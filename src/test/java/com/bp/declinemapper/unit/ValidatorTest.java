package com.bp.declinemapper.unit;

import com.bp.declinemapper.Bootstrap;
import com.bp.declinemapper.model.AmbiguityPattern;
import com.bp.declinemapper.model.Category;
import com.bp.declinemapper.model.Confidence;
import com.bp.declinemapper.model.Mapping;
import com.bp.declinemapper.model.RetryStrategy;
import com.bp.declinemapper.stage.Validator;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorTest {

    // ---- helpers ----

    private static Function<String, String> testEnv() {
        Map<String, String> m = new HashMap<>();
        m.put("ANTHROPIC_API_KEY", "sk-test");
        return m::get;
    }

    private static List<AmbiguityPattern> productionPatterns() {
        return new Bootstrap(testEnv(), "/ambiguity_patterns.yaml").run().patterns();
    }

    private static Mapping mapping(String code, String msg, Category cat, Confidence conf,
                                   String reasoning, RetryStrategy retry,
                                   boolean review, String reviewReason) {
        return new Mapping(code, msg, cat, conf, reasoning, retry, review, reviewReason);
    }

    // ---- V3: hedge demotion (test #17) ----

    @Test
    void v3_demotesHighToMedium_whenReasoningContainsHedgeToken() {
        Validator v = new Validator(List.of());
        Mapping in = mapping("X-1", "Some message", Category.COMMON_DECLINE,
                Confidence.HIGH, "It could be a fraud case", RetryStrategy.NO_RETRY,
                false, null);

        Mapping out = v.demoteIfHedged(in);

        assertThat(out.confidence()).isEqualTo(Confidence.MEDIUM);
        // category and other fields unchanged
        assertThat(out.internalCategory()).isEqualTo(Category.COMMON_DECLINE);
    }

    @Test
    void v3_doesNotTouch_whenConfidenceAlreadyMediumOrLow() {
        Validator v = new Validator(List.of());
        Mapping medium = mapping("X-1", "msg", Category.COMMON_DECLINE,
                Confidence.MEDIUM, "could be ambiguous", RetryStrategy.NO_RETRY, false, null);
        assertThat(v.demoteIfHedged(medium)).isEqualTo(medium);

        Mapping low = mapping("X-1", "msg", Category.COMMON_DECLINE,
                Confidence.LOW, "could be ambiguous", RetryStrategy.NO_RETRY, true, "..." );
        assertThat(v.demoteIfHedged(low)).isEqualTo(low);
    }

    @Test
    void v3_doesNotTouch_whenReasoningIsClean() {
        Validator v = new Validator(List.of());
        Mapping clean = mapping("X-1", "msg", Category.ANTIFRAUD, Confidence.HIGH,
                "Provider explicitly states fraud detection flagged this transaction.",
                RetryStrategy.NO_RETRY, false, null);
        assertThat(v.demoteIfHedged(clean)).isEqualTo(clean);
    }

    // ---- V4: ambiguity patterns (test #18) ----

    @Test
    void v4_forcesLowReview_whenProviderMessageMatchesPattern() {
        Validator v = new Validator(productionPatterns());
        // LLM said HIGH ANTIFRAUD with clean prose — but the message is "Do not honor"
        Mapping fromLlm = mapping("QP-008", "Do not honor",
                Category.ANTIFRAUD, Confidence.HIGH,
                "Bank flagged fraud.",
                RetryStrategy.NO_RETRY, false, null);

        Mapping out = v.applyAmbiguityPatterns(fromLlm);

        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
        assertThat(out.needsHumanReview()).isTrue();
        assertThat(out.reviewReason()).startsWith(Validator.AMBIGUITY_REASON_PREFIX);
        assertThat(out.reviewReason()).contains("Issuer catch-all");
    }

    // ---- V4 by name: the 4 ambiguous QP codes from the assignment PDF ----

    @Test
    void v4_forcesLowReview_forQp005_transactionNotPermitted() {
        Validator v = new Validator(productionPatterns());
        Mapping in = mapping("QP-005", "Transaction not permitted",
                Category.COMMON_DECLINE, Confidence.HIGH, "Permitted not? clean text",
                RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);
        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
        assertThat(out.needsHumanReview()).isTrue();
        assertThat(out.reviewReason()).contains("antifraud, card restriction, or MCC");
    }

    @Test
    void v4_forcesLowReview_forQp008_doNotHonor() {
        Validator v = new Validator(productionPatterns());
        Mapping in = mapping("QP-008", "Do not honor",
                Category.ANTIFRAUD, Confidence.HIGH, "Confidently wrong",
                RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);
        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
        assertThat(out.needsHumanReview()).isTrue();
        assertThat(out.reviewReason()).contains("Issuer catch-all");
    }

    @Test
    void v4_forcesLowReview_forQp009_duplicateTransaction() {
        Validator v = new Validator(productionPatterns());
        Mapping in = mapping("QP-009", "Duplicate transaction",
                Category.COMMON_DECLINE, Confidence.HIGH, "Clean prose",
                // The LLM has no business calling this no_action by itself; we test
                // V4 forces the review independently of retry_strategy.
                RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);
        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
        assertThat(out.needsHumanReview()).isTrue();
        assertThat(out.reviewReason()).contains("idempotency").contains("replay");
    }

    @Test
    void v4_forcesLowReview_forQp401_sessionExpired() {
        Validator v = new Validator(productionPatterns());
        Mapping in = mapping("QP-401", "Session expired",
                Category.CANCELLED_BY_CUSTOMER, Confidence.HIGH, "Clean prose",
                RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);
        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
        assertThat(out.needsHumanReview()).isTrue();
        assertThat(out.reviewReason()).contains("abandonment").contains("system timeout");
    }

    // ---- V3 vs V4 precedence (test #18e) ----

    @Test
    void v4_winsOver_v3_whenBothTriggerOnSameCode() {
        Validator v = new Validator(productionPatterns());
        // HIGH confidence + hedge in reasoning + Do not honor message
        Mapping in = mapping("QP-008", "Do not honor",
                Category.ANTIFRAUD, Confidence.HIGH,
                "It could be a fraud case or could be a limit issue",
                RetryStrategy.NO_RETRY, false, null);

        List<Mapping> out = v.applyChecks(List.of(in));

        // V3 alone would set MEDIUM (hedge in reasoning).
        // V4 forces LOW (ambiguity pattern match).
        // V4 must win.
        Mapping result = out.get(0);
        assertThat(result.confidence()).isEqualTo(Confidence.LOW);
        assertThat(result.needsHumanReview()).isTrue();
        assertThat(result.reviewReason()).startsWith(Validator.AMBIGUITY_REASON_PREFIX);
    }

    // ---- V4 extensibility (replaces test #19; three sub-tests) ----

    @Test
    void v4_ambiguityPattern_isCaseInsensitive_whenInputUppercased() {
        Validator v = new Validator(productionPatterns());
        Mapping uppercase = mapping("X-1", "DO NOT HONOR",
                Category.ANTIFRAUD, Confidence.HIGH, "x", RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(uppercase);
        assertThat(out.confidence()).isEqualTo(Confidence.LOW);
    }

    @Test
    void v4_ambiguityPattern_reasonAppearsVerbatim_inReviewReason() {
        // Build a custom pattern with a unique reason we can search for.
        Pattern p = Pattern.compile("(?i)foobar");
        Validator v = new Validator(List.of(new AmbiguityPattern(p,
                "Verbatim explanation that should flow through unchanged.")));

        Mapping in = mapping("X-1", "Contains foobar marker",
                Category.COMMON_DECLINE, Confidence.HIGH, "x", RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);

        assertThat(out.reviewReason())
                .isEqualTo("known_ambiguous_pattern: Verbatim explanation that should flow through unchanged.");
    }

    @Test
    void v4_ambiguityPattern_firstMatchWins_whenTwoMatch() {
        Pattern p1 = Pattern.compile("(?i)alpha");
        Pattern p2 = Pattern.compile("(?i)beta");
        Validator v = new Validator(List.of(
                new AmbiguityPattern(p1, "first wins"),
                new AmbiguityPattern(p2, "second loses")));

        Mapping in = mapping("X-1", "alpha and beta both present",
                Category.COMMON_DECLINE, Confidence.HIGH, "x", RetryStrategy.NO_RETRY, false, null);
        Mapping out = v.applyAmbiguityPatterns(in);

        assertThat(out.reviewReason()).contains("first wins");
        assertThat(out.reviewReason()).doesNotContain("second loses");
    }

    // ---- V5 stickiness (test #21) ----

    @Test
    void v5_revalidationCannotRaiseConfidence_whenLowIsPinnedByV4() {
        Validator v = new Validator(productionPatterns());

        // Original: V4-pinned LOW for QP-008
        Mapping pinnedByV4 = mapping("QP-008", "Do not honor",
                Category.COMMON_DECLINE, Confidence.LOW, "original reasoning",
                RetryStrategy.NO_RETRY, true,
                Validator.AMBIGUITY_REASON_PREFIX + "Issuer catch-all — etc.");

        // Re-validation: LLM came back HIGH and tried to flip the review flag off.
        Mapping fromRevalidation = mapping("QP-008", "Do not honor",
                Category.COMMON_DECLINE, Confidence.HIGH, "refined reasoning",
                RetryStrategy.NO_RETRY, false, null);

        Mapping merged = v.mergeRevalidation(pinnedByV4, fromRevalidation);

        // STICKY:
        assertThat(merged.confidence()).isEqualTo(Confidence.LOW);
        assertThat(merged.needsHumanReview()).isTrue();
        assertThat(merged.reviewReason()).startsWith(Validator.AMBIGUITY_REASON_PREFIX);
        // REFINABLE:
        assertThat(merged.reasoning()).isEqualTo("refined reasoning");
    }

    @Test
    void v5_revalidationReplacesWholesale_whenLowWasNotPinnedByV4() {
        Validator v = new Validator(productionPatterns());

        // Original: LOW with no V4 reason — say it came from the LLM itself.
        Mapping unpinnedLow = mapping("X-1", "Some unique message",
                Category.COMMON_DECLINE, Confidence.LOW, "original",
                RetryStrategy.NO_RETRY, true, "model-self-flagged");

        Mapping revalidated = mapping("X-1", "Some unique message",
                Category.ANTIFRAUD, Confidence.HIGH, "refined",
                RetryStrategy.NO_RETRY, false, null);

        Mapping merged = v.mergeRevalidation(unpinnedLow, revalidated);

        assertThat(merged.confidence()).isEqualTo(Confidence.HIGH);
        assertThat(merged.internalCategory()).isEqualTo(Category.ANTIFRAUD);
    }

    // ---- V5 selection ----

    @Test
    void v5_codesNeedingRevalidation_returnsAllLowConfidenceCodes() {
        Validator v = new Validator(List.of());
        Mapping high = mapping("A", "msg-a", Category.COMMON_DECLINE,
                Confidence.HIGH, "x", RetryStrategy.NO_RETRY, false, null);
        Mapping low1 = mapping("B", "msg-b", Category.COMMON_DECLINE,
                Confidence.LOW, "y", RetryStrategy.NO_RETRY, true, "model");
        Mapping low2 = mapping("C", "msg-c", Category.COMMON_DECLINE,
                Confidence.LOW, "z", RetryStrategy.NO_RETRY, true, "V4");

        var codes = v.codesNeedingRevalidation(List.of(high, low1, low2));
        assertThat(codes).containsExactlyInAnyOrder("B", "C");
    }
}
