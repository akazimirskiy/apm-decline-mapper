package com.bp.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Confidence levels for a mapping. Operationally defined in the system prompt
 * (see {@link com.bp.declinemapper.stage.Enricher}): high = clear match,
 * medium = primary plus plausible secondary, low = ≥2 plausible or vague message.
 */
public enum Confidence {
    @JsonProperty("high")   HIGH,
    @JsonProperty("medium") MEDIUM,
    @JsonProperty("low")    LOW
}
