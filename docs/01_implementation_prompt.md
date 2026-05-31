# Prompt: Implement the "AI Decline Code Mapper"

> **Revision 3** — incorporates the architecture review ([apm_decline_mapper_arch_review_findings.md](./apm_decline_mapper_arch_review_findings.md)) and the test-strategy review ([apm_decline_mapper_test_review_findings.md](./apm_decline_mapper_test_review_findings.md)). This document is **self-contained** — you do not need to read the architecture or testing-concept files to implement the task, though they exist as backing context.
>
> Differences from rev 2: full Bootstrap phase; `ParseOutcome` sealed type with `Garbage` bypass; `ResponseSanitizer` sub-stage in Stage 3; eight defence layers (was five); `Random(42)` per-instance ownership pinned; cache dir as constructor-argument; mandatory chaos test; named tests for the four ambiguous codes; split structural/content gold-file assertion; `FixtureLlmClient` as the default test seam.

You are a senior engineer building a small but production-grade tool — the **AI Decline Code Mapper**. Time budget ≈ 3 hours. Clean design > exhaustive edge cases.

## What you are building

A CLI service that ingests a payment provider's error-code documentation **as raw prose text** (not JSON — actual vendor docs as they appear in PDFs/READMEs/Confluence) and emits a JSON document mapping every code to one of seven internal decline categories, with confidence, reasoning, retry strategy and human-review flag.

The LLM must be used as a **controlled component inside an agent pipeline**, not as a single black-box call. Stages are explicitly separated and individually testable. Validation is deterministic and lives outside the stage that produced the data.

## Stack

- **Java 21** (records, sealed types, pattern matching, switch expressions).
- **Maven** for build.
- **Jackson** for JSON.
- **`java.net.http.HttpClient`** (stdlib) for Anthropic API — no SDK dependency.
- **SnakeYAML** for `ambiguity_patterns.yaml`.
- **JUnit 5** + **AssertJ** for tests.
- **No** Picocli, Lombok, DI containers, logging framework. `String[] args` + `System.getenv` + `System.out` are enough.

**Base package**: `com.kazimir.declinemapper`. All file paths below use this prefix; replace with your organization's namespace if reusing this prompt for a different repo. The Maven `groupId` in `pom.xml` should match the prefix without the artifact segment (e.g., `com.kazimir`).

## Internal taxonomy (closed set, 7 categories)

| Category | Meaning | Retryable? |
|---|---|---|
| `SYSTEM_MALFUNCTION` | Internal/provider infra failure, unexpected response | yes, with backoff |
| `COMMON_DECLINE` | Generic bank/provider decline (NSF, limit, expired card) | depends on sub-code |
| `ANTIFRAUD` | Blocked by fraud detection (provider- or bank-side) | no |
| `BAD_DATA_PROVIDED` | Invalid input: wrong account, currency, malformed fields | no, fix input first |
| `CANCELLED_BY_CUSTOMER` | Customer cancelled/abandoned/refused | no |
| `PROVIDER_LIMIT` | Rate limit, daily/monthly cap, quota | yes, after cooldown |
| `AUTHENTICATION_FAILURE` | Bad credentials, expired token, signature mismatch | no, fix config first |

Retry-strategy vocabulary (closed set): `no_retry`, `retry_with_backoff`, `retry_after_fix`, `no_action`.

Both must be Java `enum`s. The compiler will then refuse to construct a hallucinated value, and Jackson will throw on deserialization of an unknown string — a free first line of defence.

## Required architecture — Bootstrap + 4 stages + budget guard

### Bootstrap (before pipeline)

Fail-fast on misconfiguration **before** any input is read.

- `ANTHROPIC_API_KEY` present and non-empty.
- `LLM_MODEL` default applied if unset; warning to stderr.
- `MAX_LLM_CALLS`, `MAX_TOKENS_PER_RUN` parse as positive integers.
- `ambiguity_patterns.yaml` exists on classpath; YAML parses; every `match` compiles as a `Pattern`. **All** bad patterns listed in the error message — not just the first.
- **No pattern is "dangerously broad"**: rejects empty `match`, bare `.*`, `.+`, `(?i).*`, or any pattern that matches a curated set of clearly-unambiguous reference messages (e.g., "Insufficient balance", "Card expired"). If a pattern matches one of those, it's rejected with "pattern is dangerously broad".

Bootstrap failure exits with code 1 and a leading stderr line `BOOTSTRAP_ERROR: <details>`.

