package com.kazimir.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Closed set of internal decline categories. Every provider error maps to
 * exactly one of these — the LLM is constrained to this set via tool_use schema,
 * and Jackson refuses any other value at deserialization (V1 defence).
 */
public enum Category {
    @JsonProperty("SYSTEM_MALFUNCTION")    SYSTEM_MALFUNCTION,
    @JsonProperty("COMMON_DECLINE")        COMMON_DECLINE,
    @JsonProperty("ANTIFRAUD")             ANTIFRAUD,
    @JsonProperty("BAD_DATA_PROVIDED")     BAD_DATA_PROVIDED,
    @JsonProperty("CANCELLED_BY_CUSTOMER") CANCELLED_BY_CUSTOMER,
    @JsonProperty("PROVIDER_LIMIT")        PROVIDER_LIMIT,
    @JsonProperty("AUTHENTICATION_FAILURE") AUTHENTICATION_FAILURE
}
