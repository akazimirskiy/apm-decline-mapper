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

/**
 * Regression tests for the "result.json is always written" contract.
 *
 * <p>The code-review BLOCKER finding was that two exception paths
 * (SanitizationException, IOException from mapBatch) crashed the run before
 * the result file was written. These tests lock in the partial-write fix.
 */
class PipelinePartialWriteTest {

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

    private static String docWith(int n) {
        StringBuilder sb = new StringBuilder("== Codes ==\n\n");
        for (int i = 1; i <= n; i++) {
            sb.append("X-").append(String.format("%03d", i))
              .append(" \"msg ").append(i).append("\"\n");
            sb.append("    description ").append(i).append("\n\n");
        }
        return sb.toString();
    }

    /** Wrong tool name in the LLM response — Sanitizer raises a fatal exception. */
    private static LlmResponse wrongToolNameResponse() {
        return new LlmResponse("msg_x", "claude-sonnet-4-5", "end_turn",
                List.of(new LlmResponse.ToolUse("map_decline_codes", "{\"mappings\":[]}")),
                100, 50);
    }

    /** Headline: a fatal SanitizationException no longer crashes the run. */
    @Test
    void resultJson_isWritten_whenSanitizerThrowsFatal(@TempDir Path tmp) throws IOException {
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(3));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> wrongToolNameResponse());

        Pipeline pipeline = new Pipeline(bootstrap(), llm, tmp.resolve("cache"), 5);
        Pipeline.RunResult run = pipeline.run(input, "Test", "1.0", output);

        // Headline: file exists.
        assertThat(output).as("result.json must be written even on fatal sanitizer error").exists();

        MappingResult r = run.result();
        assertThat(r.summary().totalCodes()).isEqualTo(3);
        assertThat(r.summary().unmapped()).isEqualTo(3);
        // All codes carry the fatal reason for the operator to see.
        assertThat(r.mappings()).allSatisfy(m ->
                assertThat(m.reviewReason()).startsWith("sanitizer_fatal:"));
        // Exit 2 — codes are unmapped.
        assertThat(run.exitCode()).isEqualTo(2);
    }

    /** Transport-level IOException from mapBatch also routes through the partial-write path. */
    @Test
    void resultJson_isWritten_whenTransportFails(@TempDir Path tmp) throws IOException {
        Path input = tmp.resolve("input.txt");
        Files.writeString(input, docWith(3));
        Path output = tmp.resolve("result.json");

        ScriptedLlmClient llm = new ScriptedLlmClient()
                .setHandler(req -> {
                    throw new RuntimeException(new IOException("simulated network failure"));
                });

        Pipeline pipeline = new Pipeline(bootstrap(), llm, tmp.resolve("cache"), 5);

        // The handler throws a RuntimeException wrapping IOException — LlmMapper unwraps to
        // surface as IOException, Pipeline catches it in processBatch. Either way, result.json
        // must land on disk.
        try {
            pipeline.run(input, "Test", "1.0", output);
        } catch (Exception ignore) {
            // OK — we don't care if it propagates here. The point is the file exists.
        }
        assertThat(output).as("result.json must be written even on transport failure").exists();
    }
}