### Stage 1 — Parser: deterministic-first, LLM fallback, garbage bypass

Output type:

```java
sealed interface ParseOutcome
    permits ParseOutcome.Ok,
            ParseOutcome.AmbiguousChunk,
            ParseOutcome.Garbage {

    record Ok(ProviderError error) implements ParseOutcome {}
    record AmbiguousChunk(String text) implements ParseOutcome {}
    record Garbage(String code, GarbageKind kind, String detail) implements ParseOutcome {}
}

enum GarbageKind {
    NO_MESSAGE, DUPLICATE_CONFLICT, DESCRIPTION_TOO_LONG, NON_UTF8, EMPTY_INPUT
}
```

**Implementation:**
1. **Anchor scan** (regex): find all code-like tokens. Pattern: `[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+` plus standalone `\b\d{2,4}\b` within 30 chars of `code:` / `error:` / `result_code:`.
2. **Section walker** (state machine): scan line-by-line. Track current section from `===` / `==` / `--` headers. When a line matches the anchor regex, capture the quoted message on the same line and the indented continuation lines as the description. Emit `ParseOutcome.Ok(ProviderError(...))`.
3. **Ambiguity criteria**: emit `ParseOutcome.AmbiguousChunk(rawText)` when (a) no quoted message after the code, (b) description contains nested code references, (c) chunk spans multiple sections.
4. **Garbage detection**: emit `ParseOutcome.Garbage(...)` for: empty file (`EMPTY_INPUT`), non-UTF-8 input (`NON_UTF8`), code with no message that isn't ambiguous either (`NO_MESSAGE`), same code with conflicting descriptions (`DUPLICATE_CONFLICT`), description > 5000 chars (`DESCRIPTION_TOO_LONG`).
5. **LLM fallback**: only for `AmbiguousChunk`. Pass the raw chunk; ask for `{code, message, description}` extraction. Log per-code parse path (`STATE_MACHINE` vs `LLM_FALLBACK`) to `logs/run-*.jsonl`.
6. **Cross-check**: compare anchor scan count vs final parser output count. Mismatch → **warning to stderr**, **not** a re-prompt trigger. The anchor regex is not a correctness oracle.

`Garbage` outcomes **never reach the Mapper**. They are written directly to the final result with `review_reason = "parse_garbage: <kind>: <detail>"`. Token cost: zero.

### Stage 2 — Enricher: deterministic, no LLM

Builds the system prompt for the Mapper. Contains, in this order:

1. Taxonomy table (verbatim, 7 categories with descriptions and retry policy).
2. Retry-strategy vocabulary (4 values, one-line meaning each).
3. **Operational confidence rubric** (verbatim in the prompt):
   ```
   confidence = "high"   when: exact match to one taxonomy category,
                              no hedging language in the reasoning,
                              no plausible second category.
   confidence = "medium" when: one primary category is clear,
                              but a secondary category is plausible.
   confidence = "low"    when: two or more categories are plausible,
                              OR the provider message is vague.
   ```
4. Two **synthetic, provider-neutral** few-shot examples:
   - `XX-001 "Card balance insufficient"` → `COMMON_DECLINE`, `high`, `no_retry`.
   - `YY-042 "Transaction not allowed"` → `COMMON_DECLINE`, `low`, `needs_human_review: true`, `review_reason` citing the multiple plausible interpretations.
5. Hard instruction: "Return only via the provided tool. Choose `internal_category` strictly from the 7 values. If unsure, follow the confidence rubric above and set `needs_human_review: true`. Do not invent categories."

**Never** use QP-coded examples in the prompt — the Enricher's anti-anchoring test asserts no `QP-\d+` token in the produced string.

### Stage 3 — Mapper: LLM + Sanitizer

Two sub-steps in sequence:

**3a. LLM call**
- HTTP POST to Anthropic `/v1/messages` via stdlib `HttpClient`.
- `tool_use` with a strict JSON schema (categories and retry-strategies as `enum` arrays — schema-level rejection of hallucinated values).
- `temperature: 0`. Document the **exact model ID** in `.env.example`.
- **Batch 5–10 codes per call.**
- **Within-batch order randomized** with **`new Random(42)` held as a per-instance field of `LlmMapper`, never static** — so parallel `LlmMapper` instances don't share state.
- **Cache directory is a constructor argument** of `LlmMapper`, not a hardcoded path. Production → `logs/cache/`; every test → `@TempDir`. Cache key: `sha256(model + prompt_template_version + canonical(batch_input))`.
- Every request and response appended to `logs/run-{timestamp}.jsonl` as one line: `{ts, stage, batch_id, model, request, response, tokens_in, tokens_out, cache_hit}`.

