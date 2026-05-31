package com.bp.declinemapper.llm;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Provider-agnostic LLM response. {@link LlmClient} implementations translate
 * the provider's wire format into this shape.
 *
 * <p>For Anthropic, {@code stopReason} maps from {@code stop_reason} ({@code end_turn},
 * {@code max_tokens}, {@code tool_use}, ...). The Sanitizer treats {@code max_tokens}
 * as a re-prompt trigger.
 *
 * @param id          provider message id (for debug correlation)
 * @param model       resolved model id the provider actually used
 * @param stopReason  why the LLM stopped generating
 * @param toolUses    zero or more tool_use blocks; the Sanitizer expects exactly one
 *                    with name == {@code map_codes}
 * @param tokensIn    input tokens billed
 * @param tokensOut   output tokens billed
 */
public record LlmResponse(
        @JsonProperty("id") String id,
        @JsonProperty("model") String model,
        @JsonProperty("stop_reason") String stopReason,
        @JsonProperty("tool_uses") List<ToolUse> toolUses,
        @JsonProperty("tokens_in") int tokensIn,
        @JsonProperty("tokens_out") int tokensOut
) {
    /**
     * One tool-use block. {@code inputJson} is the raw JSON the LLM produced as the tool input,
     * left as a string so the Sanitizer can apply its own validation (R1) before parse.
     */
    public record ToolUse(
            @JsonProperty("name") String name,
            @JsonProperty("input_json") String inputJson
    ) {
    }
}
