package com.kazimir.declinemapper.model;

/**
 * Kinds of input garbage the {@link com.kazimir.declinemapper.stage.Parser} can detect.
 * {@code Garbage} outcomes bypass the LLM entirely — they are written straight
 * into the final result as {@code unmapped} entries with the kind in {@code review_reason}.
 */
public enum GarbageKind {
    /** Code appears at the left margin without a quoted message. */
    NO_MESSAGE,

    /** Same code appears twice with conflicting descriptions. */
    DUPLICATE_CONFLICT,

    /** Description exceeds {@link com.kazimir.declinemapper.stage.Parser#MAX_DESCRIPTION_CHARS}. */
    DESCRIPTION_TOO_LONG,

    /** File is not valid UTF-8. */
    NON_UTF8,

    /** File is empty, whitespace-only, or contains no recognizable codes. */
    EMPTY_INPUT
}
