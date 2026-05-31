# Architecture Review — Findings & Decisions

**Subject**: AI Decline Code Mapper, pre-implementation review
**Reviewer**: independent agent (Opus), no prior context
**Review prompt**: [apm_decline_mapper_arch_review_prompt.md](./apm_decline_mapper_arch_review_prompt.md)
**Implementation prompt under review**: [apm_decline_mapper_prompt.md](./apm_decline_mapper_prompt.md)
**Date**: 2026-05-30

---

## Reviewer's verdict

**GO-WITH-FIXES.** Four-stage shape is sound and the controlled-component framing is right, but ship blockers on bounded recovery, the parser oracle, and deterministic ambiguity detection must land before the first LLM call is wired up.

**Scores (1–10)**:
- Simplicity: 7 — four stages, clear data flow; loses a point for an LLM Parser doing work a state machine could do, and Picocli for two flags.
- Robustness: 4 — unbounded recovery, anchor regex too narrow, self-graded confidence, batch contamination.
- Cost discipline: 4 — no run budget, no cache, no token estimate, JSONL logs are post-mortem only.
- Debuggability: 7 — JSONL per call is good; loses points because partial runs produce no artifact and the parser has no oracle for *content* errors.

---

## Raw findings (verbatim from reviewer)

### BLOCKER

- **Finding**: The recovery loop has no global ceiling — only a per-code cap of 2 re-prompts, so a 24-code fixture with persistent schema drift can fire 48 extra LLM calls with no token/cost budget guarding it.
  - Why it matters: A misbehaving prompt or a transient provider issue turns a "3 hour assignment" run into a multi-dollar, multi-minute incident, and there is nothing in the design that aborts the pipeline when total calls exceed N or total tokens exceed M. The assignment explicitly asks for LLM-as-controlled-component; an unbounded fan-out of repair calls is the opposite.
  - Fix: Add an explicit run-level budget (e.g. `MAX_LLM_CALLS=ceil(codes/batch_size)*2 + 4`, `MAX_TOKENS_PER_RUN=...`) enforced in `Pipeline.java`. Once exceeded, stop calling, mark remaining codes `confidence=low, needs_human_review=true, review_reason="budget_exhausted"`, and exit non-zero.

- **Finding**: The anchor regex `[A-Z]+[-_]\d+` is the ground truth for "did the parser miss codes" but it will silently miss real-world formats (`5xx`, `do_not_honor`, `R01`, `05`, `IL_FORMAT_ERROR`, lowercase `qp-001`), so the parser's cross-check is a false sense of safety.
  - Why it matters: On FastBank or any second provider whose codes don't fit the pattern, the anchor count equals the parser count (both wrong by the same delta), the validator's "all input codes present" check passes, and codes are silently dropped — the exact failure mode the assignment lists as a hard requirement to handle.
  - Fix: Either (a) drop the anchor cross-check entirely and rely on the validator's reconciliation against an explicit per-fixture expected-count assertion in tests, or (b) make the anchor a *union* of patterns (`[A-Za-z0-9]+([-_][A-Za-z0-9]+)*` plus numeric-only `\b\d{2,4}\b` near "code:"/"error:" keywords) and treat anchor/parser disagreement as a warning, not a re-prompt trigger.

### MAJOR

- **Finding**: Stage 1 (Parser) uses an LLM to do structural extraction that the assignment explicitly describes as "sections like `=== Transaction Processing Errors ===`" — a deterministic format the model will sometimes get wrong, and you have no oracle to detect when it does.
  - Why it matters: Parser hallucinations (merged descriptions, dropped section labels, invented codes) flow into the Mapper as "ground truth." The anchor cross-check only catches *count* mismatches, not *content* mismatches. The Validator never sees the raw text, so a parser that fabricates `provider_message: "Suspected fraud"` for a code that actually said "Card expired" produces a confidently-wrong mapping with no signal.
  - Fix: Make the parser deterministic-first: a small state machine over `=== ... ===` sections and the anchor regex, with the LLM invoked *only* on chunks where the deterministic parser produced an ambiguous result (e.g. multi-paragraph description, nested codes). Log which path each code took.

- **Finding**: Batching 5–10 codes per Mapper call creates cross-code contamination — the model will pattern-match later codes in the batch to earlier ones it just categorized, producing artificially-consistent answers that look high-confidence but aren't independent judgments.
  - Why it matters: This directly breaks the "inconsistent confidence" detection the assignment requires. If `QP-005` and `QP-006` are in the same batch and both ambiguous, the model commits to a category for `QP-005` then anchors `QP-006` to it. The validator's hedge-word check can't catch this because the reasoning will look clean.
  - Fix: Keep batching for cost, but (a) shuffle batch order across runs to detect order-sensitivity in tests, and (b) randomize within-batch order on every call, and (c) for any code flagged ambiguous by the validator, re-prompt it *alone* (batch size 1) as part of recovery, not as a retry of the same batch.

