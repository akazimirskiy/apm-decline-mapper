package com.bp.declinemapper.model;

import java.util.List;
import java.util.Set;

/**
 * Outcome of {@link com.bp.declinemapper.stage.ResponseSanitizer#sanitize}.
 *
 * <ul>
 *     <li>{@link Clean} — every expected code has a syntactically-valid {@link Mapping}.</li>
 *     <li>{@link NeedsReprompt} — at least one code is missing, malformed, or the response
 *         was truncated. The caller should re-prompt only the codes in {@code codesToRetry}.</li>
 * </ul>
 *
 * <p>Wrong tool name and other unrecoverable shape errors are thrown as
 * {@code SanitizationException} (fatal config bugs), not returned via this sealed type.
 */
public sealed interface SanitizationResult
        permits SanitizationResult.Clean, SanitizationResult.NeedsReprompt {

    record Clean(List<Mapping> mappings) implements SanitizationResult {
    }

    record NeedsReprompt(String reason, Set<String> codesToRetry) implements SanitizationResult {
    }
}
