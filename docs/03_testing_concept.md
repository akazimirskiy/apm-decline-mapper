# AI Decline Code Mapper — Testing Concept (rev 2)

**Goal**: full coverage of the 4-stage pipeline and all 8 defence layers **without burning LLM tokens**. CI never calls the real Anthropic API. The real API is touched at most once, manually and optionally, when bootstrapping the `RecordedLlmClient` mode.

**Revision**: rev 2 — incorporates findings from `apm_decline_mapper_test_review_findings.md`.

**Related artifacts**:
- Solution architecture (v3.1): [apm_decline_mapper_solution_architecture.md](./apm_decline_mapper_solution_architecture.md)
- Implementation prompt: [apm_decline_mapper_prompt.md](./apm_decline_mapper_prompt.md)
- Date: 2026-05-30

---

## 1. Pyramid

```
                  ┌─────────────────────────────────┐
                  │  Integration E2E  (3 tests)     │  FixtureLlmClient
                  │  pipeline.run(quickpay) =       │  reads canned
                  │   expected (structural pin)     │  responses by
                  │   pipeline.run(messy_provider)  │  (provider, batch)
                  ├─────────────────────────────────┤
                  │  Component  (~22 tests)         │  ScriptedLlmClient
                  │  Mapper batches, Sanitizer,     │  programmable
                  │  V5 re-validation, Budget,      │  response queue
                  │  Chaos test, Cache leakage      │
                  ├─────────────────────────────────┤
                  │  Unit  (~43 tests)              │  no mocks at all
                  │  state machine, regex, YAML,    │  pure functions,
                  │  validator checks, ambiguity    │  fast, free
                  │  patterns, named QP codes,      │
                  │  garbage detection,             │
                  │  Random reproducibility         │
                  └─────────────────────────────────┘
```

**Total: ~68 tests.** Run time: under 10 seconds. Zero LLM tokens.

Every numbered test below is tagged `[unit]`, `[component]`, or `[integration]`. Scenario numbers are docs-only — they never appear in code.

## 2. Test naming convention

`<defence_layer_or_stage>_<expected_behaviour>_when_<condition>` — sentence-form, lowerCamel.

Good:
- `v4_forcesLowReview_forQp008_doNotHonor`
- `parser_emitsGarbage_whenInputNonUtf8`
- `mapper_makesOneCall_whenBatchSizeExact`

Bad:
- `test19`
- `validatorTestAmbiguous`
- `testCase_QP008_E2E_GoldStandard_v3`

Numeric scenario IDs in this document are for cross-referencing only.

## 3. Mock seam — single point of injection

Production and tests diverge at one interface:

```java
public interface LlmClient {
    LlmResponse call(LlmRequest request) throws IOException;
    int lastCallTokensIn();
    int lastCallTokensOut();
}
```

| Implementation | Used by | Behaviour |
|---|---|---|
| `AnthropicLlmClient` | Production | Real HTTP to Anthropic API |
| `ScriptedLlmClient` | Component tests | Programmable queue of canned responses |
| `FixtureLlmClient` | **e2e tests, default** | File-backed responses keyed by `(provider, batchIndex)` — hand-authorable, works on day one |
| `RecordedLlmClient` | e2e tests, optional | File-backed responses keyed by `sha256(canonical(request))` — requires one-time real LLM run to populate |

Nothing else needs mocking. Filesystem uses `@TempDir`. Time is irrelevant. Random uses **per-instance** `Random(42)`.

### Why two e2e clients

The original design used hash-keyed recordings. Test-review found this can't bootstrap without an API key (you can't compute the hash without running canonicalization code that doesn't exist yet). Two paths:

- **`FixtureLlmClient` (default)**: filename = `{provider}_batch{N}.json`. Hand-author from day one. Prompt edits do **not** invalidate the fixture mapping. Trade-off: prompt drift can silently change real LLM output without the fixture noticing — accepted because we're testing the pipeline's *control* over the LLM, not the LLM's accuracy.
- **`RecordedLlmClient` (optional)**: keeps the hash-keyed cache-style mode. Useful when a developer wants to validate end-to-end against the real model. Requires `RECORD_MODE=true` + API key on first run.

