package com.bp.declinemapper.model;

/**
 * A successfully-parsed provider error code, ready to be sent to the Mapper.
 *
 * @param code        e.g. {@code "QP-001"}
 * @param message     the quoted message, e.g. {@code "Transaction declined"}
 * @param description multi-line description text, normalized (single LF, trimmed)
 * @param section     section header the code lived under, or empty if none
 * @param parsePath   {@link ParsePath#STATE_MACHINE} or {@link ParsePath#LLM_FALLBACK}
 */
public record ProviderError(
        String code,
        String message,
        String description,
        String section,
        ParsePath parsePath
) {
}
