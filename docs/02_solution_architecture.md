# AI Decline Code Mapper — Solution Architecture (v3.1)

**Status**: pre-implementation, post-arch-review, post-garbage-hardening, post-test-review.
**Changes from v2**: added `Bootstrap` init phase, `ParseOutcome` sealed interface in Stage 1 (garbage bypass), `ResponseSanitizer` sub-stage in Stage 3 (LLM-response syntactic hygiene). Defence layers expanded from 5 to 8.
**Changes from v3 (test-review tightening)**: Bootstrap rejects dangerously-broad regex patterns; `Random(42)` is documented as instance-level (never static); cache dir is constructor-injected, never a hardcoded path.
**Related artifacts**:
- Implementation prompt: [apm_decline_mapper_prompt.md](./apm_decline_mapper_prompt.md)
- Testing concept: [apm_decline_mapper_testing_concept.md](./apm_decline_mapper_testing_concept.md)
- Review protocol: [apm_decline_mapper_arch_review_prompt.md](./apm_decline_mapper_arch_review_prompt.md)
- Raw findings: [apm_decline_mapper_arch_review_findings.md](./apm_decline_mapper_arch_review_findings.md)
- Per-finding resolution: [apm_decline_mapper_arch_review_resolution.md](./apm_decline_mapper_arch_review_resolution.md)
- Date: 2026-05-30

---

## 1. High-level flow

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  BOOTSTRAP   env vars, ambiguity_patterns.yaml, regex compile        │
   │  fail-fast before any input read                                     │
   └──────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │                          run-level budget guard                       │
   │                  MAX_LLM_CALLS · MAX_TOKENS_PER_RUN                   │
   │              wraps every LLM call across all stages                   │
   └──────────────────────────────────────────────────────────────────────┘
                                       ▲
                                       │ enforces ceiling
                                       │
   raw vendor doc (text)               │
            │                          │
            ▼                          │
   ┌──────────────────┐                │
   │  Stage 1         │                │
   │  PARSER          │                │
   │                  │                │
   │  ┌────────────┐  │                │
   │  │ anchor scan│  │ regex          │
   │  │ (regex)    │  │                │
   │  └─────┬──────┘  │                │
   │        ▼         │                │
   │  ┌────────────┐  │                │
   │  │ section    │  │ state machine │
   │  │ walker     │  │ deterministic │
   │  └─────┬──────┘  │                │
   │        ▼         │                │
   │  ParseOutcome:   │                │
   │   ┌──────────────┼──▶ Ok            ──▶ continues to Stage 2
   │   ├──────────────┼──▶ AmbiguousChunk ──▶ LLM fallback ◀──┐
   │   └──────────────┼──▶ Garbage        ──▶ bypass to result  │
   │                  │     (unmapped + parse_garbage reason)   │
   └──────────────────┘                                          │
            │                                                    │
            ▼                                                    │
   ┌──────────────────┐                                          │
   │  Stage 2         │                                          │
   │  ENRICHER        │  deterministic                           │
   │                  │  builds system prompt:                   │
   │  - taxonomy      │  - 7 categories                          │
   │  - retry vocab   │  - 4 retry-strategy values               │
   │  - confidence    │  - operational rubric                    │
   │    rubric        │  - 2 neutral few-shots                   │
   │  - few-shots     │                                          │
   └────────┬─────────┘                                          │
            │                                                    │
            ▼                                                    │
   ┌──────────────────┐                                          │
   │  Stage 3         │                                          │
   │  MAPPER          │                                          │
   │                  │                                          │
   │  ┌────────────┐  │                                          │
   │  │ batch (5-10│  │ randomized within-batch                  │
   │  │ codes,     │  │ order (seed=42)                          │
   │  │ shuffled)  │  │                                          │
   │  └─────┬──────┘  │                                          │
   │        ▼         │                                          │
   │  ┌────────────┐  │                                          │
   │  │ cache check│──┼──▶ hit ──▶ skip LLM call                 │
   │  └─────┬──────┘  │                                          │
   │        │ miss    │                                          │
   │        ▼         │                                          │
   │  ┌────────────┐  │                                          │
   │  │ Anthropic  │──┼─── HTTP, tool_use, temp=0 ───────────────┘
   │  │ /messages  │  │
   │  └─────┬──────┘  │
   │        ▼         │
   │  ┌────────────┐  │
   │  │ Sanitizer  │  │ shape checks: tool_use present,
   │  │            │  │ JSON parseable, no unknown codes,
   │  │            │  │ no duplicate codes, stop_reason ok,
   │  │            │  │ enum strings trimmed/normalized
   │  └─────┬──────┘  │      │
   │        │         │      └─▶ re-prompt batch if any check fails
   │        ▼         │
   │  [Mapping]       │
   └────────┬─────────┘
            │
            ▼
   ┌──────────────────┐
   │  Stage 4         │
   │  VALIDATOR       │  deterministic
   │                  │
   │  1. JSON+enum    │  Jackson refuses hallucinated values
   │  2. coverage     │  every input code present?
   │  3. vocabulary   │  belt-and-braces enum check
   │  4. hedge demo   │  HIGH+hedge → MEDIUM
   │  5. ambiguity    │  YAML patterns force LOW+review (sticky)
   │  6. re-validate  │  single-code pass for LOW codes
   │                  │       ──▶ back to Stage 3 (batch size 1)
   │  recovery cap=2  │       ──▶ exceeded ──▶ unmapped
   └────────┬─────────┘
            │
            ▼
       result.json
       (always written, partial allowed)
