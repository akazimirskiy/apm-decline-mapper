package com.kazimir.declinemapper.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Production HTTP client for the Anthropic Messages API (no SDK dependency —
 * just stdlib {@code java.net.http.HttpClient} plus Jackson).
 *
 * <p>Retries on transient failures (HTTP 5xx, 429, IO errors) with exponential backoff,
 * up to 3 attempts. 4xx (non-429) errors are surfaced as fatal — no point retrying.
 *
 * <p>Every call is appended to the per-run JSONL log so failed runs are post-mortem'able
 * without re-execution.
 */
public final class AnthropicLlmClient implements LlmClient {

    public static final String DEFAULT_BASE_URL = "https://api.anthropic.com/v1/messages";
    public static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_ATTEMPTS = 3;

    private final HttpClient http;
    private final String apiKey;
    private final String baseUrl;
    private final Path logFile;
    private final ObjectMapper json = new ObjectMapper();

    public AnthropicLlmClient(String apiKey, Path logFile) {
        this(apiKey, DEFAULT_BASE_URL, logFile,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build());
    }

    public AnthropicLlmClient(String apiKey, String baseUrl, Path logFile, HttpClient http) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.logFile = logFile;
        this.http = http;
    }

    @Override
    public LlmResponse call(LlmRequest request) throws IOException {
        String payload = buildRequestJson(request);

        long backoffMs = 250;
        IOException lastFailure = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest httpReq = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl))
                        .timeout(Duration.ofSeconds(60))
                        .header("x-api-key", apiKey)
                        .header("anthropic-version", ANTHROPIC_VERSION)
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                        .build();

                HttpResponse<String> httpResp = http.send(httpReq, HttpResponse.BodyHandlers.ofString());
                int status = httpResp.statusCode();
                String body = httpResp.body();
                logJsonl(request, status, body, attempt);

                if (status >= 200 && status < 300) {
                    return parseResponse(body);
                }
                if (status == 429 || status >= 500) {
                    lastFailure = new IOException("Anthropic transient error " + status + ": " + body);
                    sleepQuietly(backoffMs);
                    backoffMs *= 2;
                    continue;
                }
                throw new IOException("Anthropic fatal error " + status + ": " + body);
            } catch (java.net.http.HttpTimeoutException | java.io.UncheckedIOException te) {
                lastFailure = new IOException("Anthropic transport error: " + te.getMessage(), te);
                sleepQuietly(backoffMs);
                backoffMs *= 2;
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while calling Anthropic", ie);
            }
        }
        throw lastFailure == null ? new IOException("Anthropic call failed") : lastFailure;
    }

    private String buildRequestJson(LlmRequest r) {
        ObjectNode body = json.createObjectNode();
        body.put("model", r.model());
        body.put("max_tokens", r.maxTokens());
        body.put("temperature", r.temperature());
        body.put("system", r.systemPrompt());

        ArrayNode messages = body.putArray("messages");
        ObjectNode userMsg = messages.addObject();
        userMsg.put("role", "user");
        userMsg.put("content", r.userMessage());

        // tool_use definition
        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", r.toolName());
        tool.put("description", "Map provider error codes to internal decline categories");
        try {
            tool.set("input_schema", json.readTree(r.toolSchemaJson()));
        } catch (IOException e) {
            throw new IllegalArgumentException("tool schema JSON is malformed", e);
        }

        ObjectNode toolChoice = body.putObject("tool_choice");
        toolChoice.put("type", "tool");
        toolChoice.put("name", r.toolName());

        try {
            return json.writeValueAsString(body);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to serialize request", e);
        }
    }

    private LlmResponse parseResponse(String body) throws IOException {
        JsonNode root = json.readTree(body);
        String id = root.path("id").asText("");
        String model = root.path("model").asText("");
        String stopReason = root.path("stop_reason").asText("");
        int tokensIn = root.path("usage").path("input_tokens").asInt(0);
        int tokensOut = root.path("usage").path("output_tokens").asInt(0);

        List<LlmResponse.ToolUse> toolUses = new ArrayList<>();
        for (JsonNode block : root.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                String name = block.path("name").asText("");
                String inputJson = block.path("input").toString();
                toolUses.add(new LlmResponse.ToolUse(name, inputJson));
            }
        }
        return new LlmResponse(id, model, stopReason, toolUses, tokensIn, tokensOut);
    }

    private void logJsonl(LlmRequest req, int status, String responseBody, int attempt) {
        if (logFile == null) return;
        try {
            ObjectNode entry = json.createObjectNode();
            entry.put("ts", Instant.now().toString());
            entry.put("attempt", attempt);
            entry.put("model", req.model());
            entry.put("codes", String.join(",", req.providerCodesInBatch()));
            entry.put("status", status);
            entry.put("response_body", truncate(responseBody, 8000));
            Files.createDirectories(logFile.getParent());
            Files.write(logFile,
                    (json.writeValueAsString(entry) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // logging is best-effort; never fail the call due to a log write
            System.err.println("WARN: failed to write LLM log: " + e.getMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
