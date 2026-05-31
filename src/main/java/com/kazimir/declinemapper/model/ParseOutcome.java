package com.kazimir.declinemapper.model;

/**
 * Outcome of parsing a single chunk of vendor documentation.
 *
 * <ul>
 *     <li>{@link Ok} — clean extraction, proceeds to Stage 2.</li>
 *     <li>{@link Garbage} — known-bad input that should never reach the Mapper.
 *         Written directly to the final result as an unmapped entry with
 *         {@code review_reason = "parse_garbage: <kind>: <detail>"}.</li>
 * </ul>
 *
 * <p>An {@code AmbiguousChunk} variant existed in earlier revisions for an
 * LLM-fallback path that was deferred; it was removed to avoid advertising a
 * knob that does nothing. When the fallback ships, restore the variant and
 * its switch arm in {@code Pipeline}.
 */
public sealed interface ParseOutcome
        permits ParseOutcome.Ok, ParseOutcome.Garbage {

    record Ok(ProviderError error) implements ParseOutcome {
    }

    record Garbage(String code, GarbageKind kind, String detail) implements ParseOutcome {
    }
}
