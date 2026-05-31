package com.kazimir.declinemapper;

import com.kazimir.declinemapper.model.AmbiguityPattern;
import com.kazimir.declinemapper.model.Config;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Init phase — fails fast on misconfiguration <em>before</em> any input is read.
 *
 * <p>Catches:
 * <ul>
 *     <li>Missing or empty {@code ANTHROPIC_API_KEY}.</li>
 *     <li>Non-positive or non-numeric {@code MAX_LLM_CALLS} / {@code MAX_TOKENS_PER_RUN}.</li>
 *     <li>Missing {@code ambiguity_patterns.yaml} on classpath.</li>
 *     <li>Malformed YAML.</li>
 *     <li>Regex that doesn't compile <strong>(reports all bad patterns, not just the first)</strong>.</li>
 *     <li>Regex that is dangerously broad (empty, {@code .*}, {@code .+}, or anything
 *         matching a curated set of clearly-unambiguous reference messages).</li>
 * </ul>
 *
 * <p>On failure, prints {@code BOOTSTRAP_ERROR: <details>} to stderr and exits with code 1
 * (handled by the caller).
 */
public final class Bootstrap {

    /**
     * Curated reference messages that <strong>must not</strong> be matched by any
     * ambiguity pattern. If a pattern matches one of these, it's too broad to be useful.
     */
    private static final List<String> UNAMBIGUOUS_REFERENCE_MESSAGES = List.of(
            "Insufficient balance",
            "Card expired",
            "Suspected fraud",
            "Invalid card number",
            "Internal server error",
            "Invalid currency code",
            "Payment cancelled by user",
            "Invalid API key"
    );

    public record Result(Config config, List<AmbiguityPattern> patterns) {
    }

    public static final class BootstrapException extends RuntimeException {
        public BootstrapException(String message) {
            super(message);
        }
    }

    private final Function<String, String> env;
    private final String ambiguityPatternsResource;

    /** Production constructor — reads from {@link System#getenv} and classpath. */
    public Bootstrap() {
        this(System::getenv, "/ambiguity_patterns.yaml");
    }

    /** Test constructor — env lookup and resource path are injectable. */
    public Bootstrap(Function<String, String> env, String ambiguityPatternsResource) {
        this.env = env;
        this.ambiguityPatternsResource = ambiguityPatternsResource;
    }

    public Result run() {
        Config config = loadConfig();
        List<AmbiguityPattern> patterns = loadAmbiguityPatterns();
        return new Result(config, patterns);
    }

    // ---- Config ----

    private Config loadConfig() {
        String apiKey = env.apply("ANTHROPIC_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new BootstrapException("ANTHROPIC_API_KEY must be set and non-empty");
        }

        String model = env.apply("LLM_MODEL");
        if (model == null || model.isBlank()) {
            model = Config.DEFAULT_MODEL;
            System.err.println("WARN: LLM_MODEL not set, defaulting to " + model);
        }

        int maxCalls = parsePositiveInt("MAX_LLM_CALLS", env.apply("MAX_LLM_CALLS"),
                Config.DEFAULT_MAX_LLM_CALLS);
        int maxTokens = parsePositiveInt("MAX_TOKENS_PER_RUN", env.apply("MAX_TOKENS_PER_RUN"),
                Config.DEFAULT_MAX_TOKENS_PER_RUN);
        boolean cacheEnabled = parseBoolean(env.apply("CACHE_ENABLED"), true);

        return new Config(apiKey, model, maxCalls, maxTokens, cacheEnabled);
    }

    private static int parsePositiveInt(String name, String raw, int defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        int value;
        try {
            value = Integer.parseInt(raw.trim());
        } catch (NumberFormatException nfe) {
            throw new BootstrapException(name + " must be a positive integer, got: " + raw);
        }
        if (value <= 0) {
            throw new BootstrapException(name + " must be > 0, got: " + value);
        }
        return value;
    }

    private static boolean parseBoolean(String raw, boolean defaultValue) {
        if (raw == null || raw.isBlank()) return defaultValue;
        String t = raw.trim().toLowerCase();
        return switch (t) {
            case "true", "yes", "1", "on" -> true;
            case "false", "no", "0", "off" -> false;
            default -> defaultValue;
        };
    }

