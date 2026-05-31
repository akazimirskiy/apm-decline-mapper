package com.kazimir.declinemapper.fakes;

import com.kazimir.declinemapper.llm.LlmClient;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * E2E test seam. Reads pre-recorded LLM responses from disk keyed by
 * {@code (providerKey, batchIndex)}. Hand-authorable from day one — no API key
 * needed to bootstrap test fixtures.
 *
 * <p>If the fixture file is missing, the test fails with a clear "file not
 * found" message — that's the entire point of this client compared to a silent
 * default.
 */
public final class FixtureLlmClient implements LlmClient {

    private final Path fixturesDir;
    private final String providerKey;
    private final ObjectMapper json = new ObjectMapper();
    private int batchCounter = 0;

    public FixtureLlmClient(Path fixturesDir, String providerKey) {
        this.fixturesDir = fixturesDir;
        this.providerKey = providerKey;
    }

    @Override
    public LlmResponse call(LlmRequest request) throws IOException {
        String filename = providerKey + "_batch" + batchCounter + ".json";
        Path file = fixturesDir.resolve(filename);
        batchCounter++;

        if (!Files.exists(file)) {
            throw new AssertionError(
                    "FixtureLlmClient: missing recording " + file + ". "
                  + "Either author it by hand or run with RecordedLlmClient + RECORD_MODE=true.");
        }
        return json.readValue(file.toFile(), LlmResponse.class);
    }

    /** Reset the batch counter — useful when a test reuses the client across runs. */
    public void resetCounter() {
        batchCounter = 0;
    }

    public int batchCounter() {
        return batchCounter;
    }
}