### `ScriptedLlmClient`

```java
public class ScriptedLlmClient implements LlmClient {
    private final Queue<LlmResponse> responses = new ArrayDeque<>();
    private final List<LlmRequest> received = new ArrayList<>();

    public ScriptedLlmClient enqueue(LlmResponse r) { responses.add(r); return this; }
    public ScriptedLlmClient enqueueHttpError(int status) { ... return this; }
    public ScriptedLlmClient enqueueRepeating(LlmResponse r) {
        // for chaos test: same response over and over
        ...
    }

    @Override
    public LlmResponse call(LlmRequest request) {
        received.add(request);
        if (responses.isEmpty()) {
            throw new AssertionError("No more scripted responses. Got: " + request);
        }
        return responses.poll();
    }

    public List<LlmRequest> received() { return received; }
    public int callCount() { return received.size(); }
}
```

### `FixtureLlmClient`

```java
public class FixtureLlmClient implements LlmClient {
    private final Path fixturesDir;
    private final String providerKey;  // e.g. "quickpay_v2.4"
    private int batchCounter = 0;

    @Override
    public LlmResponse call(LlmRequest request) throws IOException {
        Path file = fixturesDir.resolve(
            providerKey + "_batch" + batchCounter + ".json");
        batchCounter++;

        if (!Files.exists(file)) {
            throw new AssertionError(
                "Missing fixture: " + file + ". "
              + "Either author it by hand or run with RecordedLlmClient + RECORD_MODE=true.");
        }
        return mapper.readValue(file.toFile(), LlmResponse.class);
    }
}
```

Sequential by batch index — assumes pipeline issues batches in deterministic order (it does, with seeded `Random(42)` per `LlmMapper` instance).

### `RecordedLlmClient` (kept as optional)

```java
public class RecordedLlmClient implements LlmClient {
    private final Path recordingsDir;
    private final boolean recordMode;
    private final LlmClient realClient;  // only used when recordMode=true

    @Override
    public LlmResponse call(LlmRequest request) throws IOException {
        String hash = sha256(canonical(request));
        Path file = recordingsDir.resolve(hash + ".json");

        if (Files.exists(file)) {
            return mapper.readValue(file.toFile(), LlmResponse.class);
        }
        if (recordMode) {
            LlmResponse r = realClient.call(request);
            Files.writeString(file, mapper.writeValueAsString(r));
            return r;
        }
        throw new AssertionError(
            "No recording for " + hash + ". "
          + "Run with RECORD_MODE=true to capture (requires API key).");
    }
}
```

## 4. Test scenarios — full matrix

### Stage 1 — Parser

| # | Scenario | Level |
|---|---|---|
| 1 | QuickPay format, 24 codes parsed by state machine, 0 LLM calls | [unit] |
| 2 | Multi-paragraph description flagged ambiguous, sent to LLM fallback | [component] |
| 3 | Snake_case code `ERR_RATE_LIMIT` matched by extended anchor | [unit] |
| 4 | Numeric-only code `05` near keyword `code:` → matched | [unit] |
| 5 | Anchor count > parser count → stderr warning, no error | [unit] |

### Stage 1 — input garbage (`ParseOutcome.Garbage`)

| # | Scenario | Level |
|---|---|---|
| 30 | Empty file → `Garbage(EMPTY_INPUT)`, exit 0, valid JSON | [unit] |
| 31 | Non-UTF-8 bytes → `Garbage(NON_UTF8)`, exit 2, `result.json` written | [integration] |
| 32 | Code with no quoted message → `Garbage(NO_MESSAGE)` for that code, others succeed | [unit] |
| 33 | Duplicate identical → dedup to one `Ok`, stderr warning | [unit] |
| 34 | `DUPLICATE_CONFLICT` (different descriptions) → both as `Garbage` | [unit] |
| 35 | Code-like string inside another code's description → no duplicate emitted | [unit] |
| 36 | `DESCRIPTION_TOO_LONG` → truncated, garbage entry surfaces anomaly | [unit] |
| 37 | Mixed line endings (CRLF + LF + CR) → parser normalizes | [unit] |

