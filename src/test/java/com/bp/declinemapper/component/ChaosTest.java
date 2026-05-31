package com.bp.declinemapper.component;

import com.bp.declinemapper.Bootstrap;
import com.bp.declinemapper.fakes.ScriptedLlmClient;
import com.bp.declinemapper.llm.LlmRequest;
import com.bp.declinemapper.llm.LlmResponse;
import com.bp.declinemapper.model.AmbiguityPattern;
import com.bp.declinemapper.model.Confidence;
import com.bp.declinemapper.model.Mapping;
import com.bp.declinemapper.model.ParseOutcome;
import com.bp.declinemapper.model.ProviderError;
import com.bp.declinemapper.model.SanitizationResult;
import com.bp.declinemapper.stage.Enricher;
import com.bp.declinemapper.stage.LlmMapper;
import com.bp.declinemapper.stage.Parser;
import com.bp.declinemapper.stage.ResponseSanitizer;
import com.bp.declinemapper.stage.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Headline component test (#56) — the single proof that the LLM is a controlled
 * component, not a black box.
 *
 * <p>Scenario: feed the entire QuickPay v2.4 fixture (26 codes) through the pipeline
 * with a {@link ScriptedLlmClient} that returns
 * {@code ANTIFRAUD / HIGH / no-hedge} for <em>every</em> code regardless of context.
 *
 * <p>If the defence layers actually work, the four named ambiguous codes
 * ({@code QP-005}, {@code QP-008}, {@code QP-009}, {@code QP-401}) must still come
 * back {@code confidence: LOW} with {@code needs_human_review: true} and a
 * {@code review_reason} starting with {@code known_ambiguous_pattern:} — because
 * V4 ignores the LLM's claims and decides from {@code provider_message} alone.
 *
 * <p>If a future change silently disables V4, this test fails loudly. That is exactly
 * the regression-protection contract.
 */
class ChaosTest {

    private static String loadResource(String path) throws IOException {
        try (InputStream in = ChaosTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing test resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Function<String, String> testEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test");
        return m::get;
    }

    /** Build a chaos response: every code in the batch comes back ANTIFRAUD/HIGH. */
    private static LlmResponse chaosResponseFor(List<String> codes) {
        StringBuilder sb = new StringBuilder("{\"mappings\":[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{")
              .append("\"provider_code\":\"").append(codes.get(i)).append("\",")
              .append("\"internal_category\":\"ANTIFRAUD\",")
              .append("\"confidence\":\"high\",")
              .append("\"reasoning\":\"Provider explicitly states the transaction was flagged by fraud detection.\",")
              .append("\"retry_strategy\":\"no_retry\",")
              .append("\"needs_human_review\":false,")
              .append("\"review_reason\":null")
              .append("}");
        }
        sb.append("]}");
        return new LlmResponse("msg_chaos", "claude-sonnet-4-5", "end_turn",
                List.of(new LlmResponse.ToolUse("map_codes", sb.toString())),
                100, 50);
    }

    // ---- Test #56: chaos — the headline proof ----

    @Test
    void chaos_alwaysAntifraudHigh_pipelineStillFlagsFourAmbiguousCodes(@TempDir Path tmp) throws IOException {
        // 1) Parse the real QuickPay fixture
        String input = loadResource("/fixtures/quickpay_v2.4.txt");
        Parser parser = new Parser();
        List<ParseOutcome> outcomes = parser.parseText(input);
        List<ProviderError> codes = outcomes.stream()
                .filter(o -> o instanceof ParseOutcome.Ok)
                .map(o -> ((ParseOutcome.Ok) o).error())
                .collect(Collectors.toList());
        assertThat(codes).hasSize(26);  // sanity

        // 2) Chaos LLM client: returns ANTIFRAUD/HIGH for every code
        ScriptedLlmClient chaos = new ScriptedLlmClient()
                .setHandler((LlmRequest req) -> chaosResponseFor(req.providerCodesInBatch()));

        // 3) Run Mapper across all batches
        LlmMapper mapper = new LlmMapper(chaos, tmp, "claude-sonnet-4-5", 5,
                new Enricher(), false);
        List<LlmResponse> responses = mapper.mapAll(codes);

        // 4) Sanitize each batch and collect Mappings
        ResponseSanitizer sanitizer = new ResponseSanitizer();
        Map<String, ProviderError> codeIndex = new HashMap<>();
        for (ProviderError e : codes) codeIndex.put(e.code(), e);

        List<Mapping> allMappings = new ArrayList<>();
        for (int i = 0; i < responses.size(); i++) {
            // Build per-batch index in the order LlmMapper actually used
            List<String> batchCodes = chaos.received().get(i).providerCodesInBatch();
            Map<String, ProviderError> batchIndex = new HashMap<>();
            for (String c : batchCodes) batchIndex.put(c, codeIndex.get(c));

            SanitizationResult sr = sanitizer.sanitize(responses.get(i), batchIndex);
            assertThat(sr).as("sanitizer must accept the chaos response shape (it's well-formed)")
                    .isInstanceOf(SanitizationResult.Clean.class);
            allMappings.addAll(((SanitizationResult.Clean) sr).mappings());
        }
        assertThat(allMappings).hasSize(26);

        // 5) Apply Validator (V3 + V4) — the headline test
        List<AmbiguityPattern> patterns =
                new Bootstrap(testEnv(), "/ambiguity_patterns.yaml").run().patterns();
        Validator validator = new Validator(patterns);
        List<Mapping> validated = validator.applyChecks(allMappings);

        // 6) Headline assertions

        // The 4 named codes MUST come out LOW + review, regardless of what the LLM said.
        Set<String> namedAmbiguous = Set.of("QP-005", "QP-008", "QP-009", "QP-401");

        Map<String, Mapping> byCode = new HashMap<>();
        for (Mapping m : validated) byCode.put(m.providerCode(), m);

        for (String code : namedAmbiguous) {
            Mapping m = byCode.get(code);
            assertThat(m).as("code " + code + " must be present").isNotNull();
            assertThat(m.confidence()).as(code + " confidence").isEqualTo(Confidence.LOW);
            assertThat(m.needsHumanReview()).as(code + " review flag").isTrue();
            assertThat(m.reviewReason()).as(code + " review_reason")
                    .startsWith(Validator.AMBIGUITY_REASON_PREFIX);
        }

        // Sanity: the chaos LLM tried to call EVERYTHING ANTIFRAUD/HIGH,
        // yet at least these 4 codes were rescued by V4 — independent of LLM output.
        long flagged = validated.stream()
                .filter(m -> namedAmbiguous.contains(m.providerCode()))
                .filter(m -> m.confidence() == Confidence.LOW)
                .filter(Mapping::needsHumanReview)
                .count();
        assertThat(flagged)
                .as("if this number is < 4 the validator is silently disabled")
                .isEqualTo(4);
    }
}