**3b. ResponseSanitizer**

A separate deterministic class. Runs on every raw `LlmResponse` before it reaches Stage 4. Output is a sealed `SanitizationResult` — either `Clean(List<Mapping>)` or `NeedsReprompt(reason, Set<String> codesToRetry)`.

Checks:
- `tool_use` block present with name `map_codes`. Wrong name → fatal (config bug).
- Tool input parses as JSON.
- `stop_reason == "end_turn"`. If `max_tokens` → drop incomplete entries, re-prompt only missing codes.
- Every returned code ∈ batch input. Unknown codes stripped with warning.
- No duplicate codes in response. First wins, rest dropped with warning.
- `confidence` and `retry_strategy` strings trimmed and case-normalized before enum parse.
- Required fields non-null.

Sanitizer is the **R1** defence layer — handles **syntactic** validity. The Validator (Stage 4) handles **semantic** validity. Two different classes, two different test surfaces.

### Stage 4 — Validator + recovery: deterministic

Six checks in order, against the sanitized `List<Mapping>`:

1. **V1** — JSON parses; enums deserialize (Jackson refuses unknown values).
2. **V2** — coverage: every input code present in output. Missing → re-prompt only those codes.
3. **V1 redundant explicit check** — readable error message if Jackson somehow passed.
4. **V3** — hedge demotion: `HIGH` + reasoning regex match `\b(could be|might|ambiguous|unclear|depends|possibly|maybe|not sure|seems)\b` → demote to `MEDIUM`, log.
5. **V4** — **ambiguity patterns** (central correctness defence): load `ambiguity_patterns.yaml`. Each entry has `match` (regex, case-insensitive) on `provider_message` and `reason` (string). Any match forces:
   - `confidence = LOW`,
   - `needs_human_review = true`,
   - `review_reason = "known_ambiguous_pattern: <reason>"`,
   - **regardless of what the LLM returned**.
6. **V5** — single-code re-validation: every code flagged `LOW` (by V3, V4, or the LLM itself) gets one more Mapper call **as batch-of-1**, with explicit "this code is suspected ambiguous — confirm or correct" instruction. **V4-pinned `LOW` is sticky** — re-validation may refine `reasoning`, never raise `confidence`.

**V4 wins over V3** when both trigger on the same code. This precedence is enforced by tests.

**Recovery limits:**
- Per-code re-prompt cap: 2.
- After cap → `internal_category = null`, `confidence = LOW`, `needs_human_review = true`, `review_reason = "recovery_exhausted"`, counted in `summary.unmapped`.

### Run-level budget guard

Enforced in `Pipeline.java` around every LLM call (Parser fallback, Mapper, V5 re-validation):

- `MAX_LLM_CALLS` (env, default 40): hard ceiling.
- `MAX_TOKENS_PER_RUN` (env, default 200_000): sum of `tokens_in + tokens_out`.
- **Cache hits do NOT count against `MAX_LLM_CALLS`** — enforced by a dedicated test.

When either ceiling hits:
1. Stop calling the LLM.
2. Remaining codes → `unmapped` with `review_reason = "budget_exhausted"`.
3. `summary.budget_exhausted = true`.
4. **`result.json` is written anyway.**
5. Exit code 2.

### Eight defence layers — control over the LLM

| # | Layer | Where | Mechanism | Catches |
|---|---|---|---|---|
| B0 | Bootstrap | Init | env validated, YAML loaded, regex compiled & smoke-tested | Misconfiguration, broken or dangerously-broad patterns |
| P1 | Parse outcome | Stage 1 | `ParseOutcome.Garbage` bypasses Mapper | Empty file, binary, truncated, duplicate-conflict, encoding |
| R1 | Response sanitizer | Stage 3 | `SanitizationResult` shape-checks raw response | Malformed JSON, missing tool_use, max_tokens truncation, unknown/duplicate codes |
| V1 | Schema/enum | Stage 4 | `tool_use` schema + Java `enum` deserialization | Hallucinated category strings |
| V2 | Coverage | Stage 4 | Input ↔ output code-set diff | Skipped codes |
| V3 | Hedge demotion | Stage 4 | Regex on `reasoning` + `HIGH` | Self-inconsistent confidence |
| V4 | Ambiguity patterns | Stage 4 | YAML match on `provider_message` | Known-ambiguous codes the LLM categorized confidently anyway |
| V5 | Re-validation pass | Stage 4 | Single-code Mapper call with sticky `LOW` | Cross-code contamination from batching |