### Stage 2 — Enricher

| # | Scenario | Level |
|---|---|---|
| 6 | System prompt contains all 7 categories | [unit] |
| 7 | System prompt contains the operational confidence rubric verbatim | [unit] |
| 8 | System prompt contains no QP-coded examples (no QuickPay anchoring) — regex `\bQP-\d+\b` returns no match | [unit] |

### Stage 3 — Mapper

| # | Scenario | Level |
|---|---|---|
| 9 | 5 codes → 1 LLM call (single batch) | [component] |
| 10 | Within-batch order randomized; deterministic with seed=42 (run twice → identical requests) | [component] |
| 11 | Cache hit → 0 LLM calls | [component] |
| 12 | Cache miss → cache file written at the configured (constructor-arg) path | [component] |
| 13 | HTTP 502 → retry → succeeds | [component] |
| 14 | HTTP 400 → fatal, no retry | [component] |
| 27 | Single-code batch (n=1) doesn't crash | [component] |
| **50** | **Batch boundary: n = batchSize exactly → 1 call** | [component] |
| **51** | **Batch boundary: n = batchSize+1 → 2 calls, sizes (batchSize, 1)** | [component] |
| **52** | **Batch boundary: n = 2·batchSize → 2 calls, sizes (batchSize, batchSize). Union of received covers each code exactly once** | [component] |
| **53** | **`Random(42)` is per-instance, never static — two `LlmMapper` instances run shuffle independently and reproducibly** | [unit] |
| **54** | **Parallel `LlmMapper` instances on two threads → each independently reproducible** | [component] |
| **55** | **Cache dir leakage guard: after pipeline run with `@TempDir`, walk working dir, assert no `logs/cache/` created outside temp** | [component] |

### Stage 3 — Sanitizer (R1 defence)

| # | Scenario | Level |
|---|---|---|
| 38 | Invalid JSON in tool_use input → re-prompt batch | [unit] |
| 39 | No tool_use block, text reply only → re-prompt with "use the tool" | [unit] |
| 40 | Empty `mappings` array → re-prompt batch | [unit] |
| 41 | Response includes unknown code (e.g. `QP-999`) → strip, log warning | [unit] |
| 42 | Same code twice in response → keep first, log warning | [unit] |
| 43 | `confidence: "HIGH "` (trailing space) → normalized, parses correctly | [unit] |
| 44 | `stop_reason: max_tokens`, last mapping truncated → drop incomplete, re-prompt missing | [unit] |
| 45 | Wrong tool name → fatal (config bug, not LLM bug) | [unit] |

### Stage 4 — Validator (defence layers, unit-level)

| # | Defence | Scenario | Level |
|---|---|---|---|
| 15 | V1 | Jackson rejects `internal_category: "FRAUD"` at deserialization | [unit] |
| 16 | V2 | Missing code → re-prompt request emitted for that code only | [unit] |
| 17 | V3 | `HIGH` + "could be" in reasoning → demoted to `MEDIUM` | [unit] |
| 18 | V4 | `provider_message: "Do not honor"` + LLM `HIGH/ANTIFRAUD` → forced `LOW + review` | [unit] |
| **18a** | **V4 by name** | `v4_forcesLowReview_forQp005_transactionNotPermitted` | [unit] |
| **18b** | **V4 by name** | `v4_forcesLowReview_forQp008_doNotHonor` | [unit] |
| **18c** | **V4 by name** | `v4_forcesLowReview_forQp009_duplicateTransaction` (also asserts `retry_strategy: no_action`) | [unit] |
| **18d** | **V4 by name** | `v4_forcesLowReview_forQp401_sessionExpired` | [unit] |
| **18e** | **V3 vs V4 precedence** | `HIGH + "could be" + provider_message="Do not honor"` → final `LOW` (V4 wins), not `MEDIUM` | [unit] |
| 19a | V4 extensibility | YAML pattern `(?i)` matches uppercased message | [unit] |
| 19b | V4 extensibility | YAML `reason` field appears verbatim in `review_reason` after `known_ambiguous_pattern: ` prefix | [unit] |
| 19c | V4 extensibility | When two patterns both match, **first match wins** — pin this as the contract | [unit] |
| 21 | V5 sticky | Re-validation returns `HIGH` for V4-flagged code → stays `LOW` | [unit] |
| 22 | Recovery | 3rd attempt → `unmapped + recovery_exhausted` | [component] |

