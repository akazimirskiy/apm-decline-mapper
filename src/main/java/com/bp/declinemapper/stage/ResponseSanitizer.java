package com.bp.declinemapper.stage;

import com.bp.declinemapper.llm.LlmResponse;
import com.bp.declinemapper.model.Mapping;
import com.bp.declinemapper.model.ProviderError;
import com.bp.declinemapper.model.SanitizationResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stage 3b — R1 defence layer. Validates the <em>syntactic</em> shape of an
 * {@link LlmResponse} before the {@link Validator} (Stage 4) sees semantic concerns.
 *
 * <p>Checks (in order):
 * <ol>
 *     <li>tool_use block present.</li>
 *     <li>tool name is the expected one ({@code map_codes}); wrong name is <strong>fatal</strong>
 *         (config bug, not LLM behaviour) and throws {@link SanitizationException}.</li>
 *     <li>tool input parses as JSON.</li>
 *     <li>{@code mappings} is a non-empty array.</li>
 *     <li>Per mapping: expected code, no duplicates, required fields present,
 *         enum strings trimmed and lower-cased before parse.</li>
 *     <li>If {@code stop_reason == max_tokens}, surface missing codes for re-prompt.</li>
 * </ol>
 *
 * <p>The sanitizer fills in {@code provider_message} from the input batch — the LLM
 * doesn't need to echo it back.
 *
 * <p>Soft errors (unknown code in response, duplicate code, malformed entry) become
 * {@link #getWarnings() warnings}; the affected mapping is dropped but the rest survive.
 * Coverage enforcement (V2) is the Validator's job, not the Sanitizer's.
 */
public final class ResponseSanitizer {

    public static final class SanitizationException extends RuntimeException {
        public SanitizationException(String message) {
            super(message);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    private final List<String> warnings = new ArrayList<>();

    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    /**
     * @param response    raw LLM response (post-cache, pre-validator).
     * @param batchByCode the batch we sent, keyed by code — used to (a) recognize which
     *                    codes are expected, (b) supply {@code provider_message} into the
     *                    {@link Mapping}.
     */
    public SanitizationResult sanitize(LlmResponse response, Map<String, ProviderError> batchByCode) {
        warnings.clear();
        Set<String> expectedCodes = new LinkedHashSet<>(batchByCode.keySet());

        // 1. tool_use block present
        if (response.toolUses() == null || response.toolUses().isEmpty()) {
            return new SanitizationResult.NeedsReprompt(
                    "no tool_use block in response; LLM returned text only — use the provided tool",
                    expectedCodes);
        }

        // 2. tool name correct — wrong name is a fatal config bug
        for (LlmResponse.ToolUse tu : response.toolUses()) {
            if (!LlmMapper.TOOL_NAME.equals(tu.name())) {
                throw new SanitizationException(
                        "unexpected tool name: '" + tu.name()
                                + "' (expected '" + LlmMapper.TOOL_NAME + "') — likely a config bug");
            }
        }

        LlmResponse.ToolUse tu = response.toolUses().get(0);

        // 3. JSON parses
        JsonNode root;
        try {
            root = JSON.readTree(tu.inputJson());
        } catch (IOException e) {
            return new SanitizationResult.NeedsReprompt(
                    "tool input is not valid JSON: " + e.getMessage(), expectedCodes);
        }

        // 4. mappings array non-empty
        JsonNode mappingsNode = root.get("mappings");
        if (mappingsNode == null || !mappingsNode.isArray() || mappingsNode.isEmpty()) {
            return new SanitizationResult.NeedsReprompt(
                    "tool input has no non-empty 'mappings' array", expectedCodes);
        }

        // 5. per-mapping processing
        List<Mapping> cleanMappings = new ArrayList<>();
        Set<String> acceptedCodes = new HashSet<>();
        for (JsonNode m : mappingsNode) {
            String code = m.path("provider_code").asText("");

            if (code.isEmpty() || !batchByCode.containsKey(code)) {
                warnings.add("response includes unknown code '" + code + "' — stripped");
                continue;
            }
            if (!acceptedCodes.add(code)) {
                warnings.add("response contains duplicate code '" + code + "' — kept first");
                continue;
            }

            // Normalize enum strings before parse: trim + lowercase
            ObjectNode normalized = (ObjectNode) m.deepCopy();
            normalizeStringField(normalized, "confidence");
            normalizeStringField(normalized, "retry_strategy");
            // internal_category is uppercase by convention; normalize to enum form
            normalizeUpperField(normalized, "internal_category");

            if (!hasRequired(normalized)) {
                warnings.add("mapping for '" + code + "' missing required fields — dropped");
                acceptedCodes.remove(code);
                continue;
            }

            try {
                Mapping parsed = JSON.treeToValue(normalized, Mapping.class);
                // Fill provider_message from the input batch — saves the LLM from echoing it back.
                ProviderError input = batchByCode.get(code);
                Mapping enriched = new Mapping(
                        parsed.providerCode(),
                        input.message(),
                        parsed.internalCategory(),
                        parsed.confidence(),
                        parsed.reasoning(),
                        parsed.retryStrategy(),
                        parsed.needsHumanReview(),
                        parsed.reviewReason()
                );
                cleanMappings.add(enriched);
            } catch (Exception e) {
                warnings.add("mapping for '" + code + "' failed to parse: "
                        + e.getMessage() + " — dropped");
                acceptedCodes.remove(code);
            }
        }

        // 6. Check for missing codes (could be due to truncation or omission)
        Set<String> missing = new LinkedHashSet<>(expectedCodes);
        missing.removeAll(acceptedCodes);

        if (!missing.isEmpty()) {
            String reason = "max_tokens".equals(response.stopReason())
                    ? "stop_reason=max_tokens; " + missing.size() + " codes missing or incomplete"
                    : missing.size() + " codes missing from response";
            return new SanitizationResult.NeedsReprompt(reason, missing);
        }

        return new SanitizationResult.Clean(cleanMappings);
    }

    private static void normalizeStringField(ObjectNode node, String fieldName) {
        JsonNode v = node.get(fieldName);
        if (v != null && v.isTextual()) {
            node.put(fieldName, v.asText().trim().toLowerCase());
        }
    }

    private static void normalizeUpperField(ObjectNode node, String fieldName) {
        JsonNode v = node.get(fieldName);
        if (v != null && v.isTextual()) {
            node.put(fieldName, v.asText().trim().toUpperCase());
        }
    }

    private static boolean hasRequired(JsonNode m) {
        return m.has("provider_code") && !m.get("provider_code").isNull()
                && m.has("internal_category") && !m.get("internal_category").isNull()
                && m.has("confidence") && !m.get("confidence").isNull()
                && m.has("reasoning") && !m.get("reasoning").isNull()
                && m.has("retry_strategy") && !m.get("retry_strategy").isNull()
                && m.has("needs_human_review");
    }
}
