package com.bp.declinemapper.llm;

import java.util.List;

/**
 * Provider-agnostic LLM request. Built by {@link com.bp.declinemapper.stage.LlmMapper};
 * translated to provider-specific JSON inside the {@link LlmClient} implementation.
 *
 * @param model       e.g. {@code claude-sonnet-4-5}
 * @param systemPrompt the deterministic prompt built by the Enricher
 * @param userMessage  the per-batch user message listing the codes to map
 * @param toolName     name of the tool the LLM must call (e.g. {@code map_codes})
 * @param toolSchemaJson JSON schema (as a string) for the tool's input
 * @param providerCodesInBatch  for cache-key / sanitizer use; the codes in this batch
 * @param maxTokens    upper bound on response tokens
 * @param temperature  sampling temperature; we use 0.0 for reproducibility
 */
public record LlmRequest(
        String model,
        String systemPrompt,
        String userMessage,
        String toolName,
        String toolSchemaJson,
        List<String> providerCodesInBatch,
        int maxTokens,
        double temperature
) {
}