### Stage 4 — V5 re-validation (component)

| # | Scenario | Level |
|---|---|---|
| 20 | Single `LOW` code → exactly 1 batch-of-1 Mapper call | [component] |

### Chaos test — the headline "LLM is controlled" proof

| # | Scenario | Level |
|---|---|---|
| **56** | **`ScriptedLlmClient` returns `ANTIFRAUD/HIGH/no-hedge` for every code in QuickPay. Assert that the 4 ambiguous codes (QP-005, QP-008, QP-009, QP-401) still come out `LOW + review:true` (V4 catches them) and that `recovery_exhausted` fires for the remainder where appropriate.** | [component] |

This is the **single most important test** in the suite. It proves the defence layers actually control the LLM, not just sit politely beside it.

### Budget guard (component)

| # | Scenario | Level |
|---|---|---|
| 23 | `MAX_LLM_CALLS=2`, need 5 → 2 mapped, 3 unmapped, exit 2 | [component] |
| 24 | `MAX_TOKENS_PER_RUN` hit mid-run → remainder unmapped | [component] |
| 25 | Budget exhausted → **`result.json` still written** | [component] |
| **57** | **Cache hits do NOT count against `MAX_LLM_CALLS`. Pre-populate cache for all batches, set `MAX_LLM_CALLS=0`, run → `summary.llm_calls == 0`, `budget_exhausted == false`, every code mapped** | [component] |

### Bootstrap / state garbage (unit)

| # | Scenario | Level |
|---|---|---|
| 46 | YAML with invalid regex `*invalid(` → exit 1, error lists all bad patterns | [unit] |
| **46a** | **YAML with overly-broad regex (`.*`, empty, `(?i).*`) → exit 1, "pattern is dangerously broad"** | [unit] |
| 47 | Cache file = `{not json` → stderr warning, LLM called, file overwritten | [component] |
| 48 | `MAX_LLM_CALLS=-1` → exit 1 with "must be > 0" | [unit] |
| 49 | `ANTHROPIC_API_KEY=` (empty) → exit 1 before parsing input | [unit] |
| **49a** | **`RecordedLlmClient.call()` for a hash not on disk in non-record mode → AssertionError mentioning `RECORD_MODE=true`** | [unit] |

### Edge cases (unit)

| # | Scenario | Level |
|---|---|---|
| 26 | Empty `mappings` list out of Stage 1 (everything was Garbage) → valid JSON, exit 2 | [unit] |

### End-to-end (integration)

| # | Scenario | Level |
|---|---|---|
| 28 | `fixtures/quickpay_v2.4.txt` (24 codes) → matches `expected/quickpay.result.json` **structurally** (see §6); explicit assert: `reviewedCodes.containsExactlyInAnyOrder("QP-005","QP-008","QP-009","QP-401")` | [integration] |
| 29 | `fixtures/fastbank_v1.0.txt` (8 codes) → matches `expected/fastbank.result.json` structurally | [integration] |
| **58** | **`fixtures/messy_provider.txt` → matches `expected/messy_provider.result.json` structurally** | [integration] |

## 5. Tagged total

| Level | Count |
|---|---|
| Unit | 43 |
| Component | 22 |
| Integration | 3 |
| **Total** | **68** |

## 6. Gold-file assertion strategy — split structural / content

E2e tests **no longer assert byte-for-byte** against the gold file. Two separate assertions:

### Structural (pinned, fails on real bugs)
- Every input code appears in `mappings` (no skipped, no duplicate).
- For each code: `internal_category` matches gold exactly.
- For each code: `retry_strategy` matches gold exactly.
- For each code: `confidence` matches gold exactly.
- For each code: `needs_human_review` matches gold exactly.
- `summary.total_codes` matches gold.
- `summary.mapped` matches gold.
- `summary.needs_review` matches gold.
- `summary.unmapped` matches gold.
- `summary.budget_exhausted` matches gold.