```

## 2. Stage details

### Bootstrap (init, before pipeline)

| Property | Value |
|---|---|
| Determinism | Fully deterministic |
| Input | Environment variables, `ambiguity_patterns.yaml` on classpath |
| Output | Validated `Config` object + compiled `Pattern` list |
| Failure mode | **Fatal — fail-fast before reading any input file**. Exit 1 with the offending env var or pattern listed. |

**Checks:**
- `ANTHROPIC_API_KEY` present and non-empty.
- `LLM_MODEL` defaults applied if unset; warning to stderr.
- `MAX_LLM_CALLS`, `MAX_TOKENS_PER_RUN` parse as positive integers.
- `ambiguity_patterns.yaml` exists on classpath; YAML parses; every `match` value compiles as a `Pattern`. All bad patterns listed in the error message — not just the first.
- **No pattern is "dangerously broad"**: rejects empty `match`, bare `.*`, `.+`, `(?i).*`, or anything that matches every conceivable provider_message. Smoke-check by matching the pattern against a curated set of clearly-unambiguous messages ("Insufficient balance", "Card expired") — if any of them matches, the pattern is rejected as too broad.

**Why before Stage 1.** A bad regex in `ambiguity_patterns.yaml` discovered mid-pipeline wastes the entire LLM bill of that run. Bootstrap catches it for free.

### Stage 1 — Parser

| Property | Value |
|---|---|
| Determinism | **Deterministic-first**, LLM only as fallback on flagged chunks |
| Input | Raw text (vendor doc) |
| Output | `List<ParseOutcome>` — sealed: `Ok` / `AmbiguousChunk` / `Garbage` |
| LLM calls | 0 in the happy path; 1 per `AmbiguousChunk`; 0 for `Garbage` (bypasses Mapper entirely) |
| Failure mode | Mismatch between anchor count and parser count → **warning to stderr**, not an error |

**`ParseOutcome` sealed interface** (the key new contract):
- `Ok(ProviderError error)` — clean record, proceeds to Stage 2/3.
- `AmbiguousChunk(String text)` — chunk that defies the state machine (no quoted message, nested code refs, cross-section bleed). Sent to LLM fallback.
- `Garbage(String code, GarbageKind kind, String detail)` — known-bad input. **Never sent to LLM.** Goes directly into the final result as `unmapped` with `review_reason="parse_garbage: <kind>: <detail>"`.

**`GarbageKind`:**
- `NO_MESSAGE` — code with no quoted message after it.
- `DUPLICATE_CONFLICT` — same code appears twice with **different** descriptions.
- `DESCRIPTION_TOO_LONG` — description > 5000 chars (truncated; included as garbage to surface the anomaly).
- `NON_UTF8` — file isn't valid UTF-8.
- `EMPTY_INPUT` — file is empty or whitespace-only.

**Why `Garbage` matters.** Without this typing, every bad input either crashes the pipeline or burns LLM tokens trying to "make sense of it." With it, the bad inputs are surfaced in the output JSON for the operator to see, without any LLM cost.

### Stage 2 — Enricher

| Property | Value |
|---|---|
| Determinism | Fully deterministic |
| Input | `List<ProviderError>` (only `Ok` outcomes) + static taxonomy + `ambiguity_patterns.yaml` |
| Output | System prompt string for the Mapper |
| LLM calls | 0 |

Output prompt structure (in order):
1. Taxonomy table (7 categories with descriptions and retry policy)
2. Retry-strategy vocabulary (4 values)
3. Operational confidence rubric (high/medium/low defined as checklists)
4. 2 synthetic neutral few-shots (`XX-001`, `YY-042`) — no QuickPay anchors
5. Hard instruction: return via `tool_use`, no invented categories, follow the rubric

### Stage 3 — Mapper (LLM + Sanitizer sub-stage)

| Property | Value |
|---|---|
| Determinism | LLM (controlled via `tool_use` schema + `temperature=0`) + deterministic sanitizer |
| Input | Batch of 5–10 `ProviderError` + system prompt from Stage 2 |
| Output | `List<Mapping>` per batch (sanitized) |
| LLM calls | `ceil(n_codes / batch_size)` for the main pass + 1 per re-validated code + 1 per re-prompt on sanitizer failure |
| Cache | `sha256(model + prompt_template_version + canonical_batch_input)` → `logs/cache/<hash>.json` |
| Logging | Every request/response (raw + sanitized) appended to `logs/run-{ts}.jsonl` |

**Cross-code contamination mitigations:**
- Within-batch order randomized with a fixed seed. **`Random(42)` is held as a per-instance field of `LlmMapper`, never a static** — so two `LlmMapper` instances in parallel tests don't share state, and execution order can't shift the random draw a test sees.
- Single-code re-validation pass for codes flagged LOW by the Validator (batch size 1).

**Cache directory** is a **constructor argument** of `LlmMapper`, not a hardcoded path. Production passes `logs/cache/`; every test passes `@TempDir`. This is enforced by a test that walks the working directory after a pipeline run and asserts no `logs/cache/` was written outside the temp dir.

**Sanitizer sub-stage** — runs on every raw `LlmResponse` before it reaches Stage 4:

| Check | Failure action |
|---|---|
| `tool_use` block present with name `map_codes` | Re-prompt batch with "use the provided tool" |
| Tool input parses as JSON | Re-prompt batch |
| `stop_reason == "end_turn"` (not `max_tokens`) | Drop incomplete entries, re-prompt only missing codes |
| Every returned code ∈ batch input | Strip unknown codes, log warning |
| No duplicate codes in response | Keep first occurrence, log warning |
| `confidence` and `retry_strategy` strings trimmed and case-normalized before enum parse | Normalization step, not a failure |
| Required fields non-null | Re-prompt batch |

**Why a separate sub-stage.** Validator (Stage 4) deals with **semantic** validity — is the chosen category sensible? Sanitizer deals with **syntactic** validity — is this even a parseable response? Two different concerns, two different test surfaces.

### Stage 4 — Validator + recovery

| Property | Value |
|---|---|
| Determinism | Fully deterministic — no LLM in decision logic |
| Input | Sanitized `List<Mapping>` + set of expected codes + `ambiguity_patterns.yaml` |
| Output | Validated `List<Mapping>` or list of codes to re-prompt |
| LLM calls | 0 (re-prompt requests go back into Stage 3) |
| Recovery cap | 2 re-prompts per code; then `unmapped` |

**Six checks, in order:**
1. JSON parses; enums deserialize (Jackson refuses unknown values before validator even runs).
2. Coverage: every input code present in output.
3. Vocabulary: explicit enum check (redundant with 1, kept for readable errors).
4. Hedge-word demotion: `HIGH` + hedge regex match → `MEDIUM`.
5. **Ambiguity patterns** (the central defence): YAML match on `provider_message` → forces `LOW + review`, regardless of LLM output.
6. Re-validation pass: every `LOW` code gets one batch-of-1 Mapper call. Result merges but `LOW` flag is **sticky** (re-validation refines reasoning, not confidence).

### Run-level budget guard

| Property | Value |
|---|---|
| Scope | Wraps every LLM call in Stages 1, 3, 4 |
| Ceilings | `MAX_LLM_CALLS=40`, `MAX_TOKENS_PER_RUN=200_000` |
| Behavior on exhaust | Stop calling LLM, mark remaining codes `unmapped` with `review_reason="budget_exhausted"`, write `result.json`, exit code 2 |

## 3. Defence layers — "controlled component" story

The design has **eight layers** between user input and the final output. Each layer catches a different class of failure. Reading top-to-bottom matches the data-flow order.

| # | Layer | Where | Mechanism | Catches |
|---|---|---|---|---|
| B0 | Bootstrap | Init | env validated, YAML loaded, regex compiled | Misconfiguration, broken patterns — **before** input is read |
| P1 | Parse outcome | Stage 1 | `ParseOutcome.Garbage` bypasses Mapper | Empty file, binary input, truncated docs, duplicate-conflict codes, encoding issues |
| R1 | Response sanitizer | Stage 3 | Shape-checks raw `LlmResponse` | Malformed JSON, missing tool_use, truncated `max_tokens` response, unknown/duplicate codes in response |
| V1 | Schema/enum | Stage 4 | `tool_use` JSON schema + Java `enum` deserialization | Hallucinated category strings, hallucinated retry strategies |
| V2 | Coverage | Stage 4 | Expected ↔ output code-set diff | Skipped codes |
| V3 | Hedge demotion | Stage 4 | Regex on `reasoning` text + `confidence == HIGH` | Self-inconsistent confidence (LLM hedges but claims high) |
| V4 | Ambiguity patterns | Stage 4 | YAML substring/regex match on `provider_message` | Known-ambiguous codes the LLM categorized confidently anyway |
| V5 | Re-validation pass | Stage 4 | Single-code Mapper call with explicit ambiguous-flag | Cross-code contamination from batching |

**V4 is still the load-bearing layer for the assignment's headline requirement** — it's the only layer that grades the LLM **independently** of the LLM's own output.

**P1 and R1 are the "garbage in, garbage out" defences** added in v3. Without them, every malformed input or LLM-response edge case either burns tokens or crashes the run.

## 4. Data contracts

```java
// Inputs / intermediates

