package com.bp.declinemapper.stage;

import com.bp.declinemapper.model.Category;

/**
 * Stage 2 — builds the system prompt for the Mapper. Fully deterministic; no LLM call.
 *
 * <p>The prompt has five sections in fixed order:
 * <ol>
 *     <li>Internal taxonomy table (7 categories with descriptions + retry policy).</li>
 *     <li>Retry-strategy vocabulary (4 values).</li>
 *     <li>Operational confidence rubric — defines high/medium/low as checklists,
 *         so different runs don't drift in what those levels mean.</li>
 *     <li>Two synthetic, provider-neutral few-shot examples (XX-001, YY-042).
 *         Deliberately not QP-coded — avoids anchoring downstream providers to
 *         QuickPay's category distribution.</li>
 *     <li>Hard instruction: return only via the provided tool, choose strictly
 *         from the 7 values, follow the rubric.</li>
 * </ol>
 *
 * <p>Crucially, the ambiguity-pattern list is <strong>not</strong> in the prompt.
 * Ambiguity patterns are applied deterministically by the Validator (V4 defence)
 * <em>after</em> the LLM responds — that's the only way to grade the LLM independently.
 */
public final class Enricher {

    /** Template version — bump when {@link #systemPrompt()} changes. Used in cache keys. */
    public static final String PROMPT_TEMPLATE_VERSION = "v1";

    private static final String TAXONOMY = """
            # Internal Decline Taxonomy

            Every provider error must map to exactly one of these seven categories:

            | Category | Meaning | Retryable? |
            |---|---|---|
            | SYSTEM_MALFUNCTION | Internal system error, provider infrastructure failure, unexpected response | yes, with backoff |
            | COMMON_DECLINE | Generic transaction decline by the bank or provider (insufficient funds, limit exceeded, card expired) | depends on sub-code |
            | ANTIFRAUD | Transaction blocked by fraud detection (provider-side or bank-side) | no |
            | BAD_DATA_PROVIDED | Invalid input data: wrong account number, invalid currency, malformed request fields | no (fix input first) |
            | CANCELLED_BY_CUSTOMER | Customer explicitly cancelled, abandoned, or refused the transaction | no |
            | PROVIDER_LIMIT | Provider-side rate limit, daily/monthly volume cap, or account quota reached | yes, after cooldown |
            | AUTHENTICATION_FAILURE | Invalid credentials, expired token, signature mismatch with the provider | no (fix config first) |
            """;

    private static final String RETRY_VOCAB = """
            # Retry Strategies

            Choose exactly one per code:

            - no_retry: terminal error, do not retry.
            - retry_with_backoff: transient error, retry with exponential backoff.
            - retry_after_fix: retryable only after the merchant or customer fixes something.
            - no_action: not a real error (e.g., idempotency guard).
            """;

    private static final String CONFIDENCE_RUBRIC = """
            # Operational Confidence Rubric

            confidence = "high"   when: exact match to one taxonomy category,
                                       no hedging language in the reasoning,
                                       no plausible second category.
            confidence = "medium" when: one primary category is clear,
                                       but a secondary category is plausible.
            confidence = "low"    when: two or more categories are plausible,
                                       OR the provider message is vague.
            """;

    private static final String FEW_SHOTS = """
            # Examples

            Example 1 (clear case):
              Input:  XX-001 "Card balance insufficient" — The customer's account does not have enough funds.
              Output:
                provider_code:     "XX-001"
                internal_category: "COMMON_DECLINE"
                confidence:        "high"
                reasoning:         "Insufficient funds is a textbook generic decline. No ambiguity."
                retry_strategy:    "no_retry"
                needs_human_review: false
                review_reason:     null

            Example 2 (ambiguous case):
              Input:  YY-042 "Transaction not allowed" — The provider gives no detail on why the transaction is not allowed.
              Output:
                provider_code:     "YY-042"
                internal_category: "COMMON_DECLINE"
                confidence:        "low"
                reasoning:         "The provider gives no detail; could be antifraud, card restriction, or MCC issue."
                retry_strategy:    "no_retry"
                needs_human_review: true
                review_reason:     "Ambiguous: could be ANTIFRAUD or BAD_DATA_PROVIDED depending on issuer behaviour."
            """;

    private static final String INSTRUCTION = """
            # Instruction

            Return your answer only via the provided `map_codes` tool. For each provider code
            in the user message, produce one mapping object. Choose `internal_category` strictly
            from the seven values above. If unsure between two or more categories, follow the
            confidence rubric and set `needs_human_review: true`. Do not invent categories,
            retry strategies, or confidence values.
            """;

    public String systemPrompt() {
        StringBuilder sb = new StringBuilder();
        sb.append(TAXONOMY).append('\n');
        sb.append(RETRY_VOCAB).append('\n');
        sb.append(CONFIDENCE_RUBRIC).append('\n');
        sb.append(FEW_SHOTS).append('\n');
        sb.append(INSTRUCTION);
        return sb.toString();
    }

    /** Returns true if every taxonomy enum value appears verbatim in the prompt. */
    public static boolean coversAllCategories(String prompt) {
        for (Category c : Category.values()) {
            if (!prompt.contains(c.name())) return false;
        }
        return true;
    }
}