### Content (presence-only, tolerates LLM drift)
- For each code: `reasoning` is non-empty and contains no obvious garbage.
- For each code marked `needs_human_review: true`: `review_reason` starts with `known_ambiguous_pattern:` (V4) or is `recovery_exhausted` / `budget_exhausted` / `parse_garbage:` (other paths).
- `summary.llm_calls` is a positive integer (or 0 if cache-only).
- `summary.tokens_used` is a positive integer (or 0 if cache-only).

PR reviewers reviewing a changed `expected/*.result.json` only need to read the structural pins; the prose is non-load-bearing.

## 7. Test data

### Fixture 1 — `quickpay_v2.4.txt`

Copy-paste of the assignment PDF. 24 codes in 5 sections.

### Fixture 2 — `fastbank_v1.0.txt` (clean second provider)

```
FastBank Payment Gateway — Error Codes (v1.0)

All responses include an `error_code` field and an `error_message` field.

== Decline Errors ==

FB-001 "Insufficient funds"
    The customer's available balance is below the transaction amount.

FB-002 "do_not_honor"
    Generic decline from issuing bank. Reason not specified.

== System Errors ==

05 "Pickup card"
    Card flagged by issuer network; do not process further. error_code: 05

ERR_RATE_LIMIT "API rate limit"
    Too many requests in the last 60 seconds. Retry after the Retry-After header.

== Validation ==

FB-V01 "Currency not supported"
    The supplied currency code is not on the merchant's enabled list.

== Customer ==

FB-C01 "User abandoned checkout"
    Customer closed the payment window before submitting.

FB-C02 "Duplicate charge attempt"
    A charge with the same idempotency_key already exists. May indicate replay
    attack or legitimate retry by the merchant.

== Auth ==

FB-A01 "Bearer token expired"
    The OAuth access token used for this request is no longer valid.
```

### Fixture 3 — `malformed_provider.txt` (parser edge cases)

```
=== Mixed ===

NOMSG-001
    A code without a quoted message.

CROSSREF-001 "See related codes"
    Same as NOMSG-001 but with nested reference to CROSSREF-002 in the
    middle of the description, which spans
    multiple paragraphs.

    Second paragraph of the description.
```

### Fixture 4 — `empty.txt`

Zero bytes.

### Fixture 5 — `single_code.txt`

```
SOLO-001 "Only one code here"
    Testing batch size = 1 edge case.
```

### Fixture 6 — `messy_provider.txt` (adversarial, real-world-shaped) **[new in rev 2]**

```
MessyPay Gateway — error reference v0.9 (draft)

Note: codes are documented inconsistently across departments. Engineering is aware.

| Code   | Message                | Description                                  |
|--------|------------------------|----------------------------------------------|
| MP-100 | "Card declined"        | Acquirer declined for unspecified reason.    |
| MP-101 | "Invalid PAN"          | Card number failed validation.               |

== auth ==

	MP-200	"Token mismatch"
	The bearer token did not match the merchant on file. See section 4.2.

== transactions ==

MP-300 "Duplicate transaction"
   Same idempotency_key already processed. Possibly a replay attack — flag for review.

Footnote: codes MP-401 and MP-402 indicate customer-initiated cancellation:
  MP-401 "User cancelled"     Customer pressed Cancel on the hosted page.
  MP-402 "Session ended"      Customer's session ended without completing payment.
```

Adversarial features intentionally included:
- Markdown table for first two codes — parser must extract from table cells.
- Tab-indented description (MP-200) — parser must accept tabs.
- Smart quotes? Not in this draft; left as future fixture if needed.
- Lowercase section headers (`== auth ==`, `== transactions ==`).
- Footnote-style codes (MP-401, MP-402) embedded in prose with 2-space indent.
- Inline cross-reference (`See section 4.2`).

