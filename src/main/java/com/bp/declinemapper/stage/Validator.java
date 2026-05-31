package com.bp.declinemapper.stage;

import com.bp.declinemapper.model.AmbiguityPattern;
import com.bp.declinemapper.model.Confidence;
import com.bp.declinemapper.model.Mapping;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stage 4 — semantic validation + recovery decision.
 *
 * <p>Six checks (V1..V5 from the architecture, plus the recovery cap):
 * <ol>
 *     <li>V1 (schema/enum) — already enforced by the {@link ResponseSanitizer}
 *         + Jackson enum deserialization. This stage assumes inputs are valid
 *         {@link Mapping} records.</li>
 *     <li>V2 (coverage) — every input code must be present; handled by callers
 *         comparing expected codes to {@code mappings.stream().map(Mapping::providerCode)}.
 *         The Sanitizer surfaces missing codes via {@code NeedsReprompt}.</li>
 *     <li>V3 (hedge demotion) — {@link #demoteIfHedged(Mapping)}: {@code HIGH} +
 *         a hedge token in {@code reasoning} → {@code MEDIUM}.</li>
 *     <li>V4 (ambiguity patterns) — {@link #applyAmbiguityPatterns(Mapping)}: any
 *         pattern match on {@code provider_message} forces {@code LOW + review_reason},
 *         regardless of what the LLM said. <strong>This is the load-bearing layer.</strong></li>
 *     <li>V5 (re-validation pass) — {@link #codesNeedingRevalidation(List)} identifies
 *         {@code LOW} codes for a batch-of-1 re-call; the merge in
 *         {@link #mergeRevalidation(Mapping, Mapping)} keeps the V4 flags sticky.</li>
 *     <li>Recovery cap — per-code retry limit; enforced by the Pipeline using
 *         {@code retryCountByCode}.</li>
 * </ol>
 *
 * <p>V4 wins over V3 by construction: V3 only moves HIGH → MEDIUM, V4 always sets LOW.
 * The combined {@link #applyChecks(List)} runs V3 then V4, but the end-state would be
 * the same in either order.
 */
public final class Validator {

    /** Hedge tokens that contradict {@code confidence: high}. Case-insensitive. */
    public static final Pattern HEDGE_RE = Pattern.compile(
            "\\b(could be|might|ambiguous|unclear|depends|possibly|maybe|not sure|seems)\\b",
            Pattern.CASE_INSENSITIVE);

    public static final String AMBIGUITY_REASON_PREFIX = "known_ambiguous_pattern: ";

    private final List<AmbiguityPattern> ambiguityPatterns;

    public Validator(List<AmbiguityPattern> ambiguityPatterns) {
        this.ambiguityPatterns = List.copyOf(ambiguityPatterns);
    }

    // ---- V3 ----

    /** V3: demote HIGH to MEDIUM when reasoning contains a hedge token. */
    public Mapping demoteIfHedged(Mapping m) {
        if (m.confidence() != Confidence.HIGH) return m;
        if (m.reasoning() == null) return m;
        if (!HEDGE_RE.matcher(m.reasoning()).find()) return m;
        return m.withConfidenceAndReview(Confidence.MEDIUM, m.needsHumanReview(), m.reviewReason());
    }

    // ---- V4 ----

    /**
     * V4: if {@code provider_message} matches any ambiguity pattern, force
     * {@code LOW + needs_human_review:true + review_reason} regardless of the LLM's output.
     * First match wins — the order of patterns in {@code ambiguity_patterns.yaml} pins precedence.
     */
    public Mapping applyAmbiguityPatterns(Mapping m) {
        if (m.providerMessage() == null) return m;
        for (AmbiguityPattern p : ambiguityPatterns) {
            if (p.match().matcher(m.providerMessage()).find()) {
                return m.withConfidenceAndReview(
                        Confidence.LOW, true,
                        AMBIGUITY_REASON_PREFIX + p.reason());
            }
        }
        return m;
    }

    // ---- Combined V3 + V4 ----

    /** Apply V3 then V4 to every mapping. V4 always wins when both want to act on the same code. */
    public List<Mapping> applyChecks(List<Mapping> mappings) {
        List<Mapping> out = new ArrayList<>(mappings.size());
        for (Mapping m : mappings) {
            Mapping afterV3 = demoteIfHedged(m);
            Mapping afterV4 = applyAmbiguityPatterns(afterV3);
            out.add(afterV4);
        }
        return out;
    }

    // ---- V5 selection + merge ----

    /** Returns the set of codes that should be re-validated single-code (V5). */
    public Set<String> codesNeedingRevalidation(List<Mapping> mappings) {
        return mappings.stream()
                .filter(m -> m.confidence() == Confidence.LOW)
                .map(Mapping::providerCode)
                .collect(Collectors.toSet());
    }

    /**
     * V5: merge a re-validated mapping into the original.
     *
     * <p>If the original was pinned by V4 (review_reason starts with
     * {@code known_ambiguous_pattern:}), the LOW confidence, the review flag, and
     * the review_reason are <strong>sticky</strong> — re-validation may only refine
     * reasoning, category, and retry_strategy. This is the test-review #21 contract.
     */
    public Mapping mergeRevalidation(Mapping original, Mapping revalidated) {
        boolean v4Pinned = isV4Pinned(original);
        if (!v4Pinned) {
            // Original was LOW for some other reason (LLM, V3 demotion). Re-validation
            // is allowed to refine wholesale.
            return revalidated;
        }
        // V4-pinned: refine details, keep the load-bearing flags.
        return new Mapping(
                original.providerCode(),
                original.providerMessage(),
                revalidated.internalCategory() != null ? revalidated.internalCategory() : original.internalCategory(),
                original.confidence(),                          // STICKY: LOW
                revalidated.reasoning() != null ? revalidated.reasoning() : original.reasoning(),
                revalidated.retryStrategy() != null ? revalidated.retryStrategy() : original.retryStrategy(),
                original.needsHumanReview(),                    // STICKY: true
                original.reviewReason()                         // STICKY: V4 reason
        );
    }

    private static boolean isV4Pinned(Mapping m) {
        return m.reviewReason() != null && m.reviewReason().startsWith(AMBIGUITY_REASON_PREFIX);
    }
}
