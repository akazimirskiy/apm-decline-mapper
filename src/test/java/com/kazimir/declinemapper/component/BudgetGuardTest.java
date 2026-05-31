package com.kazimir.declinemapper.component;

import com.kazimir.declinemapper.Bootstrap;
import com.kazimir.declinemapper.Pipeline;
import com.kazimir.declinemapper.fakes.ScriptedLlmClient;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.AmbiguityPattern;
import com.kazimir.declinemapper.model.Config;
import com.kazimir.declinemapper.model.MappingResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

class BudgetGuardTest {

    // ---- helpers ----

    private static Function<String, String> testEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test");
        return m::get;
    }

    private static List<AmbiguityPattern> productionPatterns() {
        return new Bootstrap(testEnv(), "/ambiguity_patterns.yaml").run().patterns();
    }

    /** Build a Bootstrap.Result with custom budget ceilings; everything else default. */
    private static Bootstrap.Result bootstrapWith(int maxCalls, int maxTokens, boolean cacheEnabled) {
        Config cfg = new Config("sk-test", "claude-sonnet-4-5",
                maxCalls, maxTokens, cacheEnabled, false);
        return new Bootstrap.Result(cfg, productionPatterns());
    }

    /** Tiny provider doc with N codes — one per declaration line. */
    private static String docWith(int n) {
        StringBuilder sb = new StringBuilder("== Codes ==\n\n");
        for (int i = 1; i <= n; i++) {
            sb.append("X-").append(String.format("%03d", i)).append(" \"msg ").append(i).append("\"\n");
            sb.append("    description ").append(i).append("\n\n");
        }
        return sb.toString();
    }

    /** Build a chaos response: every code in the request comes back COMMON_DECLINE/medium. */
    private static LlmResponse genericResponseFor(List<String> codes) {
        StringBuilder sb = new StringBuilder("{\"mappings\":[");
        for (int i = 0; i < codes.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{")
                .append("\"provider_code\":\"").append(codes.get(i)).append("\",")
                .append("\"internal_category\":\"COMMON_DECLINE\",")
                .append("\"confidence\":\"medium\",")
                .append("\"reasoning\":\"Generic reasoning.\",")
                .append("\"retry_strategy\":\"no_retry\",")
                .append("\"needs_human_review\":false,")
                .append("\"review_reason\":null")
                .append("}");
        }
        sb.append("]}");
        return new LlmResponse("msg_b", "claude-sonnet-4-5", "end_turn",
                List.of(new LlmResponse.ToolUse("map_codes", sb.toString())),
                /*tokensIn*/ 100, /*tokensOut*/ 50);
    }

    private static MappingResult run(int codeCount, int batchSize, int maxCalls, int maxTokens,
                                     boolean cacheEnabled, Path workDir) throws IOException {
        Path input = workDir.resolve("input.txt");
        Files.writeString(input, docWith(codeCount));
        Path output = workDir.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> genericResponseFor(req.providerCodesInBatch()));

        Bootstrap.Result bs = bootstrapWith(maxCalls, maxTokens, cacheEnabled);
        Pipeline pipeline = new Pipeline(bs, llm, workDir.resolve("cache"), batchSize);
        return pipeline.run(input, "Test", "1.0", output).result();
    }

    // ---- Test #23: MAX_LLM_CALLS=1, 10 codes, batch=5 → 5 mapped, 5 unmapped, exit 2 ----

    @Test
    void budget_exhaustsLlmCalls_andUnmapsRemaining(@TempDir Path tmp) throws IOException {
        // 10 codes, batch=5 → 2 batches required, budget=1 call only.
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(10));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> genericResponseFor(req.providerCodesInBatch()));

        Bootstrap.Result bs = bootstrapWith(/*maxCalls*/ 1, /*maxTokens*/ 100_000, true);
        Pipeline pipeline = new Pipeline(bs, llm, tmp.resolve("cache"), /*batchSize*/ 5);
        Pipeline.RunResult run = pipeline.run(input, "Test", "1.0", output);

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.result().summary().mapped()).isEqualTo(5);
        assertThat(run.result().summary().unmapped()).isEqualTo(5);
        assertThat(run.result().summary().budgetExhausted()).isTrue();
        assertThat(run.result().summary().llmCalls()).isEqualTo(1);

        // The unmapped entries must carry budget_exhausted in review_reason.
        long budgetUnmapped = run.result().mappings().stream()
                .filter(m -> m.internalCategory() == null)
                .filter(m -> m.reviewReason() != null && m.reviewReason().contains("budget_exhausted"))
                .count();
        assertThat(budgetUnmapped).isEqualTo(5);
    }

    // ---- Test #24: MAX_TOKENS_PER_RUN hit mid-run → remainder unmapped ----

    @Test
    void budget_exhaustsTokens_andUnmapsRemaining(@TempDir Path tmp) throws IOException {
        // Each LLM call uses 150 tokens (100 in + 50 out). With max tokens = 100, the
        // first call fits (budget checked before recording: 0 < 100); the second call
        // is blocked (150 >= 100).
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(10));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> genericResponseFor(req.providerCodesInBatch()));

        Bootstrap.Result bs = bootstrapWith(/*maxCalls*/ 100, /*maxTokens*/ 100, true);
        Pipeline pipeline = new Pipeline(bs, llm, tmp.resolve("cache"), 5);
        Pipeline.RunResult run = pipeline.run(input, "Test", "1.0", output);

        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.result().summary().budgetExhausted()).isTrue();
        assertThat(run.result().summary().mapped()).isEqualTo(5);
        assertThat(run.result().summary().unmapped()).isEqualTo(5);
        assertThat(run.result().summary().tokensUsed()).isEqualTo(150);
    }

    // ---- Test #25: budget exhausted → result.json still written ----

    @Test
    void budget_writesResultJsonEvenOnFullExhaustion(@TempDir Path tmp) throws IOException {
        // Budget = 0 calls → cannot make any LLM call. All codes must come back as unmapped.
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(3));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient();  // empty queue!

        Bootstrap.Result bs = bootstrapWith(/*maxCalls*/ 0, /*maxTokens*/ 0, true);
        Pipeline pipeline = new Pipeline(bs, llm, tmp.resolve("cache"), 5);
        Pipeline.RunResult run = pipeline.run(input, "Test", "1.0", output);

        // Headline: file exists even though we made zero LLM calls.
        assertThat(output).exists();
        assertThat(run.exitCode()).isEqualTo(2);
        assertThat(run.result().summary().totalCodes()).isEqualTo(3);
        assertThat(run.result().summary().mapped()).isEqualTo(0);
        assertThat(run.result().summary().unmapped()).isEqualTo(3);
        assertThat(run.result().summary().budgetExhausted()).isTrue();
        assertThat(run.result().summary().llmCalls()).isEqualTo(0);
        assertThat(llm.callCount()).isEqualTo(0);

        // All entries marked budget_exhausted.
        assertThat(run.result().mappings()).allSatisfy(m -> {
            assertThat(m.reviewReason()).contains("budget_exhausted");
            assertThat(m.internalCategory()).isNull();
        });
    }

    // ---- Test #57: cache hits do NOT count against MAX_LLM_CALLS ----

    @Test
    void cacheHits_doNotCountAgainstBudget(@TempDir Path tmp) throws IOException {
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(5));
        Path cacheDir = tmp.resolve("cache");

        // First run: populate cache (generous budget).
        {
            ScriptedLlmClient llm1 = new ScriptedLlmClient()
                    .setHandler(req -> genericResponseFor(req.providerCodesInBatch()));
            Bootstrap.Result bs1 = bootstrapWith(40, 200_000, true);
            Pipeline p1 = new Pipeline(bs1, llm1, cacheDir, 5);
            Pipeline.RunResult run1 = p1.run(input, "Test", "1.0", tmp.resolve("out1.json"));
            assertThat(run1.result().summary().llmCalls()).isEqualTo(1);
            assertThat(llm1.callCount()).isEqualTo(1);
        }

        // Second run: budget = 0 calls — but the cache should serve everything.
        ScriptedLlmClient llm2 = new ScriptedLlmClient();  // empty queue!
        Bootstrap.Result bs2 = bootstrapWith(/*maxCalls*/ 0, /*maxTokens*/ 0, true);
        Pipeline p2 = new Pipeline(bs2, llm2, cacheDir, 5);
        Pipeline.RunResult run2 = p2.run(input, "Test", "1.0", tmp.resolve("out2.json"));

        // No LLM calls were made — the cache absorbed everything.
        assertThat(llm2.callCount()).isEqualTo(0);
        assertThat(run2.result().summary().llmCalls()).isEqualTo(0);
        assertThat(run2.result().summary().budgetExhausted()).isFalse();
        // And all codes are mapped (none unmapped).
        assertThat(run2.result().summary().unmapped()).isEqualTo(0);
        assertThat(run2.result().summary().mapped()).isEqualTo(5);
    }
}