**Gold-standard for messy_provider** is committed and asserts structurally:
- All 7 codes (MP-100, MP-101, MP-200, MP-300, MP-401, MP-402) present **OR** at least 5 mapped + the rest as `Garbage`. Either outcome is acceptable as long as it's tested.
- MP-300 → `LOW + review:true` (ambiguity pattern: "Duplicate").
- MP-401, MP-402 → `CANCELLED_BY_CUSTOMER`.

### Expected outputs — gold standard

`tests/expected/quickpay_v2.4.result.json` — full 24 mappings. Below is a 5-code excerpt of the most-illustrative entries; the rest follow the same shape.

```json
{
  "provider": "QuickPay Global",
  "version": "2.4",
  "mappings": [
    {
      "provider_code": "QP-002",
      "provider_message": "Insufficient balance",
      "internal_category": "COMMON_DECLINE",
      "confidence": "high",
      "reasoning": "<presence-only>",
      "retry_strategy": "no_retry",
      "needs_human_review": false,
      "review_reason": null
    },
    {
      "provider_code": "QP-006",
      "provider_message": "Suspected fraud",
      "internal_category": "ANTIFRAUD",
      "confidence": "high",
      "reasoning": "<presence-only>",
      "retry_strategy": "no_retry",
      "needs_human_review": false,
      "review_reason": null
    },
    {
      "provider_code": "QP-008",
      "provider_message": "Do not honor",
      "internal_category": "COMMON_DECLINE",
      "confidence": "low",
      "reasoning": "<presence-only>",
      "retry_strategy": "no_retry",
      "needs_human_review": true,
      "review_reason": "known_ambiguous_pattern: Issuer catch-all — could be antifraud, limit, or generic decline."
    },
    {
      "provider_code": "QP-009",
      "provider_message": "Duplicate transaction",
      "internal_category": "COMMON_DECLINE",
      "confidence": "low",
      "reasoning": "<presence-only>",
      "retry_strategy": "no_action",
      "needs_human_review": true,
      "review_reason": "known_ambiguous_pattern: Could be legitimate idempotency or replay attack."
    },
    {
      "provider_code": "QP-103",
      "provider_message": "Rate limit exceeded",
      "internal_category": "PROVIDER_LIMIT",
      "confidence": "high",
      "reasoning": "<presence-only>",
      "retry_strategy": "retry_with_backoff",
      "needs_human_review": false,
      "review_reason": null
    }
  ],
  "summary": {
    "total_codes": 24,
    "mapped": 24,
    "high_confidence": 20,
    "needs_review": 4,
    "unmapped": 0,
    "budget_exhausted": false,
    "llm_calls": "<presence-only positive>",
    "tokens_used": "<presence-only positive>"
  }
}
```

The `<presence-only>` markers in the committed file are real string/integer placeholders the assertion ignores; structural pins (category, retry_strategy, confidence, needs_human_review, all summary integers except llm_calls/tokens_used) are exact-match.

**Debatable / borderline mappings** (documented in the gold for the operator):
- `QP-007 "3DS authentication failed"` → `COMMON_DECLINE` with `medium`.
- `QP-203 "Unsupported payment method"` → `BAD_DATA_PROVIDED` with `medium`.
- `QP-005`, `QP-008`, `QP-009`, `QP-401` → all `LOW + needs_human_review: true` (enforced by V4 plus the new by-name unit tests).

### Canned LLM responses — `FixtureLlmClient` layout

```
src/test/resources/llm_responses/
├── README.md                                  how (provider, batchIndex) keying works
├── quickpay_v2.4_batch0.json                  QP-001..QP-005
├── quickpay_v2.4_batch1.json                  QP-006..QP-010
├── quickpay_v2.4_batch2.json                  QP-100..QP-103
├── quickpay_v2.4_batch3.json                  QP-200..QP-204
├── quickpay_v2.4_batch4.json                  QP-300..QP-303
├── quickpay_v2.4_batch5.json                  QP-400..QP-402
├── quickpay_v2.4_batch6.json                  V5 re-validation for QP-005
├── quickpay_v2.4_batch7.json                  V5 re-validation for QP-008
├── quickpay_v2.4_batch8.json                  V5 re-validation for QP-009
├── quickpay_v2.4_batch9.json                  V5 re-validation for QP-401
├── fastbank_v1.0_batch0.json
├── fastbank_v1.0_batch1.json
├── fastbank_v1.0_batch2.json                  V5 re-validation for FB-002 (do_not_honor)
├── fastbank_v1.0_batch3.json                  V5 re-validation for FB-C02 (duplicate)
└── messy_provider_v0.9_batch0.json             ...
```

