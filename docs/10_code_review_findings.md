# Code Review — Findings & Decisions

**Subject**: AI Decline Code Mapper, pre-submission code review of production Java.
**Reviewer**: independent agent (Opus), no prior context.
**Review prompt**: [apm_decline_mapper_code_review_prompt.md](./apm_decline_mapper_code_review_prompt.md)
**Code under review**: `src/main/java/com/kazimir/declinemapper/`
**Test suite at review time**: 88/88 green.
**Date**: 2026-05-31

---

## Reviewer's verdict

**GO-WITH-FIXES.** Architecture is sound and the test suite covers happy paths well, but the partial-write contract is violated on two exception paths, V5 has nondeterminism and silent warning loss, and there's a five-knob dead-code surface that telegraphs "rushed" to an external evaluator.

## Scores (1–10)

| Axis | Score | Note |
|---|---|---|
| Readability | 7 | Clean structure, clear stage separation; naming drift in Pipeline |
| Consistency | 6 | byCode / codeIndex / mappingsByCode / batchIndex coexist |
| Robustness | 6 | Partial-write contract documented but violated on 2 paths |
| Defensive correctness | 7 | Strong upstream; V5 silently drops warnings |
| Thread-safety | 5 | Mutable `warnings` fields with no documented contract |

## Top 5 must-fix (reviewer)

1. **BLOCKER**: Guarantee `result.json` is written on `SanitizationException` and on `IOException` from `mapBatch`.
2. **MAJOR**: V5 nondeterminism — replace `Collectors.toSet()` with `LinkedHashSet`.
3. **MAJOR**: Drain `sanitizer.getWarnings()` in the V5 path.
4. **MAJOR**: Decide V5-vs-V4-pinned. Either filter pinned codes from `codesNeedingRevalidation`, or document deliberate refinement.
5. **MINOR**: Remove dead-code surface (`AmbiguousChunk`, `LLM_FALLBACK`, `parsePath`, `withReasoning`, `recordMode`).

---

## Author's decisions

| # | Severity / Finding | Adopted? | Action |
|---|---|---|---|
| BLOCKER | `result.json` not written on `SanitizationException` or `IOException` | **Yes, central fix** | Wrap main loop and V5 in try/finally; always emit `result.json` and exit code on any error path |
| MAJOR | V5 iterates `Collectors.toSet()` (HashSet) — nondeterministic | **Yes** | Switch to `LinkedHashSet` |
| MAJOR | V5 path discards `sanitizer.getWarnings()` | **Yes** | Drain warnings inside SanitizationResult (see next row); V5 logs them with prefix |
| MAJOR | V5 spends LLM calls on V4-pinned codes | **Yes** | Filter `isV4Pinned` out of `codesNeedingRevalidation` |
| MAJOR | Mutable `warnings` field on Parser + Sanitizer (footgun) | **Yes — refactor** | Parser returns `ParseResult(outcomes, warnings)` record; Sanitizer returns warnings inside `Clean` and `NeedsReprompt` variants |
| MAJOR | IOException from `mapBatch` bypasses partial-write | **Yes** | Catch in `processBatch`, mark batch as `transport_error`, continue |
| MINOR | Magic numbers (30 in Parser, 80 in truncate, 8000 in log) | **Yes** | Extract to `ANCHOR_KEYWORD_RADIUS`, `SNIPPET_MAX_CHARS`, `LOG_BODY_MAX_CHARS` |
| MINOR | Unsafe `LinkedHashMap` cast + redundant `@SuppressWarnings` in Bootstrap | **Yes** | Use `Map<?,?>.get(...)` directly; drop annotation |
| MINOR | Dead surface: `AmbiguousChunk`, `ParsePath`, `ProviderError.parsePath`, `Mapping.withReasoning`, `Config.recordMode`, `.env.example RECORD_MODE` | **Yes — delete all** | LLM fallback is a documented trade-off (deferred); removing the dead surface so it's not advertised |
| MINOR | Pipeline naming drift (byCode/codeIndex/mappingsByCode/batchIndex) | **Yes** | Pick consistent names; drop the transient `byCode` local entirely |
| MINOR | Pipeline unused imports (Category, GarbageKind) | **Yes** | Delete |
| NIT | BudgetGuard accessors `maxCalls()/maxTokens()` unused; constructor accepts `>= 0` | **Partial** | Delete the accessors; **keep** `>= 0` because test #25 deliberately uses `maxCalls=0` to assert "deny everything" semantics; env parser enforces `>0` for production |

---

## Raw findings (verbatim from reviewer)

### BLOCKER

- **File**: `Pipeline.java`
  - **Finding**: A `SanitizationException` thrown from `processBatch` is wrapped in `RuntimeException` and propagates out of `run()` before `result.json` is written — directly contradicting the docstring guarantee "result.json is written even on partial completion or fatal failure".
  - **Why it matters**: The single documented "fatal config bug" path leaves the operator with no output file. The whole "always write result.json" contract is load-bearing for the CLI exit-code semantics and is silently violated on the one error type Pipeline explicitly anticipates.
  - **Fix**: Wrap the entire `while (!queue.isEmpty())` block (and V5) in try/finally that writes `result.json` and computes the exit code in `finally`, or catch `SanitizationException` at the top of `run()` and route through the partial-write path.

### MAJOR

- **File**: `Validator.java`
  - **Finding**: `codesNeedingRevalidation` returns `Collectors.toSet()` — a `HashSet` with no defined iteration order — and `Pipeline.run` iterates that set under a budget cap.
  - **Why it matters**: Under any run where the budget can be hit mid-V5 (realistic at scale), *which* LOW codes get refined before exhaustion is nondeterministic. The project's stated design contract is "same input → same output".
  - **Fix**: `return … .collect(Collectors.toCollection(LinkedHashSet::new));`