V4 is the **load-bearing layer for the assignment's headline requirement** — the only layer that grades the LLM independently of its own output.

## Output schema (exact)

```json
{
  "provider": "string",
  "version": "string",
  "mappings": [
    {
      "provider_code": "string",
      "provider_message": "string",
      "internal_category": "SYSTEM_MALFUNCTION|COMMON_DECLINE|ANTIFRAUD|BAD_DATA_PROVIDED|CANCELLED_BY_CUSTOMER|PROVIDER_LIMIT|AUTHENTICATION_FAILURE|null",
      "confidence": "high|medium|low",
      "reasoning": "string",
      "retry_strategy": "no_retry|retry_with_backoff|retry_after_fix|no_action",
      "needs_human_review": false,
      "review_reason": "string|null"
    }
  ],
  "summary": {
    "total_codes": 0,
    "mapped": 0,
    "high_confidence": 0,
    "needs_review": 0,
    "unmapped": 0,
    "budget_exhausted": false,
    "llm_calls": 0,
    "tokens_used": 0
  }
}
```

`internal_category` is `null` only for `unmapped` entries (recovery/budget/parse_garbage exhaustion). The mapping is still emitted so consumers see which codes were left out.

## Known ambiguities — must come back `LOW + review`

These four codes from the assignment PDF **must** be flagged `confidence: "low"` and `needs_human_review: true`, **enforced by named unit tests plus an explicit by-name assertion in the QuickPay e2e**:

- `QP-005 "Transaction not permitted"` — antifraud / card restriction / MCC ambiguity.
- `QP-008 "Do not honor"` — issuer catch-all.
- `QP-009 "Duplicate transaction"` — legit idempotency vs replay attack; also `retry_strategy: "no_action"`.
- `QP-401 "Session expired"` — customer abandonment vs system timeout.

If your output marks any of these as `high` confidence, the pipeline is wrong.

## Required resource: `ambiguity_patterns.yaml`

`src/main/resources/ambiguity_patterns.yaml`:

```yaml
patterns:
  - match: "(?i)do not honor"
    reason: "Issuer catch-all — could be antifraud, limit, or generic decline."
  - match: "(?i)duplicate"
    reason: "Could be legitimate idempotency or replay attack."
  - match: "(?i)not permitted"
    reason: "Could be antifraud, card restriction, or MCC issue."
  - match: "(?i)session expired"
    reason: "Could be customer abandonment or system timeout."
  - match: "(?i)unknown (reason|error)"
    reason: "Unspecified — by definition ambiguous."
```

Extensible without code changes. New providers contribute new entries. **First match wins** when multiple patterns match the same message — this is contract, pinned by a test.

## Configuration (env vars)

```
ANTHROPIC_API_KEY=...
LLM_MODEL=claude-sonnet-4-5
MAX_LLM_CALLS=40
MAX_TOKENS_PER_RUN=200000
CACHE_ENABLED=true
RECORD_MODE=false        # tests only — enables RecordedLlmClient to hit real API
```

`.env.example` checked in. Real `.env` git-ignored. No keys in source. Document the **resolved model ID** (e.g., `claude-sonnet-4-5-20250929`) so cache and reproducibility don't drift on a silent alias roll.

## CLI

```
java -jar decline-mapper.jar <input.txt> <provider-name> <provider-version> <output.json>
```

Positional args, no flags. `System.out` prints the `summary` block.

**Exit codes**:
- `0` — all codes mapped, no `needs_review`, no `unmapped`.
- `1` — bootstrap failure (stderr `BOOTSTRAP_ERROR:` line) **or** all codes mapped but at least one `needs_human_review = true`.
- `2` — at least one code `unmapped`.

## Testing requirements

**Total budget**: ~68 tests; ~43 unit, ~22 component, ~3 integration. CI runtime under 10 seconds. **Zero LLM tokens consumed in CI.**

### The mock seam

One interface, three implementations:

```java
public interface LlmClient {
    LlmResponse call(LlmRequest request) throws IOException;
    int lastCallTokensIn();
    int lastCallTokensOut();
}
```

