package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.Mapping;
import com.kazimir.declinemapper.model.ProviderError;
import com.kazimir.declinemapper.model.SanitizationResult;
import com.kazimir.declinemapper.stage.ResponseSanitizer;
import com.kazimir.declinemapper.stage.ResponseSanitizer.SanitizationException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResponseSanitizerTest {

    private final ResponseSanitizer sanitizer = new ResponseSanitizer();

    // ---- helpers ----

    private static ProviderError pe(String code, String msg) {
        return new ProviderError(code, msg, "desc-" + code, "section");
    }

    private static Map<String, ProviderError> batch(String... codes) {
        Map<String, ProviderError> m = new LinkedHashMap<>();
        for (String c : codes) m.put(c, pe(c, "msg-" + c));
        return m;
    }

    private static LlmResponse withToolUse(String name, String inputJson) {
        return withToolUse(name, inputJson, "end_turn");
    }

    private static LlmResponse withToolUse(String name, String inputJson, String stopReason) {
        return new LlmResponse("msg_test", "claude-sonnet-4-5", stopReason,
                List.of(new LlmResponse.ToolUse(name, inputJson)), 100, 50);
    }

    private static String mapping(String code, String cat, String conf, String retry) {
        return "{\"provider_code\":\"" + code + "\","
                + "\"internal_category\":\"" + cat + "\","
                + "\"confidence\":\"" + conf + "\","
                + "\"reasoning\":\"because " + code + "\","
                + "\"retry_strategy\":\"" + retry + "\","
                + "\"needs_human_review\":false,"
                + "\"review_reason\":null}";
    }

    // ---- Test #38: invalid JSON in tool_use input → NeedsReprompt ----

    @Test
    void sanitizer_repromptsBatch_whenToolInputJsonIsInvalid() {
        LlmResponse r = withToolUse("map_codes", "{not valid json");
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001", "QP-002"));

        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
        SanitizationResult.NeedsReprompt rp = (SanitizationResult.NeedsReprompt) result;
        assertThat(rp.reason()).contains("not valid JSON");
        assertThat(rp.codesToRetry()).containsExactlyInAnyOrder("QP-001", "QP-002");
    }

    // ---- Test #39: no tool_use, text reply only → NeedsReprompt ----

    @Test
    void sanitizer_repromptsBatch_whenNoToolUseBlockPresent() {
        LlmResponse r = new LlmResponse("msg_x", "model", "end_turn",
                List.of(), 50, 20);
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
        assertThat(((SanitizationResult.NeedsReprompt) result).reason())
                .contains("no tool_use block");
    }

    // ---- Test #40: empty mappings array → NeedsReprompt ----

    @Test
    void sanitizer_repromptsBatch_whenMappingsArrayIsEmpty() {
        LlmResponse r = withToolUse("map_codes", "{\"mappings\":[]}");
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
        assertThat(((SanitizationResult.NeedsReprompt) result).reason())
                .contains("no non-empty 'mappings' array");
    }

    @Test
    void sanitizer_repromptsBatch_whenMappingsKeyMissing() {
        LlmResponse r = withToolUse("map_codes", "{\"other\":\"thing\"}");
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
    }

    // ---- Test #41: unknown code in response → strip with warning ----

    @Test
    void sanitizer_stripsUnknownCode_andProducesWarning() {
        String json = "{\"mappings\":["
                + mapping("QP-001", "COMMON_DECLINE", "high", "no_retry")
                + ","
                + mapping("QP-999", "ANTIFRAUD", "high", "no_retry")  // unknown
                + "]}";
        LlmResponse r = withToolUse("map_codes", json);
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.Clean.class);
        List<Mapping> ms = ((SanitizationResult.Clean) result).mappings();
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).providerCode()).isEqualTo("QP-001");
        assertThat(sanitizer.getWarnings()).anyMatch(w -> w.contains("QP-999") && w.contains("stripped"));
    }

    // ---- Test #42: duplicate code in response → keep first + warning ----

    @Test
    void sanitizer_keepsFirstOnly_whenSameCodeAppearsTwice() {
        String json = "{\"mappings\":["
                + mapping("QP-001", "COMMON_DECLINE", "high", "no_retry")
                + ","
                + mapping("QP-001", "ANTIFRAUD", "low", "no_retry")  // different category!
                + "]}";
        LlmResponse r = withToolUse("map_codes", json);
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.Clean.class);
        List<Mapping> ms = ((SanitizationResult.Clean) result).mappings();
        assertThat(ms).hasSize(1);
        assertThat(ms.get(0).internalCategory().name()).isEqualTo("COMMON_DECLINE");  // first wins
        assertThat(sanitizer.getWarnings()).anyMatch(w -> w.contains("duplicate code 'QP-001'"));
    }

    // ---- Test #43: confidence with trailing whitespace / odd casing → normalized ----

    @Test
    void sanitizer_normalizesConfidenceAndRetryStrategy_trimAndLowercase() {
        String json = "{\"mappings\":[{"
                + "\"provider_code\":\"QP-001\","
                + "\"internal_category\":\"common_decline\","       // lowercase
                + "\"confidence\":\"HIGH \","                        // trailing space
                + "\"reasoning\":\"text\","
                + "\"retry_strategy\":\"  No_Retry  \","             // padded mixed-case
                + "\"needs_human_review\":false,"
                + "\"review_reason\":null"
                + "}]}";
        LlmResponse r = withToolUse("map_codes", json);
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        assertThat(result).isInstanceOf(SanitizationResult.Clean.class);
        Mapping m = ((SanitizationResult.Clean) result).mappings().get(0);
        assertThat(m.confidence().name()).isEqualTo("HIGH");
        assertThat(m.retryStrategy().name()).isEqualTo("NO_RETRY");
        assertThat(m.internalCategory().name()).isEqualTo("COMMON_DECLINE");
    }

    // ---- Test #44: max_tokens with truncated/missing last mapping → drop + reprompt ----

    @Test
    void sanitizer_repromptsMissingCodes_whenStopReasonIsMaxTokens() {
        // 5 codes in batch, response has only 2 complete + 1 truncated (missing required fields).
        String truncated = "{\"provider_code\":\"QP-003\""
                + ",\"internal_category\":\"COMMON_DECLINE\""
                // missing confidence, reasoning, retry_strategy, needs_human_review
                + "}";
        String json = "{\"mappings\":["
                + mapping("QP-001", "COMMON_DECLINE", "high", "no_retry")
                + ","
                + mapping("QP-002", "ANTIFRAUD", "high", "no_retry")
                + ","
                + truncated
                + "]}";
        LlmResponse r = withToolUse("map_codes", json, "max_tokens");
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001", "QP-002", "QP-003", "QP-004", "QP-005"));

        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
        SanitizationResult.NeedsReprompt rp = (SanitizationResult.NeedsReprompt) result;
        assertThat(rp.reason()).contains("max_tokens");
        // The two complete mappings are dropped because we re-prompt the full set?
        // Actually: only the missing codes (QP-003, QP-004, QP-005) need re-prompt.
        assertThat(rp.codesToRetry()).containsExactlyInAnyOrder("QP-003", "QP-004", "QP-005");
    }

    // ---- Test #45: wrong tool name → fatal ----

    @Test
    void sanitizer_throwsFatal_whenToolNameIsWrong() {
        LlmResponse r = withToolUse("map_decline_codes", "{\"mappings\":[]}");
        assertThatThrownBy(() -> sanitizer.sanitize(r, batch("QP-001")))
                .isInstanceOf(SanitizationException.class)
                .hasMessageContaining("map_decline_codes")
                .hasMessageContaining("config bug");
    }

    // ---- Happy path: clean response ----

    @Test
    void sanitizer_returnsClean_whenResponseIsWellFormed_andFillsProviderMessage() {
        String json = "{\"mappings\":["
                + mapping("QP-001", "COMMON_DECLINE", "high", "no_retry")
                + ","
                + mapping("QP-002", "ANTIFRAUD", "high", "no_retry")
                + "]}";
        Map<String, ProviderError> b = batch("QP-001", "QP-002");
        LlmResponse r = withToolUse("map_codes", json);
        SanitizationResult result = sanitizer.sanitize(r, b);

        assertThat(result).isInstanceOf(SanitizationResult.Clean.class);
        List<Mapping> ms = ((SanitizationResult.Clean) result).mappings();
        assertThat(ms).hasSize(2);
        // provider_message is filled from the input batch
        assertThat(ms.get(0).providerMessage()).isEqualTo("msg-QP-001");
        assertThat(ms.get(1).providerMessage()).isEqualTo("msg-QP-002");
    }

    // ---- Mapping with hallucinated category (Jackson rejects at deserialization) ----

    @Test
    void sanitizer_dropsMapping_whenCategoryIsHallucinated() {
        String json = "{\"mappings\":["
                + mapping("QP-001", "FRAUD", "high", "no_retry")  // not a real category
                + "]}";
        LlmResponse r = withToolUse("map_codes", json);
        SanitizationResult result = sanitizer.sanitize(r, batch("QP-001"));

        // The malformed mapping is dropped; QP-001 is "missing" from the perspective of coverage,
        // so we get a NeedsReprompt for it.
        assertThat(result).isInstanceOf(SanitizationResult.NeedsReprompt.class);
        assertThat(((SanitizationResult.NeedsReprompt) result).codesToRetry()).containsExactly("QP-001");
        assertThat(sanitizer.getWarnings())
                .anyMatch(w -> w.contains("QP-001") && w.contains("failed to parse"));
    }
}
