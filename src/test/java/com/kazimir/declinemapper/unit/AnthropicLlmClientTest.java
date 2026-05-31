package com.kazimir.declinemapper.unit;

import com.kazimir.declinemapper.fakes.StubHttpResponse;
import com.kazimir.declinemapper.llm.AnthropicLlmClient;
import com.kazimir.declinemapper.llm.AnthropicLlmClient.HttpSender;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link AnthropicLlmClient}'s HTTP retry policy.
 *
 * <p>The class declares: "retries on transient failures (HTTP 5xx, 429, IO errors)
 * with exponential backoff, up to {@code MAX_ATTEMPTS} attempts. 4xx (non-429)
 * errors are surfaced as fatal — no point retrying." These tests lock that down.
 */
class AnthropicLlmClientTest {

    private static final String TOOL_USE_OK_BODY = """
            {
              "id": "msg_test",
              "model": "claude-sonnet-4-5",
              "stop_reason": "end_turn",
              "content": [
                {
                  "type": "tool_use",
                  "name": "map_codes",
                  "input": {"mappings": []}
                }
              ],
              "usage": { "input_tokens": 50, "output_tokens": 25 }
            }
            """;

    private static LlmRequest dummyRequest() {
        return new LlmRequest(
                "claude-sonnet-4-5",
                "system",
                "user",
                "map_codes",
                "{\"type\":\"object\"}",
                List.of("X-001"),
                100,
                0.0);
    }

    /** A scripted sender for the retry tests — fail-then-succeed sequences. */
    private static final class ScriptedSender implements HttpSender {
        private final Deque<HttpResponse<String>> responses = new ArrayDeque<>();
        private final AtomicInteger callCount = new AtomicInteger();

        ScriptedSender enqueue(int status, String body) {
            responses.add(StubHttpResponse.of(status, body));
            return this;
        }

        @Override
        public HttpResponse<String> send(java.net.http.HttpRequest request) {
            callCount.incrementAndGet();
            if (responses.isEmpty()) {
                throw new AssertionError("ScriptedSender: no more responses");
            }
            return responses.poll();
        }

        int callCount() { return callCount.get(); }
    }

    private static AnthropicLlmClient client(HttpSender sender) {
        return new AnthropicLlmClient("sk-test",
                "https://example.invalid/v1/messages", null, sender);
    }

    // ---- retry on transient errors ----

    @Test
    void client_retriesOnTransient502_andSucceedsOnSecondAttempt() throws IOException {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(502, "{\"error\":\"upstream\"}")
                .enqueue(200, TOOL_USE_OK_BODY);

        LlmResponse resp = client(sender).call(dummyRequest());

        assertThat(sender.callCount()).isEqualTo(2);
        assertThat(resp.stopReason()).isEqualTo("end_turn");
        assertThat(resp.toolUses()).hasSize(1);
        assertThat(resp.toolUses().get(0).name()).isEqualTo("map_codes");
    }

    @Test
    void client_retriesOn429_withBackoff() throws IOException {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(429, "{\"error\":\"rate limited\"}")
                .enqueue(200, TOOL_USE_OK_BODY);

        LlmResponse resp = client(sender).call(dummyRequest());

        assertThat(sender.callCount()).isEqualTo(2);
        assertThat(resp.id()).isEqualTo("msg_test");
    }

    @Test
    void client_givesUp_afterMaxAttemptsOnTransient() {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(503, "down")
                .enqueue(503, "still down")
                .enqueue(503, "still down");

        assertThatThrownBy(() -> client(sender).call(dummyRequest()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("transient error 503");

        assertThat(sender.callCount()).isEqualTo(AnthropicLlmClient.MAX_ATTEMPTS);
    }

    // ---- fatal on non-retryable 4xx ----

    @Test
    void client_failsImmediatelyOn400_withNoRetry() {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(400, "{\"error\":\"bad request\"}");

        assertThatThrownBy(() -> client(sender).call(dummyRequest()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fatal error 400");

        assertThat(sender.callCount()).as("4xx must not retry").isEqualTo(1);
    }

    @Test
    void client_failsImmediatelyOn401_withNoRetry() {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(401, "{\"error\":\"invalid api key\"}");

        assertThatThrownBy(() -> client(sender).call(dummyRequest()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fatal error 401");

        assertThat(sender.callCount()).isEqualTo(1);
    }

    // ---- transient-then-fatal sequence ----

    @Test
    void client_failsFatally_when429FollowedBy400() {
        ScriptedSender sender = new ScriptedSender()
                .enqueue(429, "rate limited")
                .enqueue(400, "now your retry is malformed");

        // After retry, fatal 400 surfaces immediately.
        assertThatThrownBy(() -> client(sender).call(dummyRequest()))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("fatal error 400");

        assertThat(sender.callCount()).isEqualTo(2);
    }

    // ---- happy path: first call succeeds ----

    @Test
    void client_returnsImmediately_on200() throws IOException {
        ScriptedSender sender = new ScriptedSender().enqueue(200, TOOL_USE_OK_BODY);

        LlmResponse resp = client(sender).call(dummyRequest());

        assertThat(sender.callCount()).isEqualTo(1);
        assertThat(resp.tokensIn()).isEqualTo(50);
        assertThat(resp.tokensOut()).isEqualTo(25);
    }

    // ---- IO exceptions during send ----

    @Test
    void client_retriesOnIoException_thenSucceeds() throws IOException {
        AtomicInteger calls = new AtomicInteger();
        HttpSender sender = req -> {
            int n = calls.incrementAndGet();
            if (n == 1) throw new java.io.UncheckedIOException(new IOException("connection reset"));
            return StubHttpResponse.of(200, TOOL_USE_OK_BODY);
        };

        LlmResponse resp = client(sender).call(dummyRequest());
        assertThat(calls.get()).isEqualTo(2);
        assertThat(resp.stopReason()).isEqualTo("end_turn");
    }
}