- `AnthropicLlmClient` — production. Real HTTP.
- `ScriptedLlmClient` — component tests. Programmable queue: `enqueue(response)`, `enqueueHttpError(status)`, `enqueueRepeating(response)` for the chaos test.
- `FixtureLlmClient` — **default for e2e**. File-backed responses keyed by `(provider, batchIndex)`. Filename pattern: `{provider}_batch{N}.json` under `src/test/resources/llm_responses/`. Hand-authored from day one; no API key needed to bootstrap.
- `RecordedLlmClient` — optional, hash-keyed (`sha256(canonical(request))`). For developers who want to validate against a real LLM run via `RECORD_MODE=true`.

### Test naming convention

`<defence_layer_or_stage>_<expected_behaviour>_when_<condition>` — sentence-form, lowerCamel.

Good:
- `v4_forcesLowReview_forQp008_doNotHonor`
- `parser_emitsGarbage_whenInputNonUtf8`
- `mapper_makesOneCall_whenBatchSizeExact`

Bad: `test19`, `validatorTestAmbiguous`, `testQp005`.

### Mandatory tests (you must implement these by name)

**By name — defence V4 on the four ambiguous codes:**
- `v4_forcesLowReview_forQp005_transactionNotPermitted` [unit]
- `v4_forcesLowReview_forQp008_doNotHonor` [unit]
- `v4_forcesLowReview_forQp009_duplicateTransaction` [unit] — also asserts `retry_strategy: no_action`
- `v4_forcesLowReview_forQp401_sessionExpired` [unit]

A typo in the YAML that breaks one of these four must fail a **named** test, not silently slip past a 24-mapping gold-file diff.

**V3 vs V4 precedence:**
- `v4_winsOver_v3_whenBothTriggerOnSameCode` [unit] — input has hedge in reasoning AND message matches an ambiguity pattern. Final confidence is `LOW` (V4), not `MEDIUM` (V3).

**The chaos test — single most important test in the suite:**
- `chaos_alwaysAntifraudHigh_pipelineStillCorrectFor4AmbiguousCodes` [component]:
  ```
  ScriptedLlmClient returns ANTIFRAUD/HIGH/no-hedge for EVERY code.
  Run full QuickPay pipeline.
  Assert: the 4 ambiguous codes still come out LOW + review:true.
  Assert: every reviewed code's review_reason starts with "known_ambiguous_pattern:".
  ```
  This is the single test that proves the LLM is **controlled**. Without it, a regression where the validator is silently disabled would pass the entire suite.

**Batch boundary tests** [component]:
- n = `batchSize` exactly → 1 call.
- n = `batchSize + 1` → 2 calls, sizes `(batchSize, 1)`.
- n = `2 * batchSize` → 2 calls, sizes `(batchSize, batchSize)`. Union of received covers each code exactly once.

**Random reproducibility:**
- `random42_perInstance` [unit] — two `LlmMapper` instances, batch shuffle identical and reproducible.
- `random42_threadSafe` [component] — two parallel `LlmMapper` instances on separate threads, each independently reproducible.

**Cache dir leakage guard** [component]:
- After a full pipeline run with `@TempDir`, walk the working directory and assert no `logs/cache/` was created outside the temp dir.

**Cache hits vs budget:**
- Pre-populate cache, set `MAX_LLM_CALLS=0`, run → `summary.llm_calls == 0`, `summary.budget_exhausted == false`, every code mapped.

**Overly-broad pattern guard:**
- Bootstrap rejects `match: ""` and `match: ".*"` with "pattern is dangerously broad".

**Test #19 replacement (V4 extensibility, three sub-tests):**
- `v4_ambiguityPattern_isCaseInsensitive_whenInputUppercased` [unit]
- `v4_ambiguityPattern_reasonAppearsVerbatim_inReviewReason` [unit]
- `v4_ambiguityPattern_firstMatchWins_whenTwoMatch` [unit]

### Gold-file assertion strategy — split structural / content

E2e tests are **not byte-for-byte** against `expected/*.result.json`. Two assertions:

**Structural (pinned, fails on real bugs):**
- Every input code appears in `mappings` (no skipped, no duplicate).
- For each code: `internal_category`, `retry_strategy`, `confidence`, `needs_human_review` match gold exactly.
- `summary.total_codes`, `summary.mapped`, `summary.needs_review`, `summary.unmapped`, `summary.budget_exhausted` match gold exactly.

