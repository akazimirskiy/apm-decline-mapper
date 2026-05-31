package com.kazimir.declinemapper;

import com.kazimir.declinemapper.budget.BudgetGuard;
import com.kazimir.declinemapper.budget.BudgetGuard.BudgetExhaustedException;
import com.kazimir.declinemapper.llm.LlmClient;
import com.kazimir.declinemapper.llm.LlmResponse;
import com.kazimir.declinemapper.model.Confidence;
import com.kazimir.declinemapper.model.Mapping;
import com.kazimir.declinemapper.model.MappingResult;
import com.kazimir.declinemapper.model.ParseOutcome;
import com.kazimir.declinemapper.model.ProviderError;
import com.kazimir.declinemapper.model.RetryStrategy;
import com.kazimir.declinemapper.model.SanitizationResult;
import com.kazimir.declinemapper.model.Summary;
import com.kazimir.declinemapper.stage.Enricher;
import com.kazimir.declinemapper.stage.LlmMapper;
import com.kazimir.declinemapper.stage.Parser;
import com.kazimir.declinemapper.stage.ResponseSanitizer;
import com.kazimir.declinemapper.stage.Validator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

/**
 * Stage orchestrator. Wires Bootstrap → Parser → Enricher → LlmMapper →
 * ResponseSanitizer → Validator, all under a {@link BudgetGuard}.
 *
 * <p>Guarantees:
 * <ul>
 *     <li>{@code result.json} is written on every exit path — clean success,
 *         partial completion, budget exhaustion, transport error, or fatal
 *         sanitizer error. The try/finally around the main loop and V5 makes
 *         this load-bearing.</li>
 *     <li>Sanitizer {@code NeedsReprompt} routes only the missing codes back to
 *         the Mapper (not the whole batch).</li>
 *     <li>Per-code retry cap = 2. Beyond that, the code becomes {@code unmapped}
 *         with {@code review_reason="recovery_exhausted"}.</li>
 *     <li>V5 single-code re-validation runs once after the main pass for every
 *         non-V4-pinned LOW code, respecting the same retry cap. V5 sanitizer
 *         warnings are drained to stderr with their own prefix.</li>
 *     <li>Cache hits do NOT consume the run-level budget.</li>
 * </ul>
 *
 * <p>Parser and ResponseSanitizer are constructed <strong>fresh per run()</strong>
 * — not held as instance fields — to avoid sharing their mutable {@code warnings}
 * state across runs. This is the simple form of the test-review safety fix.
 */
public final class Pipeline {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    public static final int MAX_RETRIES_PER_CODE = 2;

    public record RunResult(MappingResult result, int exitCode) {
    }

    private final Bootstrap.Result bootstrap;
    private final LlmClient llm;
    private final Path cacheDir;
    private final int batchSize;

    private final Enricher enricher = new Enricher();  // stateless, OK as field

    public Pipeline(Bootstrap.Result bootstrap, LlmClient llm, Path cacheDir) {
        this(bootstrap, llm, cacheDir, LlmMapper.DEFAULT_BATCH_SIZE);
    }

    /** Test-friendly constructor with explicit batch size. */
    public Pipeline(Bootstrap.Result bootstrap, LlmClient llm, Path cacheDir, int batchSize) {
        this.bootstrap = bootstrap;
        this.llm = llm;
        this.cacheDir = cacheDir;
        this.batchSize = batchSize;
    }

