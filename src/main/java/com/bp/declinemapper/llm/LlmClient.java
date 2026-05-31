package com.bp.declinemapper.llm;

import java.io.IOException;

/**
 * Single mock seam for the entire suite. Three implementations:
 *
 * <ul>
 *     <li>{@code AnthropicLlmClient} — production HTTP.</li>
 *     <li>{@code ScriptedLlmClient} — fake with a programmable response queue;
 *         used by component tests.</li>
 *     <li>{@code FixtureLlmClient} — fake that reads responses from disk keyed by
 *         {@code (provider, batchIndex)}; used by integration tests.</li>
 * </ul>
 */
public interface LlmClient {

    /** Sends one request and returns the response. The caller handles caching above this. */
    LlmResponse call(LlmRequest request) throws IOException;
}
