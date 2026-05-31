package com.bp.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Aggregate stats appended to every {@code result.json}.
 *
 * @param totalCodes      total entries in {@code mappings} (Ok + Garbage + unmapped)
 * @param mapped          codes with a non-null {@code internal_category}
 * @param highConfidence  codes with {@code confidence == HIGH}
 * @param needsReview     codes with {@code needs_human_review == true}
 * @param unmapped        codes with null {@code internal_category}
 *                        (budget_exhausted / recovery_exhausted / parse_garbage)
 * @param budgetExhausted true if the run hit MAX_LLM_CALLS or MAX_TOKENS_PER_RUN
 * @param llmCalls        real LLM round-trips (cache hits not counted)
 * @param tokensUsed      input + output tokens billed across real calls
 */
public record Summary(
        @JsonProperty("total_codes")      int totalCodes,
        @JsonProperty("mapped")           int mapped,
        @JsonProperty("high_confidence")  int highConfidence,
        @JsonProperty("needs_review")     int needsReview,
        @JsonProperty("unmapped")         int unmapped,
        @JsonProperty("budget_exhausted") boolean budgetExhausted,
        @JsonProperty("llm_calls")        int llmCalls,
        @JsonProperty("tokens_used")      int tokensUsed
) {
}