sealed interface ParseOutcome
        permits ParseOutcome.Ok, ParseOutcome.AmbiguousChunk, ParseOutcome.Garbage {

    record Ok(ProviderError error) implements ParseOutcome {}
    record AmbiguousChunk(String text) implements ParseOutcome {}
    record Garbage(String code, GarbageKind kind, String detail) implements ParseOutcome {}
}

enum GarbageKind {
    NO_MESSAGE,           // code with no quoted message
    DUPLICATE_CONFLICT,   // same code, different descriptions
    DESCRIPTION_TOO_LONG, // > 5000 chars
    NON_UTF8,             // file not valid UTF-8
    EMPTY_INPUT           // 0 codes found
}

record ProviderError(
    String code,            // "QP-006"
    String message,         // "Suspected fraud"
    String description,     // "The transaction was flagged..."
    String section,         // "Transaction Processing Errors"
    ParsePath parsePath     // STATE_MACHINE | LLM_FALLBACK
) {}

enum Category {
    SYSTEM_MALFUNCTION, COMMON_DECLINE, ANTIFRAUD,
    BAD_DATA_PROVIDED, CANCELLED_BY_CUSTOMER,
    PROVIDER_LIMIT, AUTHENTICATION_FAILURE
}

enum RetryStrategy {
    NO_RETRY, RETRY_WITH_BACKOFF, RETRY_AFTER_FIX, NO_ACTION
}

