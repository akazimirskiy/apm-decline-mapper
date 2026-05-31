package com.kazimir.declinemapper.fakes;

import com.kazimir.declinemapper.llm.LlmClient;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.function.Function;

/**
 * Programmable LLM client for component tests. The test enqueues canned
 * responses; the SUT consumes them one by one. Extra calls throw — the test
 * over-asks, the test fails loudly.
 *
 * <p>Two extension knobs:
 * <ul>
 *     <li>{@link #enqueueRepeating(LlmResponse)} — for the chaos test where every
 *         call returns the same canned response.</li>
 *     <li>{@link #setHandler(Function)} — for tests that need a per-request
 *         function (e.g. simulate transient 5xx then success).</li>
 * </ul>
 */
public final class ScriptedLlmClient implements LlmClient {

    private final Deque<LlmResponse> responses = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();
    private LlmResponse repeating;
    private Function<LlmRequest, LlmResponse> handler;
    private IOException pendingError;

    public ScriptedLlmClient enqueue(LlmResponse response) {
        responses.add(response);
        return this;
    }

    /** Subsequent calls all return the same response (chaos / never-changes tests). */
    public ScriptedLlmClient enqueueRepeating(LlmResponse response) {
        this.repeating = response;
        return this;
    }

    /** Subsequent calls go through this function (full control over response generation). */
    public ScriptedLlmClient setHandler(Function<LlmRequest, LlmResponse> handler) {
        this.handler = handler;
        return this;
    }

    /** Next call (once) throws this. After that, fall back to the queue. */
    public ScriptedLlmClient enqueueError(IOException error) {
        this.pendingError = error;
        return this;
    }

    @Override
    public LlmResponse call(LlmRequest request) throws IOException {
        received.add(request);
        if (pendingError != null) {
            IOException toThrow = pendingError;
            pendingError = null;
            throw toThrow;
        }
        if (handler != null) {
            return handler.apply(request);
        }
        if (!responses.isEmpty()) {
            return responses.poll();
        }
        if (repeating != null) {
            return repeating;
        }
        throw new AssertionError(
                "ScriptedLlmClient: no more responses scripted. Request was: codes="
                + request.providerCodesInBatch());
    }

    public List<LlmRequest> received() {
        return Collections.unmodifiableList(received);
    }

    public int callCount() {
        return received.size();
    }
}
