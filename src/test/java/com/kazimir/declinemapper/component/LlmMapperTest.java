package com.kazimir.declinemapper.component;

import com.kazimir.declinemapper.fakes.ScriptedLlmClient;
import com.kazimir.declinemapper.llm.LlmRequest;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.ProviderError;
import com.kazimir.declinemapper.stage.Enricher;
import com.kazimir.declinemapper.stage.LlmMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmMapperTest {

    // ---- helpers ----

    private static ProviderError pe(String code) {
        return new ProviderError(code, "msg-" + code, "desc-" + code, "section");
    }

    private static List<ProviderError> codes(String... codes) {
        return Stream.of(codes).map(LlmMapperTest::pe).toList();
    }

    private static LlmResponse okResponse() {
        return new LlmResponse(
                "msg_test", "claude-sonnet-4-5", "end_turn",
                List.of(new LlmResponse.ToolUse("map_codes", "{\"mappings\":[]}")),
                100, 50);
    }

    private static LlmMapper mapperWith(ScriptedLlmClient client, Path cacheDir, boolean cacheEnabled) {
        return new LlmMapper(client, cacheDir, "claude-sonnet-4-5",
                LlmMapper.DEFAULT_BATCH_SIZE, new Enricher(), cacheEnabled);
    }

    // ---- Test #9: 5 codes → 1 LLM call (single batch) ----

    @Test
    void mapper_makesOneCall_whenBatchSizeExact(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, false);

        List<LlmResponse> out = mapper.mapAll(codes("A-1", "B-2", "C-3", "D-4", "E-5"));

        assertThat(out).hasSize(1);
        assertThat(client.callCount()).isEqualTo(1);
        assertThat(client.received().get(0).providerCodesInBatch()).hasSize(5);
    }

    // ---- Test #27: single-code batch ----

    @Test
    void mapper_handlesSingleCodeBatch_withoutCrashing(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, false);

        List<LlmResponse> out = mapper.mapAll(codes("LONE-001"));

        assertThat(out).hasSize(1);
        assertThat(client.received().get(0).providerCodesInBatch()).containsExactly("LONE-001");
    }

    // ---- Tests #50/#51/#52: batch boundary invariants ----

    @Test
    void mapper_makesOneCall_whenInputEqualsBatchSize(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, false);

        mapper.mapAll(codes("A", "B", "C", "D", "E"));

        assertThat(client.callCount()).isEqualTo(1);
        assertThat(client.received().get(0).providerCodesInBatch()).hasSize(5);
    }

    @Test
    void mapper_makesTwoCalls_whenInputIsBatchSizePlusOne(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .enqueue(okResponse())
                .enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, false);

        mapper.mapAll(codes("A", "B", "C", "D", "E", "F"));

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.received().get(0).providerCodesInBatch()).hasSize(5);
        assertThat(client.received().get(1).providerCodesInBatch()).hasSize(1);
    }

    @Test
    void mapper_makesTwoCalls_whenInputIsExactlyDoubleBatchSize_andUnionCoversAllCodes(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient()
                .enqueue(okResponse())
                .enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, false);

        List<String> inputCodes = List.of("A", "B", "C", "D", "E", "F", "G", "H", "I", "J");
        mapper.mapAll(inputCodes.stream().map(LlmMapperTest::pe).toList());

        assertThat(client.callCount()).isEqualTo(2);
        assertThat(client.received().get(0).providerCodesInBatch()).hasSize(5);
        assertThat(client.received().get(1).providerCodesInBatch()).hasSize(5);

        // Union of received covers every input exactly once.
        List<String> union = client.received().stream()
                .flatMap(r -> r.providerCodesInBatch().stream())
                .collect(Collectors.toList());
        assertThat(union).containsExactlyInAnyOrderElementsOf(inputCodes);
    }

    // ---- Test #10: within-batch shuffle deterministic with seed=42 ----

    @Test
    void mapper_producesIdenticalShuffle_whenSameInputCalledTwice(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client1 = new ScriptedLlmClient().enqueueRepeating(okResponse());
        ScriptedLlmClient client2 = new ScriptedLlmClient().enqueueRepeating(okResponse());

        LlmMapper m1 = mapperWith(client1, tmp.resolve("c1"), false);
        LlmMapper m2 = mapperWith(client2, tmp.resolve("c2"), false);

        List<ProviderError> batch = codes("A", "B", "C", "D", "E");
        m1.mapBatch(batch);
        m2.mapBatch(batch);

        // Same shuffle order in user message
        assertThat(client1.received().get(0).userMessage())
                .isEqualTo(client2.received().get(0).userMessage());
    }

    // ---- Test #11: cache hit → 0 LLM calls ----

    @Test
    void mapper_skipsLlm_whenCacheHits(@TempDir Path tmp) throws IOException {
        // First run populates the cache.
        ScriptedLlmClient firstClient = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper firstRun = mapperWith(firstClient, tmp, true);
        firstRun.mapAll(codes("A", "B", "C"));
        assertThat(firstClient.callCount()).isEqualTo(1);

        // Second run with cache enabled should not call the LLM at all.
        ScriptedLlmClient secondClient = new ScriptedLlmClient();  // empty queue!
        LlmMapper secondRun = mapperWith(secondClient, tmp, true);
        List<LlmResponse> out = secondRun.mapAll(codes("A", "B", "C"));

        assertThat(secondClient.callCount()).as("cache should bypass LLM entirely").isEqualTo(0);
        assertThat(out).hasSize(1);
    }

    // ---- Test #12: cache miss → cache file written at configured path ----

    @Test
    void mapper_writesCacheFile_onMiss(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient client = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper mapper = mapperWith(client, tmp, true);

        mapper.mapAll(codes("X-1", "X-2"));

        // exactly one cache file should exist directly under tmp
        long cacheFiles = Files.list(tmp).filter(p -> p.toString().endsWith(".json")).count();
        assertThat(cacheFiles).isEqualTo(1);
    }

    // ---- Test #53: per-instance Random — two instances produce identical shuffle ----

    @Test
    void random42_isPerInstance_andTwoInstancesShuffleIdentically(@TempDir Path tmp) throws IOException {
        ScriptedLlmClient c1 = new ScriptedLlmClient().enqueueRepeating(okResponse());
        ScriptedLlmClient c2 = new ScriptedLlmClient().enqueueRepeating(okResponse());

        LlmMapper i1 = mapperWith(c1, tmp.resolve("a"), false);
        LlmMapper i2 = mapperWith(c2, tmp.resolve("b"), false);

        List<ProviderError> batch = codes("Q-1", "Q-2", "Q-3", "Q-4", "Q-5");

        // Run each twice to confirm intra-instance reproducibility too.
        i1.mapBatch(batch);
        i1.mapBatch(batch);
        i2.mapBatch(batch);
        i2.mapBatch(batch);

        List<LlmRequest> requests = new ArrayList<>();
        requests.addAll(c1.received());
        requests.addAll(c2.received());

        // All four user messages must be identical — same shuffle from a fresh Random(42) per call.
        String first = requests.get(0).userMessage();
        for (LlmRequest r : requests) {
            assertThat(r.userMessage()).isEqualTo(first);
        }
    }

    // ---- Test #54: thread-safe — two parallel threads each reproducible ----

    @Test
    void random42_isThreadSafe_andTwoThreadsEachReproducible(@TempDir Path tmp) throws Exception {
        List<ProviderError> batch = codes("T-1", "T-2", "T-3", "T-4", "T-5");

        ScriptedLlmClient cA = new ScriptedLlmClient().enqueueRepeating(okResponse());
        ScriptedLlmClient cB = new ScriptedLlmClient().enqueueRepeating(okResponse());

        LlmMapper mA = mapperWith(cA, tmp.resolve("ta"), false);
        LlmMapper mB = mapperWith(cB, tmp.resolve("tb"), false);

        Thread tA = new Thread(() -> {
            try { mA.mapBatch(batch); mA.mapBatch(batch); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        Thread tB = new Thread(() -> {
            try { mB.mapBatch(batch); mB.mapBatch(batch); }
            catch (IOException e) { throw new RuntimeException(e); }
        });
        tA.start();
        tB.start();
        tA.join();
        tB.join();

        // Each thread's two calls must be identical (reproducible).
        assertThat(cA.received().get(0).userMessage()).isEqualTo(cA.received().get(1).userMessage());
        assertThat(cB.received().get(0).userMessage()).isEqualTo(cB.received().get(1).userMessage());
        // And both threads should agree (same input, same per-call Random seed).
        assertThat(cA.received().get(0).userMessage()).isEqualTo(cB.received().get(0).userMessage());
    }

    // ---- Test #55: cache-dir leakage guard ----

    @Test
    void cacheDir_doesNotLeak_outsideTempDir(@TempDir Path tmp) throws IOException {
        Path cacheDir = tmp.resolve("cache");
        Path workDir = tmp.resolve("workspace");
        Files.createDirectories(workDir);

        ScriptedLlmClient client = new ScriptedLlmClient().enqueue(okResponse());
        LlmMapper mapper = new LlmMapper(client, cacheDir, "claude-sonnet-4-5", 5,
                new Enricher(), true);
        mapper.mapAll(codes("A", "B"));

        // Workspace directory must be untouched.
        try (var s = Files.list(workDir)) {
            assertThat(s).isEmpty();
        }
        // Cache file landed in the configured cache dir.
        try (var s = Files.list(cacheDir)) {
            assertThat(s.count()).isEqualTo(1);
        }
    }

    // ---- Sanity: cacheDir is mandatory ----

    @Test
    void constructor_rejectsNullCacheDir() {
        assertThatThrownBy(() -> new LlmMapper(new ScriptedLlmClient(), null,
                "claude-sonnet-4-5", 5, new Enricher(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cacheDir is mandatory");
    }

    @Test
    void constructor_rejectsNonPositiveBatchSize(@TempDir Path tmp) {
        assertThatThrownBy(() -> new LlmMapper(new ScriptedLlmClient(), tmp,
                "claude-sonnet-4-5", 0, new Enricher(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchSize must be > 0");
    }

    // ---- Cache key contract ----

    @Test
    void cacheKey_isStableUnderInputReordering(@TempDir Path tmp) {
        LlmMapper m = mapperWith(new ScriptedLlmClient(), tmp, false);
        String keyA = m.computeCacheKey(codes("A", "B", "C"));
        String keyB = m.computeCacheKey(codes("C", "A", "B"));
        assertThat(keyA).isEqualTo(keyB);
    }

    @Test
    void cacheKey_changesWhenAnyCodeChanges(@TempDir Path tmp) {
        LlmMapper m = mapperWith(new ScriptedLlmClient(), tmp, false);
        String keyA = m.computeCacheKey(codes("A", "B", "C"));
        String keyB = m.computeCacheKey(codes("A", "B", "D"));
        assertThat(keyA).isNotEqualTo(keyB);
    }
}