- **Finding**: "Hedge words in reasoning + high confidence → demote to medium" is the only consistency check, and it's a brittle keyword heuristic that will both false-positive (any code with the word "might" gets demoted) and false-negative (a model that writes confident prose about a fundamentally ambiguous code passes).
  - Why it matters: The assignment lists *specific* ambiguous codes that must come back `low` + `needs_human_review=true` ("Do not honor", "Duplicate transaction", "Session expired"). A keyword check on the model's own justification is the model grading its own work. If it confidently mis-categorizes "Do not honor" as ANTIFRAUD with clean prose, the validator ships it.
  - Fix: Maintain a deterministic ambiguity list (substring/regex match on `provider_message`) — `do not honor`, `duplicate`, `not permitted`, `session expired`, plus an extensible YAML. Any match forces `confidence=low, needs_human_review=true, review_reason="known_ambiguous_pattern"` regardless of what the model said. This is exactly the kind of work the assignment means by "deterministic recovery."

- **Finding**: Confidence levels (`high|medium|low`) are not operationally defined anywhere — the prompt will rely on the model's intuition, and "high" will mean different things across batches, providers, and model versions.
  - Why it matters: A downstream consumer of `result.json` filtering on `confidence=high` will get a different population every run. The assignment's "inconsistent confidence" requirement implies the design has a concept of what those levels *mean* — but the architecture doesn't show one.
  - Fix: Define confidence in `mapper-system.md` as a checklist the model must satisfy: `high` = exact category match in taxonomy AND no hedge AND no overlap with adjacent category; `medium` = clear primary category but plausible secondary; `low` = two or more categories plausible OR message vague. Put this in the prompt verbatim.