    // ---- Ambiguity patterns ----

    private List<AmbiguityPattern> loadAmbiguityPatterns() {
        try (InputStream in = Bootstrap.class.getResourceAsStream(ambiguityPatternsResource)) {
            if (in == null) {
                throw new BootstrapException(
                        ambiguityPatternsResource + " not found on classpath");
            }

            Map<String, Object> doc;
            try {
                doc = new Yaml().load(in);
            } catch (Exception ye) {
                throw new BootstrapException(
                        "ambiguity_patterns.yaml is not valid YAML: " + ye.getMessage());
            }
            if (doc == null) {
                throw new BootstrapException(
                        "ambiguity_patterns.yaml is empty");
            }

            Object rawList = doc.get("patterns");
            if (!(rawList instanceof List<?> list)) {
                throw new BootstrapException(
                        "ambiguity_patterns.yaml: top-level 'patterns' list is missing");
            }

            List<String> compileErrors = new ArrayList<>();
            List<String> broadErrors = new ArrayList<>();
            List<AmbiguityPattern> compiled = new ArrayList<>();

            for (int i = 0; i < list.size(); i++) {
                Object entry = list.get(i);
                if (!(entry instanceof Map<?, ?> map)) {
                    compileErrors.add("[" + i + "] entry is not a mapping");
                    continue;
                }
                Object matchObj = map.get("match");
                Object reasonObj = map.get("reason");
                String matchStr = matchObj == null ? null : matchObj.toString();
                String reason = reasonObj == null ? "" : reasonObj.toString();

                if (matchStr == null || matchStr.isEmpty()) {
                    broadErrors.add("[" + i + "] pattern is dangerously broad: <empty>");
                    continue;
                }

                if (isInherentlyBroad(matchStr)) {
                    broadErrors.add("[" + i + "] pattern is dangerously broad: " + matchStr);
                    continue;
                }

                Pattern p;
                try {
                    p = Pattern.compile(matchStr, Pattern.CASE_INSENSITIVE);
                } catch (PatternSyntaxException pse) {
                    compileErrors.add("[" + i + "] regex does not compile: '" + matchStr
                            + "' (" + pse.getDescription() + ")");
                    continue;
                }

                // Smoke-test against curated unambiguous reference messages.
                List<String> hits = new ArrayList<>();
                for (String msg : UNAMBIGUOUS_REFERENCE_MESSAGES) {
                    if (p.matcher(msg).find()) {
                        hits.add(msg);
                    }
                }
                if (!hits.isEmpty()) {
                    broadErrors.add("[" + i + "] pattern is dangerously broad: '" + matchStr
                            + "' matches unambiguous reference messages: " + hits);
                    continue;
                }

                compiled.add(new AmbiguityPattern(p, reason));
            }

            // Report ALL errors at once, not just the first.
            if (!compileErrors.isEmpty() || !broadErrors.isEmpty()) {
                StringBuilder sb = new StringBuilder("ambiguity_patterns.yaml has errors:");
                for (String e : compileErrors) sb.append("\n  - ").append(e);
                for (String e : broadErrors) sb.append("\n  - ").append(e);
                throw new BootstrapException(sb.toString());
            }

            if (compiled.isEmpty()) {
                throw new BootstrapException(
                        "ambiguity_patterns.yaml contains no valid patterns");
            }

            return compiled;
        } catch (IOException ioe) {
            throw new BootstrapException("Failed reading " + ambiguityPatternsResource
                    + ": " + ioe.getMessage());
        }
    }

    /**
     * Patterns that don't need a reference-message smoke check — they're broad on their face.
     * Catches: empty, bare {@code .*}, {@code .+}, {@code (?i).*}, {@code (?i).+}, etc.
     */
    private static boolean isInherentlyBroad(String pattern) {
        String stripped = pattern.replaceAll("\\(\\?[a-zA-Z]+\\)", ""); // strip inline flags
        return stripped.isEmpty()
                || stripped.equals(".*")
                || stripped.equals(".+")
                || stripped.equals(".")
                || stripped.equals("^.*$")
                || stripped.equals("^.+$");
    }
}
