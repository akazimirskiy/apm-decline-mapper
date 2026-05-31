package com.kazimir.declinemapper.model;

/**
 * Validated runtime configuration, produced by {@link com.kazimir.declinemapper.Bootstrap}.
 * All env vars have already been parsed and bounded; downstream code can rely on
 * non-null, in-range values.
 */
public record Config(
        String anthropicApiKey,
        String llmModel,
        int maxLlmCalls,
        int maxTokensPerRun,
        boolean cacheEnabled
) {
    public static final String DEFAULT_MODEL = "claude-sonnet-4-5";
    public static final int DEFAULT_MAX_LLM_CALLS = 40;
    public static final int DEFAULT_MAX_TOKENS_PER_RUN = 200_000;
}