Each file ~50-100 lines of JSON. Hand-authored from day one. No API key required.

Sample:
```json
{
  "_meta": {
    "provider": "quickpay_v2.4",
    "batchIndex": 0,
    "covers": ["QP-001", "QP-002", "QP-003", "QP-004", "QP-005"],
    "hand_authored": true,
    "notes": "QP-005 deliberately returned as confident COMMON_DECLINE — exercise V4 override"
  },
  "id": "msg_test_001",
  "model": "claude-sonnet-4-5",
  "stop_reason": "end_turn",
  "content": [
    {
      "type": "tool_use",
      "name": "map_codes",
      "input": {
        "mappings": [
          { "provider_code": "QP-001", "internal_category": "COMMON_DECLINE",
            "confidence": "medium", "reasoning": "Generic decline...",
            "retry_strategy": "no_retry",
            "needs_human_review": false, "review_reason": null },
          ...
        ]
      }
    }
  ],
  "usage": { "input_tokens": 1200, "output_tokens": 600 }
}
```

## 8. Canary tests — sample code

The most-valuable tests are the ones where the LLM **deliberately misbehaves** and the validator catches it. Five concrete examples below.

### Test #18b — V4 overrides confidently-wrong LLM output on QP-008 by name

```java
@Test
void v4_forcesLowReview_forQp008_doNotHonor() {
    Mapping fromLlm = new Mapping(
        "QP-008", "Do not honor",
        Category.ANTIFRAUD,         // wrong
        Confidence.HIGH,             // confidently wrong
        "Banks use this code for fraud blocks.",
        RetryStrategy.NO_RETRY,
        false, null
    );

    Mapping result = validator.applyAmbiguityPatterns(List.of(fromLlm)).get(0);

    assertThat(result.confidence()).isEqualTo(Confidence.LOW);
    assertThat(result.needsHumanReview()).isTrue();
    assertThat(result.reviewReason())
        .startsWith("known_ambiguous_pattern: Issuer catch-all");
}
```

The same shape repeats for QP-005, QP-009, QP-401 — each pinned by code in the test name, so a YAML edit that breaks one of the four fails a named test, not just a JSON diff.

### Test #18e — V4 wins over V3 when both trigger

```java
@Test
void v4_winsOver_v3_whenBothTriggerOnSameCode() {
    // LLM hedges in prose ("could be...") AND the message is a known ambiguity
    Mapping fromLlm = new Mapping(
        "QP-008", "Do not honor",
        Category.ANTIFRAUD,
        Confidence.HIGH,
        "It could be a fraud block, or it might be something else.",  // V3 trigger
        RetryStrategy.NO_RETRY,
        false, null
    );

    Mapping result = validator.runAllChecks(List.of(fromLlm)).get(0);

    assertThat(result.confidence()).isEqualTo(Confidence.LOW);          // V4, not MEDIUM
    assertThat(result.needsHumanReview()).isTrue();                     // V4
    assertThat(result.reviewReason())
        .startsWith("known_ambiguous_pattern: ");                       // V4 wins
}
```

### Test #56 — chaos test, the headline "LLM is controlled" proof

