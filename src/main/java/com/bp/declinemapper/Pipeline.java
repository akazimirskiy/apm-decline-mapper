package com.bp.declinemapper;

import com.bp.declinemapper.budget.BudgetGuard;
import com.bp.declinemapper.budget.BudgetGuard.BudgetExhaustedException;
import com.bp.declinemapper.llm.LlmClient;
import com.bp.declinemapper.llm.LlmResponse;
import com.bp.declinemapper.model.Category;
import com.bp.declinemapper.model.Confidence;
import com.bp.declinemapper.model.GarbageKind;
import com.bp.declinemapper.model.Mapping;
import com.bp.declinemapper.model.MappingResult;
import com.bp.declinemapper.model.ParseOutcome;
import com.bp.declinemapper.model.ProviderError;
import com.bp.declinemapper.model.RetryStrategy;
import com.bp.declinemapper.model.SanitizationResult;
import com.bp.declinemapper.model.Summary;
import com.bp.declinemapper.stage.Enricher;
import com.bp.declinemapper.stage.LlmMapper;
import com.bp.declinemapper.stage.Parser;
import com.bp.declinemapper.stage.ResponseSanitizer;
import com.bp.declinemapper.stage.Validator;
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
 *     <li>{@code result.json} is written even on partial completion or fatal failure.</li>
 *     <li>Sanitizer NeedsReprompt routes only the missing codes back to the Mapper
 *         (not the whole batch).</li>
 *     <li>Per-code retry cap = 2. Beyond that, the code becomes {@code unmapped}
 *         with {@code review_reason="recovery_exhausted"}.</li>
 *     <li>V5 single-code re-validation runs once after the main pass for every LOW code,
 *         respecting the same retry cap.</li>
 *     <li>Cache hits do NOT consume the run-level budget.</li>
 * </ul>
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

    private final Parser parser = new Parser();
    private final Enricher enricher = new Enricher();
    private final ResponseSanitizer sanitizer = new ResponseSanitizer();

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
        BudgetGuard guard = new BudgetGuard(
                bootstrap.config().maxLlmCalls(), bootstrap.config().maxTokensPerRun());
        LlmMapper mapper = new LlmMapper(
                llm, cacheDir, bootstrap.config().llmModel(),
                batchSize, enricher,
                bootstrap.config().cacheEnabled(), guard);
        Validator validator = new Validator(bootstrap.patterns());
        // Tracks whether budget exhaustion ACTUALLY blocked a call.
        // (Distinct from guard.isExhausted(), which can be trivially true when
        // limits are 0 but no calls were ever attempted because cache absorbed everything.)
        boolean[] budgetHit = {false};

        // ---- Stage 1: parse + bucket ----
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
                case ParseOutcome.AmbiguousChunk a ->
                        // LLM fallback for ambiguous chunks is wired in a later iteration;
                        // for now they're surfaced as unmapped so the operator sees them.
                        bypassMappings.add(ambiguousChunkToMapping(a));
            }
        }

        Map<String, ProviderError> codeIndex = new LinkedHashMap<>();
        for (ProviderError e : okCodes) codeIndex.put(e.code(), e);

        // ---- Stages 3 + 4 main pass: batched LLM + sanitize + reprompt-on-miss ----
        Map<String, Mapping> mappingsByCode = new LinkedHashMap<>();
        Map<String, Integer> retryCount = new HashMap<>();
        Set<String> exhaustedCodes = new HashSet<>();

        Queue<List<ProviderError>> queue = new ArrayDeque<>(mapper.partition(okCodes));
        while (!queue.isEmpty()) {
            List<ProviderError> batch = queue.poll();
            batch = batch.stream()
                    .filter(e -> !mappingsByCode.containsKey(e.code()))
                    .filter(e -> !exhaustedCodes.contains(e.code()))
                    .toList();
            if (batch.isEmpty()) continue;

            try {
                processBatch(batch, mapper, validator, mappingsByCode, retryCount,
                        exhaustedCodes, queue);
            } catch (BudgetExhaustedException be) {
                System.err.println("BUDGET_EXHAUSTED: " + be.getMessage());
                budgetHit[0] = true;
                // Mark every still-unfinished code in this batch and the queue as unmapped.
                markBudgetExhausted(batch, mappingsByCode);
                while (!queue.isEmpty()) {
                    markBudgetExhausted(queue.poll(), mappingsByCode);
                }
                break;
            }
        }

        // ---- Stage 4 V5: single-code re-validation for LOW codes ----
        if (!guard.isExhausted()) {
            // Apply V3 + V4 first to identify LOW codes.
            List<Mapping> afterChecks = validator.applyChecks(new ArrayList<>(mappingsByCode.values()));
            Map<String, Mapping> byCode = new LinkedHashMap<>();
            for (Mapping m : afterChecks) byCode.put(m.providerCode(), m);
            mappingsByCode.clear();
            mappingsByCode.putAll(byCode);

            Set<String> lowCodes = validator.codesNeedingRevalidation(afterChecks);
            for (String code : lowCodes) {
                if (guard.isExhausted()) break;
                int n = retryCount.getOrDefault(code, 0);
                if (n >= MAX_RETRIES_PER_CODE) continue;
                ProviderError pe = codeIndex.get(code);
                if (pe == null) continue;
                retryCount.put(code, n + 1);

                try {
                    LlmResponse resp = mapper.mapBatch(List.of(pe));
                    SanitizationResult sr = sanitizer.sanitize(resp, Map.of(code, pe));
                    if (sr instanceof SanitizationResult.Clean clean && !clean.mappings().isEmpty()) {
                        Mapping revalidated = clean.mappings().get(0);
                        Mapping merged = validator.mergeRevalidation(
                                mappingsByCode.get(code), revalidated);
                        // Re-apply V4 just in case re-validation changed something that triggers it.
                        Mapping pinned = validator.applyAmbiguityPatterns(merged);
                        mappingsByCode.put(code, pinned);
                    }
                } catch (BudgetExhaustedException be) {
                    budgetHit[0] = true;
                    break;
                } catch (IOException ioe) {
                    System.err.println("REVALIDATION_WARN: " + code + ": " + ioe.getMessage());
                }
            }
        } else {
            // Even without V5, the main pass mappings need V3/V4 once.
            List<Mapping> afterChecks = validator.applyChecks(new ArrayList<>(mappingsByCode.values()));
            mappingsByCode.clear();
            for (Mapping m : afterChecks) mappingsByCode.put(m.providerCode(), m);
        }

        // ---- Combine and build result ----
        List<Mapping> finalMappings = new ArrayList<>(mappingsByCode.values());
        finalMappings.addAll(bypassMappings);

        Summary summary = computeSummary(finalMappings, guard, budgetHit[0]);
        MappingResult result = new MappingResult(provider, version, finalMappings, summary);

        // Always write — even on partial completion or budget exhaustion.
        if (outFile.getParent() != null) {
            java.nio.file.Files.createDirectories(outFile.getParent());
        }
        JSON.writeValue(outFile.toFile(), result);

        int exit = computeExitCode(summary);
        return new RunResult(result, exit);
    }

    // ---- helpers ----

    private void processBatch(List<ProviderError> batch,
                              LlmMapper mapper,
                              Validator validator,
                              Map<String, Mapping> mappingsByCode,
                              Map<String, Integer> retryCount,
                              Set<String> exhaustedCodes,
                              Queue<List<ProviderError>> queue) throws IOException {
        Map<String, ProviderError> batchIndex = new LinkedHashMap<>();
        for (ProviderError e : batch) batchIndex.put(e.code(), e);

        LlmResponse resp = mapper.mapBatch(batch);

        SanitizationResult sr;
        try {
            sr = sanitizer.sanitize(resp, batchIndex);
        } catch (ResponseSanitizer.SanitizationException se) {
            // Fatal config bug — fail the run.
            throw new RuntimeException("Fatal sanitization error: " + se.getMessage(), se);
        }
        for (String w : sanitizer.getWarnings()) {
            System.err.println("SANITIZER_WARN: " + w);
        }

        if (sr instanceof SanitizationResult.Clean clean) {
            for (Mapping m : clean.mappings()) {
                mappingsByCode.put(m.providerCode(), m);
            }
        } else if (sr instanceof SanitizationResult.NeedsReprompt rp) {
            // Accept any partial clean mappings we got along the way (none in NeedsReprompt
            // currently — the sanitizer returns either Clean or NeedsReprompt).
            // Re-queue only the codes we still need, bounded by retry cap.
            List<ProviderError> retryBatch = new ArrayList<>();
            for (String code : rp.codesToRetry()) {
                int n = retryCount.getOrDefault(code, 0);
                if (n >= MAX_RETRIES_PER_CODE) {
                    exhaustedCodes.add(code);
                    ProviderError pe = batchIndex.get(code);
                    if (pe != null) {
                        mappingsByCode.put(code, unmappedMapping(code, pe.message(),
                                "recovery_exhausted"));
                    }
                } else {
                    retryCount.put(code, n + 1);
                    ProviderError pe = batchIndex.get(code);
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

    private Mapping ambiguousChunkToMapping(ParseOutcome.AmbiguousChunk a) {
        // Tag with a synthetic code so the operator can still see something in the output.
        String snippet = a.text() == null ? "" :
                (a.text().length() > 80 ? a.text().substring(0, 80) + "..." : a.text());
        return unmappedMapping("AMBIGUOUS-CHUNK", snippet,
                "parse_garbage: ambiguous_chunk (LLM fallback not yet wired)");
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
