package com.kazimir.declinemapper.stage;

import com.kazimir.declinemapper.budget.BudgetGuard;
import com.kazimir.declinemapper.budget.BudgetGuard.BudgetExhaustedException;
import com.kazimir.declinemapper.llm.LlmClient;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.Category;
import com.kazimir.declinemapper.model.ProviderError;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Stage 3a — batched LLM calls with per-instance Random(42), file-backed cache,
 * and a deterministic content-addressed cache key.
 *
 * <p>Cache key: {@code sha256(model + prompt_template_version + sorted_codes_with_messages)}.
 * Cache directory is a <strong>constructor argument</strong>, never a hardcoded path —
 * tests pass {@code @TempDir} to guarantee isolation.
 *
 * <p>Within-batch order is shuffled with a fresh {@code Random(42)} per call: deterministic
 * (same input → same shuffle), thread-safe (no shared state), and effective at breaking
 * the LLM's tendency to anchor later codes in a batch to earlier ones.
 *
 * <p>This class does not parse the LLM response into Mappings — that's the
 * Sanitizer's job in Step 5. Output is the raw {@link LlmResponse} list.
 */
public final class LlmMapper {

    public static final int DEFAULT_BATCH_SIZE = 5;
    public static final long SHUFFLE_SEED = 42L;
    public static final String TOOL_NAME = "map_codes";
    public static final int MAX_TOKENS_PER_CALL = 4096;
    public static final double TEMPERATURE = 0.0;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TOOL_SCHEMA_JSON = buildToolSchemaJson();

    private final LlmClient client;
    private final Path cacheDir;
    private final String model;
    private final int batchSize;
    private final Enricher enricher;
    private final boolean cacheEnabled;
    private final BudgetGuard guard;  // optional — null means no enforcement

    /** Constructor without budget enforcement — used by unit/component tests of the mapper itself. */
    public LlmMapper(LlmClient client,
                     Path cacheDir,
                     String model,
                     int batchSize,
                     Enricher enricher,
                     boolean cacheEnabled) {
        this(client, cacheDir, model, batchSize, enricher, cacheEnabled, null);
    }

    /** Production constructor — pass a BudgetGuard to enforce the run-level ceiling. */
    public LlmMapper(LlmClient client,
                     Path cacheDir,
                     String model,
                     int batchSize,
                     Enricher enricher,
                     boolean cacheEnabled,
                     BudgetGuard guard) {
        if (cacheDir == null) {
            throw new IllegalArgumentException("cacheDir is mandatory — pass @TempDir in tests");
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batchSize must be > 0, got " + batchSize);
        }
        this.client = client;
        this.cacheDir = cacheDir;
        this.model = model;
        this.batchSize = batchSize;
        this.enricher = enricher;
        this.cacheEnabled = cacheEnabled;
        this.guard = guard;
    }

    /** Partition a list of codes into batches of at most {@link #batchSize}. */
    public List<List<ProviderError>> partition(List<ProviderError> codes) {
        List<List<ProviderError>> batches = new ArrayList<>();
        for (int i = 0; i < codes.size(); i += batchSize) {
            batches.add(new ArrayList<>(codes.subList(i, Math.min(i + batchSize, codes.size()))));
        }
        return batches;
    }

    /** Process every batch sequentially. */
    public List<LlmResponse> mapAll(List<ProviderError> codes) throws IOException {
        List<LlmResponse> all = new ArrayList<>();
        for (List<ProviderError> batch : partition(codes)) {
            all.add(mapBatch(batch));
        }
        return all;
    }

    /** Process a single batch — cache check, budget check, shuffle, LLM call, cache write. */
    public LlmResponse mapBatch(List<ProviderError> batch) throws IOException {
        if (batch.isEmpty()) {
            throw new IllegalArgumentException("cannot map an empty batch");
        }

        String cacheKey = computeCacheKey(batch);
        Path cacheFile = cacheDir.resolve(cacheKey + ".json");

        // Cache hit short-circuits BEFORE consulting the budget — cache hits are free.
        if (cacheEnabled && Files.exists(cacheFile)) {
            return JSON.readValue(cacheFile.toFile(), LlmResponse.class);
        }

        // Cache miss: only proceed if the budget allows.
        if (guard != null && guard.isExhausted()) {
            throw new BudgetExhaustedException(
                    "LLM call budget exhausted (calls=" + guard.callCount()
                            + ", tokens=" + guard.tokensUsed() + ")");
        }

        // Shuffle within batch with a fresh Random — deterministic and thread-safe.
        List<ProviderError> shuffled = new ArrayList<>(batch);
        Collections.shuffle(shuffled, new Random(SHUFFLE_SEED));

        LlmRequest request = buildRequest(shuffled);
        LlmResponse response = client.call(request);

        // Record budget AFTER the call so we count what we actually billed.
        if (guard != null) {
            guard.recordCall(response.tokensIn(), response.tokensOut());
        }

        if (cacheEnabled) {
            Files.createDirectories(cacheDir);
            JSON.writeValue(cacheFile.toFile(), response);
        }
        return response;
    }