```java
@Test
void chaos_alwaysAntifraudHigh_pipelineStillCorrectFor4AmbiguousCodes(@TempDir Path tmp) {
    // ScriptedLlmClient returns ANTIFRAUD/HIGH for every single code, no matter what
    var llm = ScriptedLlmClient.repeatingForever(allMapped(
        Category.ANTIFRAUD, Confidence.HIGH,
        "Confidently wrong reasoning, no hedge tokens.",
        RetryStrategy.NO_RETRY));

    var pipeline = buildPipeline(llm, tmp);
    Path out = tmp.resolve("result.json");

    pipeline.run(QUICKPAY_FIXTURE, "QuickPay", "2.4", out);

    MappingResult r = readJson(out);

    // The four named ambiguous codes must STILL be LOW + review,
    // because V4 doesn't care what the LLM said.
    Set<String> reviewedCodes = r.mappings().stream()
        .filter(Mapping::needsHumanReview)
        .map(Mapping::providerCode)
        .collect(toSet());
    assertThat(reviewedCodes).contains("QP-005", "QP-008", "QP-009", "QP-401");

    // Every reviewed code's review_reason must be V4-attributed
    r.mappings().stream()
        .filter(Mapping::needsHumanReview)
        .forEach(m -> assertThat(m.reviewReason())
            .startsWith("known_ambiguous_pattern: "));
}
```

### Test #28 — QuickPay e2e with structural assertion + named-codes pin

```java
@Test
void e2e_quickpay_structurallyMatchesGold_andNames4AmbiguousCodes(@TempDir Path tmp) {
    var llm = new FixtureLlmClient(FIXTURES_DIR, "quickpay_v2.4");
    var pipeline = buildPipeline(llm, tmp);
    Path out = tmp.resolve("result.json");

    pipeline.run(QUICKPAY_FIXTURE, "QuickPay Global", "2.4", out);

    MappingResult actual = readJson(out);
    MappingResult gold   = readJson(GOLD_QUICKPAY);

    // Headline assertion — explicit, by name
    Set<String> reviewedCodes = actual.mappings().stream()
        .filter(Mapping::needsHumanReview)
        .map(Mapping::providerCode)
        .collect(toSet());
    assertThat(reviewedCodes)
        .containsExactlyInAnyOrder("QP-005", "QP-008", "QP-009", "QP-401");

    // Structural pins
    GoldAssert.structurallyMatches(actual, gold);
}
```

### Test #55 — cache dir leakage guard

```java
@Test
void cacheDir_noLeakageOutsideTempDir(@TempDir Path tmp) throws IOException {
    Path beforeSnapshot = capture(workingDir());

    var pipeline = buildPipelineWithCacheDir(tmp.resolve("cache"));
    pipeline.run(QUICKPAY_FIXTURE, "QuickPay", "2.4", tmp.resolve("out.json"));

    Path afterSnapshot = capture(workingDir());
    assertThat(diff(beforeSnapshot, afterSnapshot)).isEmpty();   // no stray files
    assertThat(tmp.resolve("cache")).exists();                    // cache went where it should
}
```

## 9. What is NOT tested

- Real HTTP calls to Anthropic (that's Anthropic's test surface, not ours).
- Jackson JSON serialization correctness (Jackson's tests).
- Reproducibility across model versions (validated separately via optional `RecordedLlmClient + RECORD_MODE=true` runs, not in CI).
- Performance / throughput (out of scope for a 3-hour assignment).

## 10. Test commands

```bash
mvn test                                # all unit + component + integration
mvn test -Dtest=ValidatorTest           # one class
mvn test -Dtest=*_when_*                # tests matching naming convention
mvn test -DRECORD_MODE=true             # rewrite tests/resources/llm_responses/
                                        # (requires ANTHROPIC_API_KEY, manual only,
                                        # only relevant if using RecordedLlmClient)
```

**CI gate**: `mvn test`. No `RECORD_MODE`. No API key needed in CI. **Zero tokens consumed.**

## 11. Summary

| Layer | Tests | Mock | LLM tokens |
|---|---|---|---|
| Unit | 43 | none | 0 |
| Component | 22 | `ScriptedLlmClient` | 0 |
| Integration | 3 | `FixtureLlmClient` (file-backed) | 0 |
| **Total** | **68** | — | **0** |

Every defence layer (B0, P1, R1, V1-V5) has at least one **targeted** test that fails specifically when that layer is removed, plus at least one e2e test that exercises it as part of the full pipeline. The chaos test (#56) is the global proof that the LLM is controlled — without the validator, the suite fails noisily.
