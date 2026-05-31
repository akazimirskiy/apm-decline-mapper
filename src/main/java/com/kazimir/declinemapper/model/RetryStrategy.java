package com.kazimir.declinemapper.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Closed set of retry strategies — same enum-as-defence pattern as {@link Category}. */
public enum RetryStrategy {
    @JsonProperty("no_retry")            NO_RETRY,
    @JsonProperty("retry_with_backoff")  RETRY_WITH_BACKOFF,
    @JsonProperty("retry_after_fix")     RETRY_AFTER_FIX,
    @JsonProperty("no_action")           NO_ACTION
}