    /** Build the user message + request envelope. Visible for tests. */
    LlmRequest buildRequest(List<ProviderError> shuffledBatch) {
        StringBuilder user = new StringBuilder();
        user.append("Map each of these provider error codes to the internal decline taxonomy. ")
            .append("Return one mapping object per code via the `").append(TOOL_NAME).append("` tool.\n\n");
        for (ProviderError e : shuffledBatch) {
            user.append(e.code()).append(" \"").append(e.message()).append("\"\n");
            if (e.description() != null && !e.description().isEmpty()) {
                user.append("    ").append(e.description()).append('\n');
            }
            if (e.section() != null && !e.section().isEmpty()) {
                user.append("    (section: ").append(e.section()).append(")\n");
            }
            user.append('\n');
        }

        List<String> codes = shuffledBatch.stream().map(ProviderError::code).toList();
        return new LlmRequest(
                model,
                enricher.systemPrompt(),
                user.toString(),
                TOOL_NAME,
                TOOL_SCHEMA_JSON,
                codes,
                MAX_TOKENS_PER_CALL,
                TEMPERATURE);
    }

    /**
     * Content-addressed cache key. Codes are sorted alphabetically so the cache hit
     * depends on the <em>set</em> of codes in the batch, not the shuffle order.
     *
     * <p>Public for testing — useful to verify the key contract (stable under reordering,
     * sensitive to any input change).
     */
    public String computeCacheKey(List<ProviderError> batch) {
        List<ProviderError> sorted = new ArrayList<>(batch);
        sorted.sort(Comparator.comparing(ProviderError::code));
        StringBuilder sb = new StringBuilder();
        sb.append(model).append('|');
        sb.append(Enricher.PROMPT_TEMPLATE_VERSION).append('|');
        for (ProviderError e : sorted) {
            sb.append(e.code()).append('|')
              .append(nullSafe(e.message())).append('|')
              .append(nullSafe(e.description())).append('\n');
        }
        return sha256(sb.toString());
    }

    private static String nullSafe(String s) {
        return s == null ? "" : s;
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                out.append(String.format("%02x", b & 0xff));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** JSON schema for the {@code map_codes} tool input. Built once at class-load. */
    private static String buildToolSchemaJson() {
        ObjectNode schema = JSON.createObjectNode();
        schema.put("type", "object");

        ObjectNode props = schema.putObject("properties");
        ObjectNode mappings = props.putObject("mappings");
        mappings.put("type", "array");

        ObjectNode item = mappings.putObject("items");
        item.put("type", "object");
        ObjectNode ip = item.putObject("properties");

        ip.putObject("provider_code").put("type", "string");

        ObjectNode cat = ip.putObject("internal_category");
        cat.put("type", "string");
        ArrayNode catEnum = cat.putArray("enum");
        for (Category c : Category.values()) catEnum.add(c.name());

        ObjectNode conf = ip.putObject("confidence");
        conf.put("type", "string");
        ArrayNode confEnum = conf.putArray("enum");
        confEnum.add("high"); confEnum.add("medium"); confEnum.add("low");

        ip.putObject("reasoning").put("type", "string");

        ObjectNode rs = ip.putObject("retry_strategy");
        rs.put("type", "string");
        ArrayNode rsEnum = rs.putArray("enum");
        rsEnum.add("no_retry"); rsEnum.add("retry_with_backoff");
        rsEnum.add("retry_after_fix"); rsEnum.add("no_action");

        ip.putObject("needs_human_review").put("type", "boolean");

        ObjectNode rev = ip.putObject("review_reason");
        ArrayNode revTypes = rev.putArray("type");
        revTypes.add("string"); revTypes.add("null");

        ArrayNode required = item.putArray("required");
        required.add("provider_code"); required.add("internal_category"); required.add("confidence");
        required.add("reasoning"); required.add("retry_strategy"); required.add("needs_human_review");

        ArrayNode topRequired = schema.putArray("required");
        topRequired.add("mappings");

        try {
            return JSON.writeValueAsString(schema);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build tool schema", e);
        }
    }
}
