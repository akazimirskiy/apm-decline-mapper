package com.kazimir.declinemapper.fakes;

import javax.net.ssl.SSLSession;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Minimal {@link HttpResponse} implementation for {@link AnthropicLlmClient} retry tests.
 * Only the fields the production code actually reads are non-trivial: {@code statusCode()}
 * and {@code body()}. The rest return safe defaults.
 */
public final class StubHttpResponse implements HttpResponse<String> {

    public static StubHttpResponse of(int status, String body) {
        return new StubHttpResponse(status, body);
    }

    private final int status;
    private final String body;

    private StubHttpResponse(int status, String body) {
        this.status = status;
        this.body = body;
    }

    @Override public int statusCode() { return status; }
    @Override public String body() { return body; }

    @Override public HttpRequest request() { return null; }
    @Override public Optional<HttpResponse<String>> previousResponse() { return Optional.empty(); }
    @Override public HttpHeaders headers() {
        return HttpHeaders.of(Collections.<String, List<String>>emptyMap(), (a, b) -> true);
    }
    @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
    @Override public URI uri() { return URI.create("https://example.invalid/"); }
    @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
}