**Content (presence-only, tolerates LLM drift):**
- For each code: `reasoning` is non-empty.
- For each code with `needs_human_review: true`: `review_reason` starts with `known_ambiguous_pattern:` OR is `recovery_exhausted` / `budget_exhausted` / `parse_garbage:`.
- `summary.llm_calls`, `summary.tokens_used` are non-negative integers.

PR reviewers reviewing a changed `expected/*.result.json` only need to read the structural pins.

### Fixtures (test inputs)

Six files, all committed:

1. **`quickpay_v2.4.txt`** — 24 codes from the assignment PDF.
2. **`fastbank_v1.0.txt`** — invented second provider, 8 codes including `do_not_honor` (snake_case + ambiguity pattern), `05` (numeric-only), `ERR_RATE_LIMIT` (snake_case PROVIDER_LIMIT), `Duplicate charge attempt` (ambiguity pattern). Full contents in the testing-concept doc.
3. **`malformed_provider.txt`** — `NOMSG-001` (no quoted message → `Garbage`), `CROSSREF-001` (multi-paragraph nested ref → `AmbiguousChunk`).
4. **`empty.txt`** — zero bytes.
5. **`single_code.txt`** — one code, exercises batch-of-1.
6. **`messy_provider.txt`** — adversarial-by-design: markdown table for some codes, tab-indented description, lowercase section header (`== auth ==`), footnote-style codes inside prose, inline cross-reference. Either the parser handles them or emits `Garbage` cleanly — both are acceptable outcomes pinned by gold standard.

### Gold standards committed:
- `samples/quickpay_v2.4.result.json` — reference output, regenerated when the prompt changes.
- `src/test/resources/expected/quickpay_v2.4.result.json` — same content, used by e2e structural assertion.
- `src/test/resources/expected/fastbank_v1.0.result.json`.
- `src/test/resources/expected/messy_provider_v0.9.result.json`.

## File layout

```
apm-decline-mapper/
├── README.md
├── .env.example
├── .gitignore
├── pom.xml
├── src/main/java/com/kazimir/declinemapper/
│   ├── Main.java                            String[] args entry; calls Bootstrap, Pipeline
│   ├── Bootstrap.java                       env + YAML + regex validation
│   ├── Pipeline.java                        wires 4 stages + budget guard
│   ├── model/
│   │   ├── Category.java                    enum (7)
│   │   ├── RetryStrategy.java               enum (4)
│   │   ├── Confidence.java                  enum (3)
│   │   ├── GarbageKind.java                 enum (5)
│   │   ├── ParsePath.java                   enum (2)
│   │   ├── ParseOutcome.java                sealed + 3 records
│   │   ├── SanitizationResult.java          sealed + 2 records
│   │   ├── ProviderError.java               record
│   │   ├── Mapping.java                     record
│   │   ├── Summary.java                     record
│   │   └── MappingResult.java               record
│   ├── stage/
│   │   ├── Parser.java
│   │   ├── Enricher.java
│   │   ├── LlmMapper.java                   constructor: (LlmClient, Path cacheDir, BudgetGuard)
│   │   ├── ResponseSanitizer.java
│   │   └── Validator.java
│   ├── llm/
│   │   ├── LlmClient.java                   interface
│   │   └── AnthropicLlmClient.java
│   └── budget/
│       └── BudgetGuard.java
├── src/main/resources/
│   ├── prompts/
│   │   ├── parser-fallback.md
│   │   └── mapper-system.md
│   ├── taxonomy.md
│   └── ambiguity_patterns.yaml
├── src/test/java/com/kazimir/declinemapper/
│   ├── unit/                                ~43 tests
│   ├── component/                           ~22 tests
│   ├── integration/                         3 tests
│   └── fakes/
│       ├── ScriptedLlmClient.java
│       ├── FixtureLlmClient.java
│       └── RecordedLlmClient.java
├── src/test/resources/
│   ├── fixtures/                            6 input files
│   ├── expected/                            3 gold-standard result files
│   └── llm_responses/                       canned responses for FixtureLlmClient
├── fixtures/                                production-mode inputs
├── samples/
│   └── quickpay_v2.4.result.json
└── logs/
    ├── .gitkeep
    ├── run-{ts}.jsonl
    └── cache/                               git-ignored
```

## Test commands