enum Confidence { HIGH, MEDIUM, LOW }

// Sanitizer output

sealed interface SanitizationResult
        permits SanitizationResult.Clean, SanitizationResult.NeedsReprompt {

    record Clean(List<Mapping> mappings) implements SanitizationResult {}
    record NeedsReprompt(String reason, Set<String> codesToRetry) implements SanitizationResult {}
}

// Output

record Mapping(
    String providerCode,
    String providerMessage,
    Category internalCategory,        // nullable only when unmapped
    Confidence confidence,
    String reasoning,
    RetryStrategy retryStrategy,
    boolean needsHumanReview,
    String reviewReason               // nullable
) {}

record Summary(
    int totalCodes,
    int mapped,
    int highConfidence,
    int needsReview,
    int unmapped,
    boolean budgetExhausted,
    int llmCalls,
    int tokensUsed
) {}

record MappingResult(
    String provider,
    String version,
    List<Mapping> mappings,
    Summary summary
) {}
```

## 5. File layout

```
apm-decline-mapper/
├── README.md
├── .env.example                 ANTHROPIC_API_KEY, LLM_MODEL,
│                                MAX_LLM_CALLS, MAX_TOKENS_PER_RUN,
│                                CACHE_ENABLED
├── .gitignore
├── pom.xml                      Java 21, Jackson, JUnit 5, SnakeYAML
│
├── src/main/java/com/bp/declinemapper/
│   ├── Main.java                String[] args entry; calls Bootstrap then Pipeline
│   ├── Bootstrap.java           env validation + YAML load + regex compile
│   ├── Pipeline.java            wires 4 stages + budget guard
│   │
│   ├── model/
│   │   ├── Category.java        enum (7)
│   │   ├── RetryStrategy.java   enum (4)
│   │   ├── Confidence.java      enum (3)
│   │   ├── GarbageKind.java     enum (5)
│   │   ├── ParsePath.java       enum (2)
│   │   ├── ParseOutcome.java    sealed interface + 3 records
│   │   ├── SanitizationResult.java  sealed interface + 2 records
│   │   ├── ProviderError.java   record
│   │   ├── Mapping.java         record
│   │   ├── Summary.java         record
│   │   └── MappingResult.java   record
│   │
│   ├── stage/
│   │   ├── Parser.java          state machine + LLM fallback + garbage detection
│   │   ├── Enricher.java        builds system prompt
│   │   ├── LlmMapper.java       batched + cached LLM calls
│   │   ├── ResponseSanitizer.java  shape-checks raw LlmResponse
│   │   └── Validator.java       6-check pipeline + recovery
│   │
│   ├── llm/
│   │   ├── LlmClient.java       interface
│   │   └── AnthropicLlmClient.java  HttpClient + JSONL log + cache
│   │
│   └── budget/
│       └── BudgetGuard.java     ceiling enforcement
│
├── src/main/resources/
│   ├── prompts/
│   │   ├── parser-fallback.md   only used by Stage 1 fallback
│   │   └── mapper-system.md     used by Stage 3 (composed by Enricher)
│   ├── taxonomy.md
│   └── ambiguity_patterns.yaml  load-bearing — V4 defence
│
├── src/test/java/com/bp/declinemapper/
│   ├── unit/                    pure tests, no mocks
│   ├── component/               with ScriptedLlmClient
│   ├── integration/             with RecordedLlmClient
│   └── fakes/                   ScriptedLlmClient, RecordedLlmClient
│
├── src/test/resources/
│   ├── fixtures/                test inputs (quickpay, fastbank, malformed, empty, single)
│   ├── expected/                gold-standard result.json files
│   └── llm_responses/           recorded LLM outputs (hash-keyed)
│
├── fixtures/                    production-mode inputs (same as test fixtures)
│   ├── quickpay_v2.4.txt
│   └── fastbank_v1.0.txt
│
├── samples/
│   └── quickpay_v2.4.result.json   committed reference output
│
└── logs/
    ├── .gitkeep
    ├── run-{ts}.jsonl              one JSON line per LLM call
    └── cache/                       sha256 → cached response
