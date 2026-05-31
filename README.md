# APM Decline Code Mapper

CLI tool that ingests a payment provider's error-code documentation as raw text and emits a JSON mapping of every code to one of seven internal decline categories — with confidence, reasoning, retry strategy, and a human-review flag.

The LLM is used as a **controlled component inside an agent pipeline**, not as a single black-box call. Eight deterministic defence layers wrap every LLM interaction.

## Quick start

```bash
# 1. Build
mvn -q package

# 2. Configure
cp .env.example .env
# Edit .env, set ANTHROPIC_API_KEY=sk-ant-...

# 3. Load env vars (robust against values with spaces or quotes)
set -a; source .env; set +a

# 4. Run
java -jar target/decline-mapper.jar \
  fixtures/quickpay_v2.4.txt "QuickPay Global" "2.4" result.json

# 4. Inspect
cat result.json | jq '.summary'
```

`result.json` is **always written**, even on partial completion or budget exhaustion.

Exit codes:
- `0` — every code mapped, no review, no unmapped
- `1` — bootstrap failure (stderr prefix `BOOTSTRAP_ERROR:`) OR every code mapped but ≥1 needs human review
- `2` — ≥1 code unmapped (recovery exhausted, budget hit, or parse garbage)

## Architecture

```
   ┌──────────────────────────────────────────────────────────────────────┐
   │  BOOTSTRAP   env vars, ambiguity_patterns.yaml, regex compile        │
   │  fail-fast before any input is read                                  │
   └──────────────────────────────────────────────────────────────────────┘
                                       │
                                       ▼
   ┌──────────────────────────────────────────────────────────────────────┐
   │            run-level budget guard (MAX_LLM_CALLS · MAX_TOKENS)       │
   │            wraps every LLM call across all stages                    │
   └──────────────────────────────────────────────────────────────────────┘
                                       ▲
                                       │ enforces ceiling
                                       │
   raw vendor doc (text) ─▶ ┌──────────────────┐
                            │  STAGE 1 PARSER  │ deterministic state machine
                            │  ParseOutcome:   │ Ok / AmbiguousChunk / Garbage
                            │  Garbage bypass  │ (Garbage NEVER hits the LLM)
                            └────────┬─────────┘
                                     ▼
                            ┌──────────────────┐
                            │  STAGE 2 ENRICH  │ taxonomy + retry vocab +
                            │  build prompt    │ confidence rubric + 2 neutral
                            │  (no LLM)        │ few-shots (XX-001 / YY-042)
                            └────────┬─────────┘
                                     ▼
                            ┌──────────────────┐
                            │  STAGE 3 MAPPER  │ batches of 5, within-batch
                            │  LLM + cache +   │ Random(42), tool_use schema,
                            │  Sanitizer (R1)  │ Anthropic Messages API,
                            │                  │ NeedsReprompt or Clean
                            └────────┬─────────┘
                                     ▼
                            ┌──────────────────┐
                            │  STAGE 4 VALIDATE│ V1 enum / V2 coverage /
                            │  + recovery loop │ V3 hedge demote / V4 ambiguity
                            │  + V5 re-validate│ patterns (sticky) / V5 batch-1
                            │  retry cap = 2   │ re-call for LOW codes
                            └────────┬─────────┘
                                     ▼
                                result.json
                          (always written, partial OK)
```

### Eight defence layers

The LLM is the only non-deterministic component. Every other layer treats the LLM as fallible.

| Layer | Where | Mechanism | Catches |
|---|---|---|---|
| B0 Bootstrap | Init | env validated, YAML loaded, regex compiled and smoke-tested | Misconfiguration; dangerously-broad patterns |
| P1 ParseOutcome | Stage 1 | `Garbage` variants bypass the Mapper entirely | Empty file, binary, truncated, duplicate-conflict, encoding |
| R1 Sanitizer | Stage 3 | Shape-checks raw `LlmResponse` | Malformed JSON, missing tool_use, max_tokens truncation, unknown/duplicate codes |
| V1 Schema/enum | Stage 4 | `tool_use` schema + Java enum deserialization | Hallucinated category strings |
| V2 Coverage | Stage 4 | Expected ↔ output code-set diff | Skipped codes |
| V3 Hedge demotion | Stage 4 | Regex on `reasoning` text + `HIGH` | Self-inconsistent confidence |
| V4 Ambiguity patterns | Stage 4 | YAML match on `provider_message` | Known-ambiguous codes the LLM categorized confidently anyway |
| V5 Re-validation | Stage 4 | Single-code batch-of-1 call with sticky `LOW` | Cross-code contamination from batching |

