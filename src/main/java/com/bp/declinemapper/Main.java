package com.bp.declinemapper;

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
 *     <li>{@code 1} — bootstrap failure (stderr prefix {@code BOOTSTRAP_ERROR:}) OR
 *         all mapped but at least one needs_human_review.</li>
 *     <li>{@code 2} — at least one code unmapped (recovery / budget / parse_garbage).</li>
 * </ul>
 *
 * <p>The Pipeline itself lands in the next implementation step; for now Main runs
 * Bootstrap and exits with a marker so {@code mvn test} can verify wiring end-to-end.
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

        // Pipeline wiring lands in the next implementation step.
        System.out.println("Bootstrap OK. Loaded " + bootstrap.patterns().size()
                + " ambiguity patterns. Model=" + bootstrap.config().llmModel()
                + ". (Pipeline not yet implemented.)");
        System.exit(0);
    }

    private Main() {}
}