- **File**: `Pipeline.java`
  - **Finding**: V5's single-code re-validation calls `sanitizer.sanitize(...)` but never drains `sanitizer.getWarnings()`. The main-pass loop does. Worse: each subsequent `sanitize()` call starts with `warnings.clear()`, so V5 warnings are silently discarded.
  - **Why it matters**: Soft errors during re-validation (unknown code echoed back, malformed entry, etc.) vanish — operators investigating "why is QP-013 still LOW after V5?" have no breadcrumbs.
  - **Fix**: Drain warnings after the V5 `sanitize` call, prefixed `REVALIDATION_SANITIZER_WARN:`.

- **File**: `Validator.java`
  - **Finding**: `codesNeedingRevalidation` returns *every* LOW code, including V4-pinned ones. The Pipeline spends a real LLM call on each, only for `mergeRevalidation` to discard the confidence/review fields (sticky V4 wins). At worst this doubles the V5 call count for no behavioural change.
  - **Why it matters**: V4-pinned codes are LOW *by deterministic policy*. The wasted calls eat into `MAX_LLM_CALLS` and can starve genuinely-LOW codes of a re-validation slot.
  - **Fix**: Filter `isV4Pinned` codes out of `codesNeedingRevalidation`.

- **File**: `Parser.java`
  - **Finding**: `warnings` is a mutable instance field, and `Pipeline.parser` is long-lived. Same issue in `ResponseSanitizer`.
  - **Why it matters**: Class advertises no thread-safety contract but is held as a long-lived field, inviting reuse. Footgun for the next reader.
  - **Fix**: Return warnings *inside* the result object (`record ParseResult(List<ParseOutcome> outcomes, List<String> warnings)`). Same for Sanitizer: add warnings to Clean and NeedsReprompt variants.

- **File**: `Pipeline.java`
  - **Finding**: Pipeline.processBatch only handles `BudgetExhaustedException` and `SanitizationException`; an `IOException` from `mapper.mapBatch(batch)` propagates out of `run()`.
  - **Why it matters**: A cache-corruption or network glitch shouldn't lose the partial mappings already accumulated.
  - **Fix**: Catch `IOException` in `processBatch`, log to stderr, mark the in-flight batch with `transport_error`, continue.

### MINOR

- **File**: `Parser.java` / `AnthropicLlmClient.java`
  - **Finding**: Magic numbers — `30` in `NUMERIC_NEAR_KEYWORD`, `80` reused 3× as a truncation length, `8000` in `logJsonl`.
  - **Fix**: Extract to `ANCHOR_KEYWORD_RADIUS = 30`, `SNIPPET_MAX_CHARS = 80`, `LOG_BODY_MAX_CHARS = 8000`. Share `SNIPPET_MAX_CHARS` from Pipeline's ambiguous-chunk path.

- **File**: `Bootstrap.java`
  - **Finding**: Line 166-167 cast `Map<?, ?>` to `LinkedHashMap<String, Object>` to call `.get()`. Gratuitous and unsafe.
  - **Fix**: Replace with `map.get("match")`. Drop `@SuppressWarnings("unchecked")`.

- **File**: `ParseOutcome.java` / `ParsePath.java` / `Parser.java`
  - **Finding**: `ParseOutcome.AmbiguousChunk` is sealed-permitted and switched on, but Parser never emits it. Same for `ParsePath.LLM_FALLBACK`. `ProviderError.parsePath` field is set once and never read.
  - **Fix**: Delete `AmbiguousChunk`, `ParsePath.LLM_FALLBACK`, `ProviderError.parsePath` until the LLM fallback ships.

- **File**: `Mapping.java`
  - **Finding**: `Mapping.withReasoning` is never called.
  - **Fix**: Delete.

- **File**: `Config.java` / `Bootstrap.java` / `.env.example`
  - **Finding**: `Config.recordMode` is parsed from `RECORD_MODE` env, plumbed through the record, and never read.
  - **Fix**: Remove from Config record, Bootstrap, and .env.example until the fixture-record feature lands.

- **File**: `Pipeline.java`
  - **Finding**: Naming drift — `codeIndex`, `mappingsByCode`, `byCode`, `batchIndex` all refer to `Map<String, ?>` keyed by provider code.
  - **Fix**: Settle on consistent names; drop the transient `byCode` local entirely (the existing reconstruction below already does what it needed to do).

- **File**: `Pipeline.java`
  - **Finding**: Unused imports `Category` and `GarbageKind`.
  - **Fix**: Delete.

### NIT

- **File**: `BudgetGuard.java`
  - **Finding**: `maxCalls()` and `maxTokens()` accessors never called externally. Constructor accepts `>= 0`.
  - **Fix (partial)**: Delete the accessors. **Keep** `>= 0` because test #25 deliberately uses `maxCalls=0` to assert "deny-everything" semantics; env parser already enforces `> 0` for production.

---

## Net impact (forecast)

- **Public API changes**: `Parser.parse()` returns `ParseResult` instead of `List<ParseOutcome>`; `SanitizationResult.Clean` and `.NeedsReprompt` gain a `warnings` field.
- **Dead code removed**: 5 distinct knobs (~80 LoC + .env line).
- **Constants extracted**: 3.
- **Pipeline rewrite scope**: try/finally around main + V5; IOException catch; naming pass.
- **Validator change**: 2 lines (LinkedHashSet + V4-pin filter).
- **Tests**: existing 88 should remain green after refactor; one new test to lock in the partial-write guarantee on SanitizationException would be welcome.