```bash
mvn test                       # full suite, zero tokens
mvn test -Dtest=ValidatorTest  # one class
mvn test -Dtest=*_when_*       # tests matching the naming convention
mvn test -DRECORD_MODE=true    # OPTIONAL, requires API key, refreshes hash-keyed recordings
```

CI runs `mvn test`. No API key in CI. No tokens consumed.

## Deliverables

1. Git repo.
2. `README.md` containing:
   - Minimal run steps (`mvn package`, set env, run jar).
   - ASCII architecture diagram (Bootstrap + 4 stages + budget guard).
   - **Defence-layers table** (B0, P1, R1, V1-V5).
   - Test description: how to run, what the chaos test proves, where the gold-standard pins live.
   - **Trade-offs section**: simplified vs would-do-with-more-time (multi-provider batching, interactive disambiguation UI, persistent decline-code DB with versioning, golden-set regression tests across model versions, multi-model consensus for `LOW`-confidence codes, structured exception types).
3. `samples/quickpay_v2.4.result.json` checked in.
4. `logs/` git-ignored; `.gitkeep` present.

## Non-goals (do NOT spend time on)

- Authentication / multi-tenancy.
- Persistent database.
- Web UI.
- Streaming / async / queues / virtual threads.
- DI containers, hexagonal architecture, domain events.
- Splitting `AnthropicLlmClient` from JSONL logging (reviewer waived for 3h budget).
- Cross-run shuffle ordering (within-batch shuffle is enough).
- Mutation testing, property-based testing.

## Quality bar

- **Validator + ambiguity_patterns.yaml is the proof-of-control.** If `chaos_alwaysAntifraudHigh_pipelineStillCorrectFor4AmbiguousCodes` passes, the LLM is genuinely controlled. If it fails, you have shipped an uncontrolled black box.
- **Every defence layer must have a targeted test** that fails specifically when that layer is removed. Coverage breadth is good; layer-targeted depth is what the test review demanded.
- **Mapper stage is debuggable.** Every LLM call (request + response + token counts + cache_hit flag) appended to `logs/run-{ts}.jsonl`. No call is invisible.
- **Partial output is always written.** A crash mid-run that leaves `result.json` absent is a bug, not a degraded mode.
- **Code reads like one person wrote it in one sitting.** Records, sealed types, switch expressions. No dead abstractions, no "for future extension" hooks.

## Recommended order of work (≈3 hours)

| Time | Task |
|---|---|
| 0:00–0:20 | `Bootstrap.java` + `ambiguity_patterns.yaml` + env-var plumbing. Land tests #46, #46a, #48, #49. |
| 0:20–0:50 | `Parser.java` with `ParseOutcome` sealed type. Land tests #1, #3, #4, #5, #30, #32–37. |
| 0:50–1:10 | `Enricher.java`. Land tests #6, #7, #8. |
| 1:10–1:50 | `LlmClient` interface + `ScriptedLlmClient` + `FixtureLlmClient`. `LlmMapper.java` with batching, cache, Random(42) per-instance. Land tests #9, #10, #11, #12, #27, #50–55. |
| 1:50–2:10 | `ResponseSanitizer.java`. Land tests #38–45. |
| 2:10–2:35 | `Validator.java` with 6-check pipeline. Land tests #15–22, #18a–e, #19a–c, #21, #56 (chaos). |
| 2:35–2:50 | `BudgetGuard.java` + Pipeline wiring. Land tests #23–25, #57. |
| 2:50–3:00 | E2E tests #28, #29, #58. README. Final `mvn test`. |

Stop and ship at 3 hours. If something is red, document it in README and submit anyway. The chaos test, the 4 named V4 tests, and the budget-exhausted-still-writes-result-json test are the three you cannot ship without.

## Pre-submission workflow (reusable across projects)

After the 3-hour build, before tagging the repo as "ready for review", the following passes are mandatory. They are cheap to run, they catch the class of issues that ship-day reviewers will catch in the first 5 minutes, and they make the project's CI a green-checkmark signal rather than just a claim in the README.

### W1. Independent code review (~30 min)

Run a hard code-review pass against the production tree with a separate agent / reviewer who did **not** write the code. Use the reusable prompt at `docs/09_code_review_prompt.md`. Expect 5–15 findings; the standard severity bands are BLOCKER / MAJOR / MINOR / NIT.

Apply at minimum every BLOCKER and every MAJOR. Document any MINOR/NIT you skip with a one-line rationale in the resolution file. Run `mvn test` after each fix batch. Lock in any BLOCKER fix with a regression test.