    public RunResult run(Path inputFile, String provider, String version, Path outFile)
            throws IOException {
        // Fresh stateful collaborators per run.
        Parser parser = new Parser();
        ResponseSanitizer sanitizer = new ResponseSanitizer();

        BudgetGuard guard = new BudgetGuard(
                bootstrap.config().maxLlmCalls(), bootstrap.config().maxTokensPerRun());
        LlmMapper mapper = new LlmMapper(
                llm, cacheDir, bootstrap.config().llmModel(),
                batchSize, enricher,
                bootstrap.config().cacheEnabled(), guard);
        Validator validator = new Validator(bootstrap.patterns());

        // Independent of guard.isExhausted(): tells us a call was actually blocked.
        // (Trivial guard.isExhausted() can be true when limits are 0 but cache absorbed everything.)
        boolean[] budgetHit = {false};

        // Stage 1: parse + bucket
        List<ParseOutcome> outcomes = parser.parse(inputFile);
        for (String w : parser.getWarnings()) {
            System.err.println("PARSER_WARN: " + w);
        }

        List<ProviderError> okCodes = new ArrayList<>();
        List<Mapping> bypassMappings = new ArrayList<>();
        for (ParseOutcome o : outcomes) {
            switch (o) {
                case ParseOutcome.Ok ok -> okCodes.add(ok.error());
                case ParseOutcome.Garbage g -> bypassMappings.add(garbageToMapping(g));
            }
        }

        Map<String, ProviderError> okCodesByCode = new LinkedHashMap<>();
        for (ProviderError e : okCodes) okCodesByCode.put(e.code(), e);

        // Working result set — populated incrementally so partial output is always available.
        Map<String, Mapping> mappingsByCode = new LinkedHashMap<>();
        Map<String, Integer> retryCount = new HashMap<>();
        Set<String> exhaustedCodes = new HashSet<>();

        // The try/finally enforces the "result.json is always written" contract.
        // Any exception during the main loop or V5 lands in finally, which writes whatever
        // we managed to accumulate.
        try {
            // Stages 3 + 4 main pass
            Queue<List<ProviderError>> queue = new ArrayDeque<>(mapper.partition(okCodes));
            while (!queue.isEmpty()) {
                List<ProviderError> batch = queue.poll();
                batch = batch.stream()
                        .filter(e -> !mappingsByCode.containsKey(e.code()))
                        .filter(e -> !exhaustedCodes.contains(e.code()))
                        .toList();
                if (batch.isEmpty()) continue;

                try {
                    processBatch(batch, mapper, sanitizer, mappingsByCode, retryCount,
                            exhaustedCodes, queue);
                } catch (BudgetExhaustedException be) {
                    System.err.println("BUDGET_EXHAUSTED: " + be.getMessage());
                    budgetHit[0] = true;
                    markBudgetExhausted(batch, mappingsByCode);
                    while (!queue.isEmpty()) {
                        markBudgetExhausted(queue.poll(), mappingsByCode);
                    }
                    break;
                }
            }

            // Stage 4 V5: single-code re-validation for LOW (non-V4-pinned) codes.
            // Validator's codesNeedingRevalidation returns a LinkedHashSet — deterministic order.
            List<Mapping> afterChecks = validator.applyChecks(new ArrayList<>(mappingsByCode.values()));
            mappingsByCode.clear();
            for (Mapping m : afterChecks) mappingsByCode.put(m.providerCode(), m);

            if (!guard.isExhausted()) {
                Set<String> lowCodes = validator.codesNeedingRevalidation(afterChecks);
                for (String code : lowCodes) {
                    if (guard.isExhausted()) break;
                    int n = retryCount.getOrDefault(code, 0);
                    if (n >= MAX_RETRIES_PER_CODE) continue;
                    ProviderError pe = okCodesByCode.get(code);
                    if (pe == null) continue;
                    retryCount.put(code, n + 1);

                    try {
                        LlmResponse resp = mapper.mapBatch(List.of(pe));
                        SanitizationResult sr = sanitizer.sanitize(resp, Map.of(code, pe));
                        // Drain V5 sanitizer warnings BEFORE the next call clears them.
                        for (String w : sanitizer.getWarnings()) {
                            System.err.println("REVALIDATION_SANITIZER_WARN: " + w);
                        }
                        if (sr instanceof SanitizationResult.Clean clean && !clean.mappings().isEmpty()) {
                            Mapping revalidated = clean.mappings().get(0);
                            Mapping merged = validator.mergeRevalidation(
                                    mappingsByCode.get(code), revalidated);
                            // Re-apply V4 just in case re-validation moved a field into ambiguous territory.
                            Mapping pinned = validator.applyAmbiguityPatterns(merged);
                            mappingsByCode.put(code, pinned);
                        }
                    } catch (BudgetExhaustedException be) {
                        budgetHit[0] = true;
                        break;
                    } catch (ResponseSanitizer.SanitizationException se) {
                        // Same config-bug surface as the main pass; refuse to crash V5 over it.
                        System.err.println("REVALIDATION_SANITIZER_FATAL: " + code + ": " + se.getMessage());
                    } catch (IOException ioe) {
                        System.err.println("REVALIDATION_WARN: " + code + ": " + ioe.getMessage());
                    }
                }
            }
        } finally {
            // Combine and emit, regardless of how we got here.
            List<Mapping> finalMappings = new ArrayList<>(mappingsByCode.values());
            finalMappings.addAll(bypassMappings);

            Summary summary = computeSummary(finalMappings, guard, budgetHit[0]);
            MappingResult result = new MappingResult(provider, version, finalMappings, summary);

            if (outFile.getParent() != null) {
                java.nio.file.Files.createDirectories(outFile.getParent());
            }
            JSON.writeValue(outFile.toFile(), result);

            // Signal in the return value if the caller is still around;
            // the explicit return below will use the same summary.
            // (Java has no "finally return", so the actual return is in the try block-exit path.)
            this.lastResult = result;
            this.lastExit = computeExitCode(summary);
        }

        return new RunResult(lastResult, lastExit);
    }

    // The finally block computes the result; these fields carry it out of the try.
    // Not great style, but the alternative (catching every exception explicitly) is uglier
    // for this single-shot CLI.
    private MappingResult lastResult;
    private int lastExit;

    // ---- helpers ----

