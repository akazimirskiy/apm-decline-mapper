package com.kazimir.declinemapper.model;

/**
 * Outcome of parsing a single chunk of vendor documentation. Three flavours:
 *
 * <ul>
 *     <li>{@link Ok} — clean extraction, proceeds to Stage 2.</li>
 *     <li>{@link AmbiguousChunk} — state machine couldn't extract cleanly; the chunk
 *         is shipped to the LLM fallback in Stage 1b. The LLM either upgrades it
 *         to an {@code Ok} or returns nothing useful (which becomes a no-op).</li>
 *     <li>{@link Garbage} — known-bad input that should never reach the Mapper.
 *         Written directly to the final result as an unmapped entry with
 *         {@code review_reason = "parse_garbage: <kind>: <detail>"}.</li>
 * </ul>
 */
public sealed interface ParseOutcome
        permits ParseOutcome.Ok, ParseOutcome.AmbiguousChunk, ParseOutcome.Garbage {

    record Ok(ProviderError error) implements ParseOutcome {
    }

    record AmbiguousChunk(String text) implements ParseOutcome {
    }

    record Garbage(String code, GarbageKind kind, String detail) implements ParseOutcome {
    }
}