Save the findings + decisions to `docs/10_code_review_findings.md` for the audit trail.

### W2. Build the deployable artifact (~10 min)

```bash
mvn -B clean package
```

Verify:
- The jar exists at the expected path with the correct file name.
- The manifest declares the right `Main-Class`.
- All required runtime resources (here: `ambiguity_patterns.yaml`, any prompt files) are inside the jar (`unzip -l target/<jar>`).
- The shade plugin merged dependencies correctly (no `ClassNotFoundException` for transitive deps at runtime).

### W3. CLI smoke test with a fake API key (~5 min)

Run the binary end-to-end with credentials that will fail at the LLM layer but pass everything else:

```bash
ANTHROPIC_API_KEY=sk-ant-fake-not-real \
  MAX_LLM_CALLS=2 CACHE_ENABLED=false \
  java -jar target/decline-mapper.jar fixtures/<provider>.txt "<Provider>" "<Version>" /tmp/result.json
```

Verify:
- Bootstrap passes (no `BOOTSTRAP_ERROR:` on stderr).
- Parser extracts the expected code count.
- The Anthropic API rejects the fake key with HTTP 401 (the *request shape is right*, only the key is wrong — most common smoke-test failure is a 400 from a malformed body).
- `result.json` is written with `unmapped` entries carrying `transport_error: ...` in `review_reason`.
- Exit code is `2` (codes left unmapped).

This catches: misnamed main class, missing resources in jar, wrong API endpoint, malformed request body, broken exit-code semantics, swallowed exceptions. ~5 minutes for a high-signal sanity check.

### W4. CI workflow (~10 min)

Add `.github/workflows/test.yml` (or equivalent) that runs `mvn test` on push and PR. Use Java 21, Maven caching, no API key. The CI gate must reproduce the local `mvn test` result. Optionally upload the built jar as an artifact.

The point is the green checkmark in the PR view — reviewers see "tests pass" before they clone.

### W5. AnthropicLlmClient retry unit tests (~20 min)

The retry policy claimed in the class's javadoc must be locked in by a test. Cover at minimum:
- Transient 5xx → retry → succeed.
- 429 → retry → succeed.
- All attempts fail (transient) → fatal after `MAX_ATTEMPTS`.
- 4xx non-429 → fatal, no retry.
- Transient followed by fatal → fail at the fatal step.
- `IOException` during `send` → retry → succeed.

This requires a small refactor: the production class should accept a `Function<HttpRequest, HttpResponse<String>>` (or equivalent `HttpSender` interface) instead of stdlib `HttpClient` directly. Then tests inject a scripted sender; the production constructor wraps the stdlib client.

### W6. Adversarial fixture (~10 min)

Add one fixture that breaks the assumptions of the happy-path fixtures: mixed-case section headers, footnote-style codes, embedded cross-references, smart quotes if your parser cares. Either the parser handles it or surfaces it as `Garbage` cleanly. Both outcomes are acceptable — both must be tested.

### W7. README hygiene (~10 min)

- Replace any `cat .env | xargs` with `set -a; source .env; set +a` (robust against values with spaces/quotes).
- Flag synthetic sample outputs as such ("generated by test harness; reasoning text is canned — run against real API to refresh").
- Verify the architecture diagram still matches the code (defence-layer counts, stage names).
- Verify the trade-offs section enumerates the deferred features (so reviewers see them documented, not as bugs).

### W8. (Optional) Real-LLM run (~$0.01, 5 min)

Set a real `ANTHROPIC_API_KEY` and run the CLI on the primary fixture. This is the only step that costs money. It validates:
- Real Anthropic API request/response shape (catches subtle field misnaming).
- `samples/*.result.json` reflects model-authored prose, not test-harness placeholders.
- Token counts and `llm_calls` in the summary are realistic.

Skip if the assignment is purely about controlled-LLM mechanics and the test suite already proves them. Run if the reviewer is likely to clone, set their own key, and re-run.

### W9. Final state check (~5 min)

```bash
git status                           # clean
mvn test                             # green
mvn -DskipTests package              # builds the jar
git log --oneline | head -20         # commit history makes sense
ls docs/                             # design trail complete
```

Tag the repo / open the PR / submit.

---

**Total post-implementation workflow: ~90 minutes** (or ~100 with the optional real-LLM run). Treat it as part of the project, not as extra work. The 3-hour core build is what you *did*; the 90-minute workflow is what makes it *reviewable*.
