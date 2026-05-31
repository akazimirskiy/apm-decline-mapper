package com.kazimir.declinemapper;

import com.kazimir.declinemapper.llm.AnthropicLlmClient;

import java.nio.file.Path;
import java.time.Instant;

/**
 * CLI entry point. Positional args, no flags.
 *
 * <pre>
 *   java -jar decline-mapper.jar &lt;input.txt&gt; &lt;provider&gt; &lt;version&gt; &lt;output.json&gt;
 * </pre>
 *
 * Exit codes:
 * <ul>
 *     <li>{@code 0} — all codes mapped, no review, no unmapped.</li>
 *     <li>{@code 1} — bootstrap failure ({@code BOOTSTRAP_ERROR:} on stderr) OR
 *         all mapped but at least one needs_human_review.</li>
 *     <li>{@code 2} — at least one code unmapped (recovery / budget / parse_garbage).</li>
 * </ul>
 */
public final class Main {

    public static void main(String[] args) {
        Bootstrap.Result bootstrap;
        try {
            bootstrap = new Bootstrap().run();
        } catch (Bootstrap.BootstrapException be) {
            System.err.println("BOOTSTRAP_ERROR: " + be.getMessage());
            System.exit(1);
            return;
        }

        if (args.length != 4) {
            System.err.println("Usage: decline-mapper <input.txt> <provider> <version> <output.json>");
            System.exit(1);
            return;
        }

        Path inputFile = Path.of(args[0]);
        String provider = args[1];
        String version = args[2];
        Path outputFile = Path.of(args[3]);

        Path logFile = Path.of("logs", "run-" + Instant.now().toEpochMilli() + ".jsonl");
        Path cacheDir = Path.of("logs", "cache");

        try {
            AnthropicLlmClient llm = new AnthropicLlmClient(
                    bootstrap.config().anthropicApiKey(), logFile);
            Pipeline pipeline = new Pipeline(bootstrap, llm, cacheDir);
            Pipeline.RunResult run = pipeline.run(inputFile, provider, version, outputFile);

            // Print summary to stdout
            System.out.println("provider=" + run.result().provider());
            System.out.println("version=" + run.result().version());
            System.out.println("total_codes=" + run.result().summary().totalCodes());
            System.out.println("mapped=" + run.result().summary().mapped());
            System.out.println("needs_review=" + run.result().summary().needsReview());
            System.out.println("unmapped=" + run.result().summary().unmapped());
            System.out.println("llm_calls=" + run.result().summary().llmCalls());
            System.out.println("tokens_used=" + run.result().summary().tokensUsed());
            System.out.println("budget_exhausted=" + run.result().summary().budgetExhausted());

            System.exit(run.exitCode());
        } catch (Exception e) {
            System.err.println("FATAL: " + e.getMessage());
            e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private Main() {}
}