- **Finding**: No idempotency / caching layer — running the tool twice on the same input fires the full LLM bill again and can produce different output if the model is non-deterministic at temp=0 (Anthropic's models still drift).
  - Why it matters: The assignment asks for reproducibility (it's the whole point of pinning temperature). Two runs of `quickpay_v2.4.txt` producing different `result.json` files makes the tool unusable as a reference. Also wastes the time budget on development re-runs.
  - Fix: Hash `(model, prompt_template_version, input_text_normalized)` and cache responses to `logs/cache/<hash>.json`. On a cache hit, skip the call. This also makes the JSONL log + cache the artifact that lets you replay a run without re-billing.

### MINOR

- **Finding**: Two few-shot examples both from QuickPay (`QP-006` clean, `QP-005` ambiguous) will anchor every other provider's mapping toward QuickPay's category distribution and prose style.
  - Why it matters: The whole point of the FastBank fixture is to prove the design isn't overfit to QuickPay, but the prompt itself is overfit. FastBank codes will get categorized "in the style of QuickPay" — particularly the model will reach for the same categories the examples used.
  - Fix: Use synthetic, provider-neutral examples (e.g. `XX-001`, `YY-042`) that exercise the taxonomy edges, not real QuickPay codes. Keep QuickPay codes for fixtures/tests, not prompts.

- **Finding**: `MappingResult.summary.unmapped` is in the schema but no stage produces it — the Validator's recovery loop will instead mark codes `needs_human_review=true`, so `unmapped` will always be 0 and is dead data.
  - Why it matters: Schema fields that are never populated lie to consumers and rot. Either the field has a meaning (codes the pipeline gave up on) or it doesn't.
  - Fix: Define `unmapped` as "codes for which recovery was exhausted and no valid mapping was produced" — populate it when per-code retry cap or run budget is hit. Otherwise remove it.

- **Finding**: `AnthropicClient.java` wraps the SDK *and* does JSONL logging — two responsibilities, and the logging will be skipped on any code path that bypasses the wrapper (e.g. a future stage that calls the SDK directly).
  - Why it matters: For a 3-hour build it's fine, but the stated value of JSONL logs is "post-mortem on a failed run" — and that fails the moment one call escapes the wrapper.
  - Fix: Make the logger a separate `LlmCallLog` that the wrapper *must* invoke; assert in `Pipeline.java` that the log file has at least one entry per LLM-using stage at end-of-run. Cheap insurance.

- **Finding**: The pipeline writes `result.json` only on success — a run that exhausts the budget or crashes mid-mapper produces no artifact, only logs.
  - Why it matters: For an internal tool, a partial `result.json` with `needs_human_review=true` on the unfinished codes is far more useful than no file at all, and matches how operators actually use these tools.
  - Fix: Always write `result.json`, even on partial completion. Use the `summary` to indicate completeness (`total_codes`, `mapped`, `unmapped`, `budget_exhausted: true|false`).

### NIT

- **Finding**: Picocli is overkill for a tool with `--input file --output file` and 2 env vars.
  - Why it matters: Adds a dependency, generates help text nobody reads, costs maybe 10 minutes of the 3-hour budget.
  - Fix: Two `args[]` positionals plus `System.getenv`. Save the time for a third fixture.

### Top 3 must-fix before implementation (reviewer's call)

1. Add a run-level LLM call/token budget with a deterministic abort path that still writes a valid `result.json` — the recovery loop is unbounded as designed.
2. Replace the LLM-driven Parser with a deterministic section/anchor parser that falls back to LLM only for ambiguous chunks, and stop trusting the anchor-count cross-check as a correctness oracle.
3. Move ambiguity detection out of "hedge words in the model's own prose" and into a deterministic `provider_message` pattern list — this is the assignment's headline requirement and the current design lets the model self-grade.

---

## Author's decisions (which findings are adopted)

| # | Finding | Adopted? | Action |
|---|---|---|---|
| BLOCKER 1 | No global budget on recovery loop | **Yes, fully** | Add env vars `MAX_LLM_CALLS`, `MAX_TOKENS_PER_RUN`. Exhaustion → remaining codes marked `unmapped`, exit non-zero, **still write `result.json`**. |
| BLOCKER 2 | Anchor regex too narrow | **Yes** | Widen pattern (`[A-Za-z][A-Za-z0-9]*([-_][A-Za-z0-9]+)+` plus `\b\d{2,4}\b` near `code:`/`error:` keywords). Anchor/parser mismatch becomes a **warning**, not a re-prompt trigger. |
| MAJOR 1 | LLM-first parser is risky | **Yes — design reversed** | **Deterministic-first parser** (state machine over `=== sections ===` + anchors). LLM invoked **only as fallback** on chunks the state machine flags ambiguous. Log per-code which path was used. |
| MAJOR 2 | Batch contamination | **Partial** | (a) Randomize within-batch order. (b) Add a **single-code re-validation pass** for any code flagged ambiguous by the validator. (c) **Skip** cross-run order shuffling — testing overhead not worth it for 3h. |
| MAJOR 3 | Hedge-word check = model grading itself | **Yes — central fix** | Introduce `ambiguity_patterns.yaml` with `provider_message` substring/regex matchers. Any match forces `confidence=low`, `needs_human_review=true`, `review_reason="known_ambiguous_pattern"` regardless of LLM output. |
| MAJOR 4 | Confidence levels undefined | **Yes** | Add operational rubric to `mapper-system.md`: `high` = clear match, no hedge, no overlap; `medium` = clear primary, plausible secondary; `low` = ≥2 plausible OR vague message. |
| MAJOR 5 | No cache / non-idempotent | **Yes, lightweight** | File cache: `hash(model + prompt_template_version + normalized_input)` → `logs/cache/<hash>.json`. ~30 lines, pays for itself in dev iteration. |
| MINOR 1 | Few-shot anchored to QuickPay | **Yes** | Replace QP-005/QP-006 in prompt with synthetic neutral examples (`XX-001`, `YY-042`). |
| MINOR 2 | `unmapped` is dead data | **Resolved by BLOCKER 1 fix** | Field becomes the budget/retry-exhaustion counter. |
| MINOR 3 | `AnthropicClient` has 2 responsibilities | **No** | Reviewer admits "for 3-hour build it's fine." Skip. |
| MINOR 4 | No artifact on partial completion | **Yes** | Always write `result.json`. `summary` carries completeness flags. |
| NIT | Picocli overkill | **Yes** | Drop. Use `String[] args` + `System.getenv`. |

## Resulting architecture (post-fixes)

```
                  ┌────────────────────────────────────────┐
                  │ anchors: extended regex + keywords     │  ← warning-only
                  └─────────────────┬──────────────────────┘
                                    │
raw text  ──────▶ ┌─────────────────▼──────────────────────┐
                  │ 1. PARSER (state machine first)        │
                  │   sections + anchors → tuples          │
                  │   LLM fallback ONLY on flagged chunks  │
                  │   log path per code                    │
                  └─────────────────┬──────────────────────┘
                                    │
                  ┌─────────────────▼──────────────────────┐
                  │ 2. ENRICHER (no LLM)                   │
                  │   + operational confidence rubric      │
                  │   + neutral synthetic few-shots        │
                  └─────────────────┬──────────────────────┘
                                    │
                  ┌─────────────────▼──────────────────────┐
                  │ 3. MAPPER (LLM, batches 5-10)          │
                  │   tool_use schema, temperature=0       │
                  │   within-batch order randomized        │
                  │   response cache by content hash       │
                  └─────────────────┬──────────────────────┘
                                    │
                  ┌─────────────────▼──────────────────────┐
                  │ 4. VALIDATOR + recovery (no LLM)       │◀──┐
                  │   schema/coverage/vocab checks         │   │ targeted re-prompt
                  │   + deterministic ambiguity patterns   │   │ (per-code cap = 2)
                  │     (force low+review independently)   │   │
                  │   + single-code re-validation pass     │   │
                  │     for ambiguous codes                │   │
                  └─────────────────┬──────────────────────┘   │
                                    │                          │
                                    └──────────────────────────┘
                                    │
                       ┌────────────▼─────────────┐
                       │ run-level budget guard   │
                       │ MAX_LLM_CALLS, MAX_TOKENS│
                       │ exhaust → unmapped +     │
                       │ partial result.json      │
                       └────────────┬─────────────┘
                                    ▼
                              result.json
```

## New configuration (env)

```
ANTHROPIC_API_KEY=...
LLM_MODEL=claude-sonnet-4-5
MAX_LLM_CALLS=40
MAX_TOKENS_PER_RUN=200000
CACHE_ENABLED=true
```

## New resource: `ambiguity_patterns.yaml`

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
