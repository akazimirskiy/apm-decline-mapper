package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.Bootstrap;
import com.kazimir.declinemapper.Bootstrap.BootstrapException;
import com.kazimir.declinemapper.model.AmbiguityPattern;
import com.kazimir.declinemapper.model.Config;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BootstrapTest {

    /** Minimum-valid env: only the API key. Everything else takes defaults. */
    private static Function<String, String> goodEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test-123");
        return m::get;
    }

    private static Function<String, String> envWith(String key, String value) {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test-123");
        m.put(key, value);
        return m::get;
    }

    // ---------- Config validation ----------

    @Test
    void bootstrap_succeeds_whenAllEnvDefaultsAreUsed() {
        Bootstrap.Result r = new Bootstrap(goodEnv(), "/test_patterns/valid.yaml").run();
        assertThat(r.config().anthropicApiKey()).isEqualTo("sk-test-123");
        assertThat(r.config().llmModel()).isEqualTo(Config.DEFAULT_MODEL);
        assertThat(r.config().maxLlmCalls()).isEqualTo(Config.DEFAULT_MAX_LLM_CALLS);
        assertThat(r.config().maxTokensPerRun()).isEqualTo(Config.DEFAULT_MAX_TOKENS_PER_RUN);
        assertThat(r.config().cacheEnabled()).isTrue();
    }

    /** Test #49: empty ANTHROPIC_API_KEY → fail-fast before parsing input. */
    @Test
    void bootstrap_fails_whenApiKeyEmpty() {
        Function<String, String> env = envWith("ANTHROPIC_API_KEY", "");
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    @Test
    void bootstrap_fails_whenApiKeyMissing() {
        // empty map → System.getenv-style lookup returns null
        Function<String, String> env = k -> null;
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("ANTHROPIC_API_KEY");
    }

    /** Test #48: negative MAX_LLM_CALLS → fail. */
    @Test
    void bootstrap_fails_whenMaxLlmCallsNegative() {
        Function<String, String> env = envWith("MAX_LLM_CALLS", "-1");
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("MAX_LLM_CALLS")
                .hasMessageContaining("> 0");
    }

    @Test
    void bootstrap_fails_whenMaxLlmCallsZero() {
        Function<String, String> env = envWith("MAX_LLM_CALLS", "0");
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("> 0");
    }

    @Test
    void bootstrap_fails_whenMaxLlmCallsNotAnInteger() {
        Function<String, String> env = envWith("MAX_LLM_CALLS", "abc");
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("positive integer");
    }

    @Test
    void bootstrap_fails_whenMaxTokensNegative() {
        Function<String, String> env = envWith("MAX_TOKENS_PER_RUN", "-100");
        assertThatThrownBy(() -> new Bootstrap(env, "/test_patterns/valid.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("MAX_TOKENS_PER_RUN");
    }

    @Test
    void bootstrap_acceptsCustomNumericEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test-123");
        m.put("MAX_LLM_CALLS", "100");
        m.put("MAX_TOKENS_PER_RUN", "500000");
        m.put("LLM_MODEL", "claude-sonnet-4-5-custom");
        m.put("CACHE_ENABLED", "false");
        Bootstrap.Result r = new Bootstrap(m::get, "/test_patterns/valid.yaml").run();
        assertThat(r.config().maxLlmCalls()).isEqualTo(100);
        assertThat(r.config().maxTokensPerRun()).isEqualTo(500_000);
        assertThat(r.config().llmModel()).isEqualTo("claude-sonnet-4-5-custom");
        assertThat(r.config().cacheEnabled()).isFalse();
    }

    // ---------- Ambiguity patterns ----------

    @Test
    void ambiguityPatterns_loaded_whenYamlValid() {
        Bootstrap.Result r = new Bootstrap(goodEnv(), "/test_patterns/valid.yaml").run();
        assertThat(r.patterns()).hasSize(2);
        assertThat(r.patterns()).extracting(AmbiguityPattern::reason)
                .contains("Issuer catch-all.", "Idempotency vs replay.");
    }

    @Test
    void ambiguityPatterns_caseInsensitive() {
        Bootstrap.Result r = new Bootstrap(goodEnv(), "/test_patterns/valid.yaml").run();
        AmbiguityPattern p = r.patterns().get(0);  // "do not honor"
        assertThat(p.match().matcher("DO NOT HONOR").find()).isTrue();
        assertThat(p.match().matcher("Do Not Honor").find()).isTrue();
        assertThat(p.match().matcher("do not honor").find()).isTrue();
    }

    @Test
    void ambiguityPatterns_fail_whenYamlMissing() {
        assertThatThrownBy(() -> new Bootstrap(goodEnv(), "/test_patterns/does_not_exist.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("not found on classpath");
    }

    /** Test #46: invalid regex → fail listing ALL bad patterns, not just the first. */
    @Test
    void ambiguityPatterns_fail_whenRegexInvalid_listingAllBadPatterns() {
        assertThatThrownBy(() ->
                new Bootstrap(goodEnv(), "/test_patterns/invalid_regex.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("does not compile")
                .hasMessageContaining("*invalid(")
                .hasMessageContaining("[unterminated");  // both, not just the first
    }

    /** Test #46a (variant 1): bare ".*" → "pattern is dangerously broad". */
    @Test
    void ambiguityPatterns_fail_whenPatternIsDotStar() {
        assertThatThrownBy(() ->
                new Bootstrap(goodEnv(), "/test_patterns/broad_dotstar.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("dangerously broad")
                .hasMessageContaining(".*");
    }

    /** Test #46a (variant 2): empty match → "dangerously broad". */
    @Test
    void ambiguityPatterns_fail_whenPatternEmpty() {
        assertThatThrownBy(() ->
                new Bootstrap(goodEnv(), "/test_patterns/broad_empty.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("dangerously broad");
    }

    /** Test #46a (variant 3): pattern matches a curated unambiguous reference. */
    @Test
    void ambiguityPatterns_fail_whenPatternMatchesReferenceMessage() {
        assertThatThrownBy(() ->
                new Bootstrap(goodEnv(), "/test_patterns/broad_matches_reference.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining("dangerously broad")
                .hasMessageContaining("Card expired");  // one of the reference messages it matches
    }

    /** Multiple kinds of errors must all be reported, not just the first. */
    @Test
    void ambiguityPatterns_fail_reportsAllErrorsAtOnce() {
        assertThatThrownBy(() ->
                new Bootstrap(goodEnv(), "/test_patterns/multiple_errors.yaml").run())
                .isInstanceOf(BootstrapException.class)
                .hasMessageContaining(".*")             // broad #1
                .hasMessageContaining("*invalid(")      // bad regex #2
                .hasMessageContaining("dangerously broad")
                .hasMessageContaining("does not compile");
    }

    /** Production YAML (the one shipped on classpath) must itself pass Bootstrap. */
    @Test
    void productionAmbiguityPatterns_loadCleanly() {
        // Use test constructor with goodEnv() so this works in CI with no real ANTHROPIC_API_KEY,
        // but point at the real production resource on the classpath.
        Bootstrap.Result rTest = new Bootstrap(goodEnv(), "/ambiguity_patterns.yaml").run();
        assertThat(rTest.patterns()).isNotEmpty();
        // The 4 named QP ambiguous codes from the assignment PDF must be coverable:
        boolean coversDoNotHonor = rTest.patterns().stream()
                .anyMatch(p -> p.match().matcher("Do not honor").find());
        boolean coversDuplicate = rTest.patterns().stream()
                .anyMatch(p -> p.match().matcher("Duplicate transaction").find());
        boolean coversNotPermitted = rTest.patterns().stream()
                .anyMatch(p -> p.match().matcher("Transaction not permitted").find());
        boolean coversSessionExpired = rTest.patterns().stream()
                .anyMatch(p -> p.match().matcher("Session expired").find());
        assertThat(coversDoNotHonor).as("QP-008 do not honor").isTrue();
        assertThat(coversDuplicate).as("QP-009 duplicate").isTrue();
        assertThat(coversNotPermitted).as("QP-005 not permitted").isTrue();
        assertThat(coversSessionExpired).as("QP-401 session expired").isTrue();
    }
}