    private void processBatch(List<ProviderError> batch,
                              LlmMapper mapper,
                              ResponseSanitizer sanitizer,
                              Map<String, Mapping> mappingsByCode,
                              Map<String, Integer> retryCount,
                              Set<String> exhaustedCodes,
                              Queue<List<ProviderError>> queue) {
        Map<String, ProviderError> batchByCode = new LinkedHashMap<>();
        for (ProviderError e : batch) batchByCode.put(e.code(), e);

        LlmResponse resp;
        try {
            resp = mapper.mapBatch(batch);
        } catch (IOException ioe) {
            // Transport-level error after AnthropicLlmClient's own retry loop.
            // Mark this batch's codes as recovery_exhausted with the transport reason
            // so the operator gets a clear breadcrumb in result.json.
            System.err.println("TRANSPORT_ERROR: " + ioe.getMessage());
            for (ProviderError e : batch) {
                exhaustedCodes.add(e.code());
                if (!mappingsByCode.containsKey(e.code())) {
                    mappingsByCode.put(e.code(), unmappedMapping(e.code(), e.message(),
                            "transport_error: " + ioe.getMessage()));
                }
            }
            return;
        }

        SanitizationResult sr;
        try {
            sr = sanitizer.sanitize(resp, batchByCode);
        } catch (ResponseSanitizer.SanitizationException se) {
            // Fatal config bug (wrong tool name). Don't crash the whole run — mark this
            // batch as unmapped and let finally write result.json. The operator sees the
            // problem in the unmapped review_reason.
            System.err.println("FATAL_SANITIZER_ERROR: " + se.getMessage());
            for (ProviderError e : batch) {
                exhaustedCodes.add(e.code());
                if (!mappingsByCode.containsKey(e.code())) {
                    mappingsByCode.put(e.code(), unmappedMapping(e.code(), e.message(),
                            "sanitizer_fatal: " + se.getMessage()));
                }
            }
            return;
        }
        for (String w : sanitizer.getWarnings()) {
            System.err.println("SANITIZER_WARN: " + w);
        }

        if (sr instanceof SanitizationResult.Clean clean) {
            for (Mapping m : clean.mappings()) {
                mappingsByCode.put(m.providerCode(), m);
            }
        } else if (sr instanceof SanitizationResult.NeedsReprompt rp) {
            // Re-queue only the codes we still need, bounded by retry cap.
            List<ProviderError> retryBatch = new ArrayList<>();
            for (String code : rp.codesToRetry()) {
                int n = retryCount.getOrDefault(code, 0);
                if (n >= MAX_RETRIES_PER_CODE) {
                    exhaustedCodes.add(code);
                    ProviderError pe = batchByCode.get(code);
                    if (pe != null) {
                        mappingsByCode.put(code, unmappedMapping(code, pe.message(),
                                "recovery_exhausted"));
                    }
                } else {
                    retryCount.put(code, n + 1);
                    ProviderError pe = batchByCode.get(code);
                    if (pe != null) retryBatch.add(pe);
                }
            }
            if (!retryBatch.isEmpty()) queue.offer(retryBatch);
        }
    }

    private void markBudgetExhausted(List<ProviderError> batch, Map<String, Mapping> mappingsByCode) {
        for (ProviderError e : batch) {
            if (!mappingsByCode.containsKey(e.code())) {
                mappingsByCode.put(e.code(),
                        unmappedMapping(e.code(), e.message(), "budget_exhausted"));
            }
        }
    }

    private Mapping garbageToMapping(ParseOutcome.Garbage g) {
        String reason = "parse_garbage: " + g.kind().name().toLowerCase();
        if (g.detail() != null && !g.detail().isEmpty()) {
            reason = reason + ": " + g.detail();
        }
        return unmappedMapping(g.code(), null, reason);
    }

    private Mapping unmappedMapping(String code, String message, String reviewReason) {
        return new Mapping(
                code,
                message,
                null,                          // unmapped → null category
                Confidence.LOW,
                "n/a — " + reviewReason,
                RetryStrategy.NO_RETRY,
                true,
                reviewReason);
    }

    private Summary computeSummary(List<Mapping> finalMappings, BudgetGuard guard, boolean budgetHit) {
        int total = finalMappings.size();
        int mapped = (int) finalMappings.stream().filter(m -> m.internalCategory() != null).count();
        int high = (int) finalMappings.stream()
                .filter(m -> m.confidence() == Confidence.HIGH && m.internalCategory() != null)
                .count();
        int review = (int) finalMappings.stream().filter(Mapping::needsHumanReview).count();
        int unmapped = total - mapped;
        return new Summary(total, mapped, high, review, unmapped,
                budgetHit, guard.callCount(), guard.tokensUsed());
    }

    private int computeExitCode(Summary s) {
        if (s.unmapped() > 0) return 2;
        if (s.needsReview() > 0) return 1;
        return 0;
    }
}