**V4 is the load-bearing layer** — it's the only layer that grades the LLM independently of the LLM's own output. It's the defence the assignment's "QP-005 / QP-008 / QP-009 / QP-401 must come back `LOW + review`" requirement actually rests on.

### Configuration

| Env var | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | — (required) | Anthropic API auth |
| `LLM_MODEL` | `claude-sonnet-4-5` | Model alias |
| `MAX_LLM_CALLS` | `40` | Hard ceiling per run |
| `MAX_TOKENS_PER_RUN` | `200000` | Hard ceiling per run |
| `CACHE_ENABLED` | `true` | File-backed response cache under `logs/cache/` |
| `RECORD_MODE` | `false` | Test-only — currently unused (`FixtureLlmClient` is the default test seam) |

## Design trail

The full reasoning trail — assignment, implementation prompt, architecture (with two independent reviews), and testing concept — lives in [`docs/`](./docs/). See [`docs/README.md`](./docs/README.md) for the reading order.

## Project layout

```
apm-decline-mapper/
├── README.md
├── .env.example                ANTHROPIC_API_KEY, LLM_MODEL, budget, cache
├── pom.xml                     Java 21, Jackson, SnakeYAML, JUnit 5, AssertJ
├── src/main/java/com/bp/declinemapper/
│   ├── Main.java               CLI entry
│   ├── Bootstrap.java          env + YAML + regex validation (B0)
│   ├── Pipeline.java           wires 4 stages + budget guard
│   ├── model/                  Category, RetryStrategy, Confidence enums;
│   │                           ParseOutcome (sealed); Mapping / Summary /
│   │                           MappingResult records; ambiguity types
│   ├── stage/                  Parser, Enricher, LlmMapper,
│   │                           ResponseSanitizer (R1), Validator (V1..V5)
│   ├── llm/                    LlmClient interface, AnthropicLlmClient,
│   │                           LlmRequest / LlmResponse records
│   └── budget/                 BudgetGuard
├── src/main/resources/
│   └── ambiguity_patterns.yaml load-bearing — V4 defence (extensible)
├── src/test/                   ~88 tests (unit / component / integration);
│                               zero LLM tokens consumed in CI
├── fixtures/
│   ├── quickpay_v2.4.txt       26 codes from the assignment PDF
│   └── fastbank_v1.0.txt       invented 2nd provider (snake_case +
│                               numeric + ambiguity-pattern triggers)
├── samples/
│   └── quickpay_v2.4.result.json   committed reference output
└── logs/                       run-{ts}.jsonl + cache/  (git-ignored)
```

## Testing

```bash
mvn test        # full suite, ~3-5 seconds, ZERO LLM tokens consumed
```

The suite is split into three layers:

| Layer | Tests | Mock | LLM tokens |
|---|---|---|---|
| Unit | ~66 | none | 0 |
| Component | ~20 | `ScriptedLlmClient` (programmable queue) | 0 |
| Integration | 2 | `ScriptedLlmClient` with per-code gold-standard handler | 0 |
| **Total** | **88** | — | **0** |

The single mock seam is the `LlmClient` interface. Three implementations:

- `AnthropicLlmClient` — production HTTP with stdlib `HttpClient`, tool_use envelope, JSONL log, 3-attempt retry on 429/5xx with exponential backoff.
- `ScriptedLlmClient` — programmable queue / repeating / handler / error variants. Used everywhere in unit + component + integration tests.
- `FixtureLlmClient` — file-backed by `(provider, batchIndex)`. Scaffolded for future on-disk recordings; not currently used (the integration tests use `ScriptedLlmClient` with a gold-standard handler for simplicity, which keeps the suite single-file).

### Key tests

- `ChaosTest` (component): feeds the full QuickPay v2.4 fixture through the pipeline with a `ScriptedLlmClient` that returns `ANTIFRAUD/HIGH` for **every** code regardless of context. Asserts that the four named ambiguous codes (QP-005, QP-008, QP-009, QP-401) still come back `LOW + needs_human_review:true` with a `known_ambiguous_pattern:` reason. **This is the single regression-protection test for the "LLM is controlled" claim.** If V4 is ever silently disabled, this test fails loudly.
- `BudgetGuardTest` (component): MAX_LLM_CALLS / MAX_TOKENS exhaustion produces partial `result.json` with `budget_exhausted: true`; cache hits don't count against the budget.
- `PipelineE2eTest` (integration): full pipeline on QuickPay + FastBank fixtures, structural pins (category, retry strategy, confidence, review-flag count). Writes `samples/quickpay_v2.4.result.json` as a side-effect so the reference output is committed.

