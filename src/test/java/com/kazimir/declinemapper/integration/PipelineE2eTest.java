package com.kazimir.declinemapper.integration;

import com.kazimir.declinemapper.Bootstrap;
import com.kazimir.declinemapper.Pipeline;
import com.kazimir.declinemapper.fakes.ScriptedLlmClient;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.AmbiguityPattern;
import com.kazimir.declinemapper.model.Confidence;
import com.kazimir.declinemapper.model.Config;
import com.kazimir.declinemapper.model.MappingResult;
import com.kazimir.declinemapper.stage.Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full pipeline integration tests. Uses {@link ScriptedLlmClient} with a smart
 * per-code handler — the handler returns a realistic gold-standard categorization
 * for every code we throw at it, simulating a well-behaved LLM.
 *
 * <p>The tests then assert <em>structural</em> properties: code coverage, the four
 * named ambiguous codes are reviewed, exit code, summary counts. Free-text fields
 * (reasoning, token counts) are presence-only.
 *
 * <p>Side-effect: writes a stable copy of the QuickPay run to {@code samples/}
 * so the reference output is committed in the repo for human inspection.
 */
class PipelineE2eTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    // ---- helpers ----

    private static Function<String, String> testEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test");
        return m::get;
    }

    private static List<AmbiguityPattern> productionPatterns() {
        return new Bootstrap(testEnv(), "/ambiguity_patterns.yaml").run().patterns();
    }

    private static Bootstrap.Result bootstrap() {
        Config cfg = new Config("sk-test", "claude-sonnet-4-5", 100, 1_000_000, true);
        return new Bootstrap.Result(cfg, productionPatterns());
    }

    /** Gold-standard categorizations the simulated LLM returns. */
    private static final Map<String, String[]> QUICKPAY_GOLD = quickPayGold();

    private static Map<String, String[]> quickPayGold() {
        Map<String, String[]> m = new HashMap<>();
        // {category, confidence, retry_strategy}
        m.put("QP-001", new String[]{"COMMON_DECLINE",      "medium", "no_retry"});
        m.put("QP-002", new String[]{"COMMON_DECLINE",      "high",   "no_retry"});
        m.put("QP-003", new String[]{"COMMON_DECLINE",      "high",   "no_retry"});
        m.put("QP-004", new String[]{"BAD_DATA_PROVIDED",   "high",   "retry_after_fix"});
        m.put("QP-005", new String[]{"COMMON_DECLINE",      "high",   "no_retry"}); // V4 forces LOW
        m.put("QP-006", new String[]{"ANTIFRAUD",           "high",   "no_retry"});
        m.put("QP-007", new String[]{"COMMON_DECLINE",      "medium", "no_retry"});
        m.put("QP-008", new String[]{"COMMON_DECLINE",      "high",   "no_retry"}); // V4 forces LOW
        m.put("QP-009", new String[]{"COMMON_DECLINE",      "high",   "no_action"}); // V4 forces LOW
        m.put("QP-010", new String[]{"COMMON_DECLINE",      "high",   "no_retry"});
        m.put("QP-100", new String[]{"SYSTEM_MALFUNCTION",  "high",   "retry_with_backoff"});
        m.put("QP-101", new String[]{"SYSTEM_MALFUNCTION",  "high",   "retry_with_backoff"});
        m.put("QP-102", new String[]{"SYSTEM_MALFUNCTION",  "high",   "retry_with_backoff"});
        m.put("QP-103", new String[]{"PROVIDER_LIMIT",      "high",   "retry_with_backoff"});
        m.put("QP-200", new String[]{"BAD_DATA_PROVIDED",   "high",   "retry_after_fix"});
        m.put("QP-201", new String[]{"BAD_DATA_PROVIDED",   "high",   "retry_after_fix"});
        m.put("QP-202", new String[]{"BAD_DATA_PROVIDED",   "high",   "retry_after_fix"});
        m.put("QP-203", new String[]{"BAD_DATA_PROVIDED",   "medium", "retry_after_fix"});
        m.put("QP-204", new String[]{"BAD_DATA_PROVIDED",   "high",   "retry_after_fix"});
        m.put("QP-300", new String[]{"AUTHENTICATION_FAILURE", "high", "retry_after_fix"});
        m.put("QP-301", new String[]{"AUTHENTICATION_FAILURE", "high", "retry_after_fix"});
        m.put("QP-302", new String[]{"AUTHENTICATION_FAILURE", "high", "retry_after_fix"});
        m.put("QP-303", new String[]{"AUTHENTICATION_FAILURE", "high", "retry_after_fix"});
        m.put("QP-400", new String[]{"CANCELLED_BY_CUSTOMER", "high", "no_retry"});
        m.put("QP-401", new String[]{"CANCELLED_BY_CUSTOMER", "high", "no_retry"}); // V4 forces LOW
        m.put("QP-402", new String[]{"CANCELLED_BY_CUSTOMER", "high", "no_retry"});
        return m;
    }

    private static final Map<String, String[]> FASTBANK_GOLD = fastBankGold();

    private static Map<String, String[]> fastBankGold() {
        Map<String, String[]> m = new HashMap<>();
        m.put("FB-001",         new String[]{"COMMON_DECLINE",         "high",   "no_retry"});
        m.put("FB-002",         new String[]{"COMMON_DECLINE",         "high",   "no_retry"}); // V4 -> LOW
        m.put("05",             new String[]{"ANTIFRAUD",              "medium", "no_retry"});
        m.put("ERR_RATE_LIMIT", new String[]{"PROVIDER_LIMIT",         "high",   "retry_with_backoff"});
        m.put("FB-V01",         new String[]{"BAD_DATA_PROVIDED",      "high",   "retry_after_fix"});
        m.put("FB-C01",         new String[]{"CANCELLED_BY_CUSTOMER",  "high",   "no_retry"});
        m.put("FB-C02",         new String[]{"COMMON_DECLINE",         "high",   "no_action"}); // V4 -> LOW
        m.put("FB-A01",         new String[]{"AUTHENTICATION_FAILURE", "high",   "retry_after_fix"});
        return m;
    }

    private static LlmResponse simulate(LlmRequest req, Map<String, String[]> gold) {
        List<String> codes = req.providerCodesInBatch();
        StringBuilder mappings = new StringBuilder("{\"mappings\":[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) mappings.append(',');
            String code = codes.get(i);
            String[] g = gold.getOrDefault(code,
                    new String[]{"COMMON_DECLINE", "medium", "no_retry"});
            mappings.append("{")
                .append("\"provider_code\":\"").append(code).append("\",")
                .append("\"internal_category\":\"").append(g[0]).append("\",")
                .append("\"confidence\":\"").append(g[1]).append("\",")
                .append("\"reasoning\":\"Reasoning for ").append(code).append(": ")
                .append("classified as ").append(g[0]).append(".\",")
                .append("\"retry_strategy\":\"").append(g[2]).append("\",")
                .append("\"needs_human_review\":false,")
                .append("\"review_reason\":null")
                .append("}");
        }
        mappings.append("]}");
        return new LlmResponse("msg_e2e", "claude-sonnet-4-5", "end_turn",
                List.of(new LlmResponse.ToolUse("map_codes", mappings.toString())),
                500, 200);
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream in = PipelineE2eTest.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    // ---- Test #28: QuickPay end-to-end + sample file generation ----

    @Test
    void e2e_quickpay_structurallyMatchesGold_andNames4AmbiguousCodes(@TempDir Path tmp) throws IOException {
        // Use the production fixture (same file the CLI would consume).
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, loadResource("/fixtures/quickpay_v2.4.txt"));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> simulate(req, QUICKPAY_GOLD));

        Pipeline pipeline = new Pipeline(bootstrap(), llm, tmp.resolve("cache"));
        Pipeline.RunResult run = pipeline.run(input, "QuickPay Global", "2.4", output);

        // Structural pins:
        assertThat(output).exists();
        MappingResult r = run.result();
        assertThat(r.provider()).isEqualTo("QuickPay Global");
        assertThat(r.version()).isEqualTo("2.4");
        assertThat(r.summary().totalCodes()).isEqualTo(26);
        assertThat(r.summary().mapped()).isEqualTo(26);
        assertThat(r.summary().unmapped()).isEqualTo(0);
        assertThat(r.summary().budgetExhausted()).isFalse();

        // Headline: exactly these 4 codes flagged for review, all V4-pinned.
        Set<String> reviewedCodes = r.mappings().stream()
                .filter(m -> m.needsHumanReview())
                .map(m -> m.providerCode())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(reviewedCodes)
                .containsExactlyInAnyOrder("QP-005", "QP-008", "QP-009", "QP-401");

        // Every reviewed code is V4-flagged.
        r.mappings().stream()
                .filter(m -> m.needsHumanReview())
                .forEach(m -> assertThat(m.reviewReason())
                        .startsWith(Validator.AMBIGUITY_REASON_PREFIX));

        // Spot checks on category contracts:
        assertCategoryEquals(r, "QP-002", "COMMON_DECLINE", "high", "no_retry");
        assertCategoryEquals(r, "QP-006", "ANTIFRAUD",      "high", "no_retry");
        assertCategoryEquals(r, "QP-103", "PROVIDER_LIMIT", "high", "retry_with_backoff");
        assertCategoryEquals(r, "QP-300", "AUTHENTICATION_FAILURE", "high", "retry_after_fix");
        assertCategoryEquals(r, "QP-400", "CANCELLED_BY_CUSTOMER",  "high", "no_retry");

        // QP-009 retry_strategy: V4 forces LOW + review but does NOT touch retry_strategy.
        // The LLM-gold sets QP-009 to no_action; that stays.
        assertCategoryEquals(r, "QP-009", "COMMON_DECLINE", "low", "no_action");

        // Exit code: needs_review > 0 → exit 1
        assertThat(run.exitCode()).isEqualTo(1);

        // Side-effect: persist the same JSON to samples/ for the repo to commit.
        // This makes the gold output visible to PR reviewers without running tests.
        Path samples = Path.of("samples", "quickpay_v2.4.result.json");
        Files.createDirectories(samples.getParent());
        JSON.writeValue(samples.toFile(), r);
    }

    // ---- FastBank end-to-end ----

    @Test
    void e2e_fastbank_structurallyMatchesGold_andFlagsAmbiguousCodes(@TempDir Path tmp) throws IOException {
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, loadResource("/fixtures/fastbank_v1.0.txt"));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> simulate(req, FASTBANK_GOLD));

        Pipeline pipeline = new Pipeline(bootstrap(), llm, tmp.resolve("cache"));
        Pipeline.RunResult run = pipeline.run(input, "FastBank Payment Gateway", "1.0", output);

        assertThat(output).exists();
        MappingResult r = run.result();
        assertThat(r.summary().totalCodes()).isEqualTo(8);
        assertThat(r.summary().mapped()).isEqualTo(8);
        assertThat(r.summary().unmapped()).isEqualTo(0);

        // V4 must catch FB-002 ("do_not_honor") and FB-C02 ("Duplicate charge attempt").
        Set<String> reviewedCodes = r.mappings().stream()
                .filter(m -> m.needsHumanReview())
                .map(m -> m.providerCode())
                .collect(java.util.stream.Collectors.toSet());
        assertThat(reviewedCodes).contains("FB-002", "FB-C02");

        // Exit 1 — at least one needs review, none unmapped.
        assertThat(run.exitCode()).isEqualTo(1);
    }

    // ---- helpers ----

    private static void assertCategoryEquals(MappingResult r, String code,
                                              String expectedCategory,
                                              String expectedConfidence,
                                              String expectedRetry) {
        var m = r.mappings().stream()
                .filter(x -> x.providerCode().equals(code))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing code " + code));
        assertThat(m.internalCategory()).as(code + " category").isNotNull();
        assertThat(m.internalCategory().name()).as(code + " category").isEqualTo(expectedCategory);
        assertThat(m.confidence()).as(code + " confidence").isEqualTo(parseConf(expectedConfidence));
        assertThat(m.retryStrategy().name().toLowerCase()).as(code + " retry").isEqualTo(expectedRetry);
    }

    private static Confidence parseConf(String s) {
        return Confidence.valueOf(s.toUpperCase());
    }
}