```

~13 Java production files, ~600 lines of code + ~50 lines of YAML/Markdown resources.

## 6. Configuration

| Env var | Default | Purpose | Bootstrap validation |
|---|---|---|---|
| `ANTHROPIC_API_KEY` | — (required) | Anthropic API auth | non-empty |
| `LLM_MODEL` | `claude-sonnet-4-5` | Model alias; resolved version logged per call | warns if unset |
| `MAX_LLM_CALLS` | `40` | Run-level call ceiling | positive int |
| `MAX_TOKENS_PER_RUN` | `200000` | Run-level token ceiling | positive int |
| `CACHE_ENABLED` | `true` | Toggle response cache | parseable boolean |
| `RECORD_MODE` | `false` | Test-only: have `RecordedLlmClient` capture from real LLM | parseable boolean |

## 7. Failure handling matrix

| Failure | Detected at | Action |
|---|---|---|
| **Bootstrap failures** | | |
| `ANTHROPIC_API_KEY` missing | Bootstrap | Exit 1 with explicit message |
| `ambiguity_patterns.yaml` not on classpath | Bootstrap | Exit 1, fatal |
| Bad regex in YAML | Bootstrap | Exit 1, **all** bad patterns listed |
| Dangerously-broad regex in YAML (`.*`, empty, etc.) | Bootstrap | Exit 1 with "pattern is dangerously broad: <pattern>" |
| `MAX_LLM_CALLS` not parseable / non-positive | Bootstrap | Exit 1, fatal |
| **Input garbage (Stage 1)** | | |
| Empty file | Parser | `ParseOutcome.Garbage(EMPTY_INPUT)` → `total_codes=0`, exit 0 |
| Non-UTF-8 bytes | Parser | `Garbage(NON_UTF8)` → exit 2, `result.json` written |
| Code with no quoted message | Parser | `Garbage(NO_MESSAGE)` for that code, others processed |
| Same code, conflicting descriptions | Parser | Both occurrences → `Garbage(DUPLICATE_CONFLICT)`, both visible in result |
| Description > 5000 chars | Parser | Truncated + `Garbage(DESCRIPTION_TOO_LONG)` to surface anomaly |
| Anchor scan misses codes | Stage 1 cross-check | Warning to stderr, continue |
| State machine flags chunk ambiguous | Stage 1 | LLM fallback for that chunk |
| **LLM response garbage (Stage 3 sanitizer)** | | |
| Malformed JSON in tool_use input | Sanitizer | Re-prompt batch |
| Tool_use block missing entirely | Sanitizer | Re-prompt batch |
| Wrong tool name (config bug) | Sanitizer | Fatal — not the LLM's fault |
| `stop_reason: max_tokens` (truncated) | Sanitizer | Drop incomplete entries, re-prompt missing codes |
| Mapping for unknown code | Sanitizer | Strip, log warning |
| Duplicate code in response | Sanitizer | Keep first, log warning |
| Required field null | Sanitizer | Re-prompt batch |
| **Semantic validator failures (Stage 4)** | | |
| Hallucinated category in LLM output | V1 (Jackson) or explicit V1 check | Re-prompt that code |
| Skipped code in batch | V2 | Re-prompt missing codes |
| `HIGH` confidence + hedge in reasoning | V3 | Demote to `MEDIUM`, log |
| `provider_message` matches ambiguity pattern | V4 | Force `LOW + review`, ignore LLM's claim |
| Confidence still `LOW` after main pass | V5 | Single-code re-validation; result merges but V4 flags sticky |
| Per-code re-prompt cap (2) exceeded | Stage 4 | Mark `unmapped`, `review_reason="recovery_exhausted"` |
| **Budget exhaustion** | | |
| `MAX_LLM_CALLS` or `MAX_TOKENS_PER_RUN` hit | Budget guard | Stop LLM calls, mark remaining `unmapped`, `summary.budget_exhausted=true`, write `result.json`, exit 2 |
| **Transport-level** | | |
| Anthropic 429 / 5xx | `AnthropicLlmClient` | Retry with backoff (3 attempts), then surface as recovery exhaustion |
| Anthropic 4xx (bad request) | `AnthropicLlmClient` | Surface as fatal — no point retrying |
| **State garbage** | | |
| Cache file corrupted (not JSON) | `AnthropicLlmClient` | Warning, call LLM, overwrite cache file |
| **Last resort** | | |
| Total crash | OS | `result.json` not written (only fatal mode — documented limitation) |

## 8. Exit codes

| Code | Meaning |
|---|---|
| `0` | All codes mapped, no `needs_review`, no `unmapped` |
| `1` | Bootstrap failure (config/state problem) **or** all codes mapped but at least one `needs_human_review = true` |
| `2` | At least one code `unmapped` (recovery exhausted, budget hit, or parse_garbage) |

Distinguishing exit 1 (bootstrap) from exit 1 (needs_review) by stderr: bootstrap failures print a structured "BOOTSTRAP_ERROR: …" line before any other output; needs_review runs do not.
