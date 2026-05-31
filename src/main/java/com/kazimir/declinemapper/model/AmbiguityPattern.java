package com.kazimir.declinemapper.model;

import java.util.regex.Pattern;

/**
 * One row from {@code ambiguity_patterns.yaml}. Compiled at Bootstrap; if the regex
 * is invalid or the pattern is dangerously broad, Bootstrap fails before the
 * pipeline starts.
 *
 * @param match  case-insensitive regex tested against {@code provider_message}
 * @param reason human-readable explanation used as the {@code review_reason} suffix
 */
public record AmbiguityPattern(Pattern match, String reason) {
}
