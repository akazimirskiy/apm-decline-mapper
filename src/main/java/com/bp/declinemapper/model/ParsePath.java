package com.bp.declinemapper.model;

/**
 * Records which path the parser took to extract a given {@link ProviderError}.
 * Logged per code so a failed run can be post-mortem'd without re-running.
 */
public enum ParsePath {
    /** Deterministic state machine — no LLM call. */
    STATE_MACHINE,
    /** Chunk was flagged ambiguous and resolved via LLM fallback. */
    LLM_FALLBACK
}