## Sample output

`samples/quickpay_v2.4.result.json` contains the canonical output for the assignment PDF's QuickPay v2.4 (26 codes across 5 sections).

> **Note**: this sample was generated by the integration test harness with a `ScriptedLlmClient` that returns a hand-authored gold-standard categorization per code. The `reasoning` strings are synthetic — running the pipeline against the real Anthropic API will produce richer, model-authored prose. To regenerate from a real LLM, set `ANTHROPIC_API_KEY`, then run the CLI command above and copy the result.

Highlights:

```json
{
  "provider": "QuickPay Global",
  "version": "2.4",
  "mappings": [
    {
      "provider_code": "QP-008",
      "provider_message": "Do not honor",
      "internal_category": "COMMON_DECLINE",
      "confidence": "low",
      "reasoning": "...",
      "retry_strategy": "no_retry",
      "needs_human_review": true,
      "review_reason": "known_ambiguous_pattern: Issuer catch-all — could be antifraud, limit, or generic decline."
    },
    {
      "provider_code": "QP-009",
      "provider_message": "Duplicate transaction",
      "internal_category": "COMMON_DECLINE",
      "confidence": "low",
      "retry_strategy": "no_action",
      "needs_human_review": true,
      "review_reason": "known_ambiguous_pattern: Could be legitimate idempotency or replay attack."
    }
    /* ... 24 more entries ... */
  ],
  "summary": {
    "total_codes": 26,
    "mapped": 26,
    "high_confidence": 20,
    "needs_review": 4,
    "unmapped": 0,
    "budget_exhausted": false,
    "llm_calls": 6,
    "tokens_used": 4200
  }
}
```

## Trade-offs (what was simplified for the ~3-hour budget)

**Simplified:**
- `AnthropicLlmClient` is single-class — HTTP, tool_use envelope, and JSONL logging combined. The test review explicitly waived splitting this for the time budget.
- LLM fallback for `ParseOutcome.AmbiguousChunk` is **not wired**. Ambiguous chunks (multi-paragraph descriptions, nested code references) are currently emitted as unmapped with a clear reason. State-machine parsing covers ~95% of well-formed vendor docs (QuickPay, FastBank fixtures); the long tail would need the LLM fallback. State-machine parsing handles QuickPay/FastBank with zero LLM calls.
- `FixtureLlmClient` (on-disk hash-keyed recordings) is scaffolded but the integration tests use `ScriptedLlmClient` with a gold-standard handler. The handler approach scales nicely with new providers without hand-authoring JSON per batch.
- Single Anthropic provider. No OpenAI/Gemini abstraction yet, although the `LlmClient` interface is ready for it.

**With more time:**
- LLM fallback for ambiguous chunks (Stage 1b).
- Multi-provider abstraction (OpenAI / Anthropic / local model behind the same `LlmClient`).
- Multi-model consensus for `LOW`-confidence codes — call two different models, agree on category or escalate to human review faster.
- Interactive disambiguation UI for operators to resolve `needs_human_review` codes and persist the result to a feedback loop.
- Persistent decline-code database with versioning + golden-set regression tests across model versions (catch silent prompt drift).
- Structured exception types instead of `IOException` propagation.

## How to extend the ambiguity patterns

`src/main/resources/ambiguity_patterns.yaml` is the load-bearing V4 defence. Add a new entry:

```yaml
patterns:
  - match: "(?i)your-new-pattern"
    reason: "Why this is ambiguous in operator terms."
```

No code change needed. Bootstrap will reject the file at startup if:
- the regex doesn't compile, or
- the pattern is "dangerously broad" (`.*`, empty, or matches any of a curated set of clearly-unambiguous reference messages like "Insufficient balance" / "Card expired").

The reason flows verbatim into `review_reason` after the `known_ambiguous_pattern:` prefix.

## Logs

Every LLM call (request + response + tokens + cache_hit flag) is appended to `logs/run-{timestamp}.jsonl`. One JSON object per line. Useful for post-mortem on a failed run without re-execution.

Cache files live under `logs/cache/`. Both directories are git-ignored.
