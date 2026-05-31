package com.bp.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One row in the final {@code result.json}. Mirrors the schema in the assignment PDF
 * with the strict enum types layered on top.
 *
 * <p>{@code internalCategory} is nullable only for {@code unmapped} entries
 * (recovery exhausted / budget exhausted / parse_garbage). The LLM never produces
 * a null category — the Sanitizer guards that.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record Mapping(
        @JsonProperty("provider_code")      String providerCode,
        @JsonProperty("provider_message")   String providerMessage,
        @JsonProperty("internal_category")  Category internalCategory,
        @JsonProperty("confidence")         Confidence confidence,
        @JsonProperty("reasoning")          String reasoning,
        @JsonProperty("retry_strategy")     RetryStrategy retryStrategy,
        @JsonProperty("needs_human_review") boolean needsHumanReview,
        @JsonProperty("review_reason")      String reviewReason
) {
    /** Convenience: produce a copy with a new confidence and review state (Validator V3, V4 use this). */
    public Mapping withConfidenceAndReview(Confidence newConf, boolean review, String reviewReason) {
        return new Mapping(providerCode, providerMessage, internalCategory, newConf,
                reasoning, retryStrategy, review, reviewReason);
    }

    /** Convenience: produce a copy with updated reasoning (V5 re-validation refines, doesn't replace). */
    public Mapping withReasoning(String newReasoning) {
        return new Mapping(providerCode, providerMessage, internalCategory, confidence,
                newReasoning, retryStrategy, needsHumanReview, reviewReason);
    }
}
