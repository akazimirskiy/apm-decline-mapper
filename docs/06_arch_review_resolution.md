# Architecture Review — Resolution Notes

**Subject**: how each review finding was resolved in the implementation prompt (rev 2).
**References**:
- Review protocol: [apm_decline_mapper_arch_review_prompt.md](./apm_decline_mapper_arch_review_prompt.md)
- Raw findings + decisions table: [apm_decline_mapper_arch_review_findings.md](./apm_decline_mapper_arch_review_findings.md)
- Updated implementation prompt: [apm_decline_mapper_prompt.md](./apm_decline_mapper_prompt.md)
- Date: 2026-05-30

This document complements the findings file. The findings file contains the reviewer's raw output and a binary adopted/skipped table. This file walks each finding in prose, showing exactly **what landed in the rev 2 implementation prompt and where**.

---

## BLOCKER 1 — Recovery loop has no global budget

**What was found.** A per-code re-prompt cap of 2 exists, but no run-level ceiling. A misbehaving prompt or transient provider issue can fan out into +48 LLM calls on a 24-code fixture, with nothing stopping it. Directly contradicts the "LLM as controlled component" requirement.

**How it was fixed in the prompt.**
- New dedicated section `Run-level budget guard` between the four stages and the output schema.
- Two env vars: `MAX_LLM_CALLS=40`, `MAX_TOKENS_PER_RUN=200000`.
- Guard applies to **all** LLM calls — Parser-fallback, Mapper batches, Validator re-prompts.
- On exhaustion: **do not crash**. Remaining codes → `unmapped` with `review_reason: "budget_exhausted"`. `summary.budget_exhausted = true`. `result.json` is still written.
- New exit codes: `0` = clean, `1` = some `needs_review`, `2` = some `unmapped`.

---

## BLOCKER 2 — Anchor regex too narrow

**What was found.** `[A-Z]+[-_]\d+` silently misses `R01`, `do_not_honor`, `IL_FORMAT_ERROR`, `05`, lowercase `qp-001`. On a second provider, anchor count and parser count can both be wrong by the same delta — the cross-check passes, codes are dropped silently. This is the exact failure mode the assignment lists as a hard requirement.

**How it was fixed in the prompt.**
- Pattern widened to `[A-Za-z][A-Za-z0-9]*(?:[-_][A-Za-z0-9]+)+` — captures `QP-001`, `ERR_4002`, `do_not_honor`, `PG-CARD-EXPIRED`.
- Numeric-only codes handled separately: `\b\d{2,4}\b` within 30 chars of `code:` / `error:` / `result_code:`.
- Critical change: **mismatch is now a stderr warning, not a re-prompt trigger**. Verbatim in the prompt: "Do not re-prompt the parser — the anchor regex is not a correctness oracle."
- FastBank fixture requirements explicitly call for one "do not honor"-style code, one numeric (`05`), and one snake_case (`ERR_RATE_LIMIT`) — so the widened regex is actually exercised.

---

## MAJOR 1 — Parser uses LLM where a state machine suffices

**What was found.** Parsing `=== sections ===` + anchored quoted messages is a regular task. Using the LLM means: wasted tokens, hallucinations in `provider_message` flow into the Mapper as ground truth, and the Validator has no oracle for content errors (only for count).

**How it was fixed in the prompt.** *This is the largest change between rev 1 and rev 2.* Stage 1 is fully rewritten:

1. Anchor scan (regex list of code tokens).
2. Section walker — state machine line-by-line, tracks current section from `===` headers, captures quoted message + indented description.
3. Explicit ambiguity criteria: no quoted message after the code, nested code references, chunk spans multiple sections.
4. **LLM is invoked only on chunks flagged ambiguous in step 3.** Per-code log entry records which path was taken (state-machine vs LLM-fallback) — this is the oracle the reviewer demanded.
5. Cross-check stays as a warning, not a trigger (see BLOCKER 2).

Net effect: state machine handles 95% for free, LLM absorbs the long tail, and neither path is a single point of failure.

---

## MAJOR 2 — Cross-code contamination in batches

**What was found.** With batch size 5–10, the model anchors later codes to earlier ones. If two ambiguous codes land in the same batch, the model commits on the first and pattern-matches the second to it. The result looks uniformly confident — an artifact of batching, not real signal.

**How it was fixed in the prompt.** Two of the three reviewer-suggested mitigations were adopted:
- **Within-batch order randomized** with a fixed seed (`Random(42)`) — reproducible, but breaks the anchoring effect.
- **Single-code re-validation pass** — every code flagged `LOW` (by the LLM, by hedge demotion, or by ambiguity patterns) gets one more Mapper call as a **batch of size 1**, with an explicit "this code is suspected ambiguous — confirm or correct" instruction. Result is merged but **`LOW` from ambiguity patterns is sticky** — re-validation can refine reasoning, not raise confidence.
- Cross-run shuffle was **not adopted** — testing overhead doesn't pay back within the 3-hour budget.

---

## MAJOR 3 — Hedge-word check = model grading itself

**What was found.** The only consistency check was a keyword scan over the model's own reasoning. If the model confidently mis-categorizes "Do not honor" as ANTIFRAUD with clean prose, the validator ships it. This breaks the assignment's headline requirement — specific listed codes must come back `low + review`.

**How it was fixed in the prompt.** *This is the central correctness fix in rev 2.*
- New required resource: `src/main/resources/ambiguity_patterns.yaml`, content specified inline in the prompt.
- Validator step 5 is verbatim: any pattern match against `provider_message` forces `confidence = LOW`, `needs_human_review = true`, `review_reason = "known_ambiguous_pattern: <reason>"`, **regardless of what the LLM returned**.
- The list is extensible without code changes — new providers contribute new patterns, not new code.
- Validator step 6 (single-code re-validation) explicitly states: **ambiguity-pattern flags are sticky**. Re-validation may refine the reasoning, never raise the confidence.
- The hedge-word check (step 4) is retained as a secondary filter on top of the deterministic patterns, no longer the sole defence.

---

## MAJOR 4 — Confidence levels not operationally defined

**What was found.** `high|medium|low` are nowhere defined. The model decides what "high" means. Different batches, models, runs → different distributions. A downstream consumer filtering on `confidence=high` gets a different population every run.

**How it was fixed in the prompt.** Stage 2 (Enricher), step 3 — an **operational confidence rubric verbatim** in the system prompt:
```
high   = exact match + no hedge + no plausible second category
medium = clear primary + plausible secondary
low    = ≥2 plausible OR vague message
```
The model sees this rubric and is bound to it.

---

## MAJOR 5 — No cache, repeated runs reburn the bill

**What was found.** Reproducibility is the whole point of `temperature=0`, but Anthropic models drift even at zero. Two runs of `quickpay_v2.4.txt` may produce different `result.json`. Dev iteration also pays for the same thing repeatedly.

**How it was fixed in the prompt.** Stage 3 (Mapper) — new subsection on response cache.
- Key: `sha256(model + prompt_template_version + canonical_batch_input)`.
- Storage: `logs/cache/<hash>.json`.
- Behavior: cache hit returns cached response; success writes new entry.
- Controlled by env flag `CACHE_ENABLED=true`.
- Side benefit: the checked-in `samples/quickpay_v2.4.result.json` is guaranteed to match a local re-run as long as the prompt hasn't changed.

---

## MINOR 1 — Few-shot examples anchored to QuickPay

**What was found.** Both in-prompt examples were `QP-005` and `QP-006`. The FastBank fixture exists specifically to prove the design isn't QuickPay-overfit, but the prompt itself was. FastBank codes would get categorized "in the style of QuickPay."

**How it was fixed in the prompt.** Stage 2, step 4 — examples replaced with **provider-neutral synthetic codes**:
- `XX-001 "Card balance insufficient"` → `COMMON_DECLINE, high`.
- `YY-042 "Transaction not allowed"` → `COMMON_DECLINE, low, needs_human_review: true`.

QP-coded entries now live only in the fixtures and tests, not in the prompt sent to the model.

---

## MINOR 2 — `unmapped` is dead data

**What was found.** Schema declares `unmapped` but no code path produces it — the recovery loop marks codes `needs_review`, so `unmapped` is always 0. Schema fields that lie eventually rot.

**How it was fixed in the prompt.** Resolved as a side effect of BLOCKER 1. `unmapped` now counts codes with `review_reason ∈ {"budget_exhausted", "recovery_exhausted"}`. The `summary` block also gains new fields: `mapped`, `llm_calls`, `tokens_used`, `budget_exhausted` — full picture of the run.

---

## MINOR 3 — `AnthropicClient` has two responsibilities

**What was found.** SDK wrapper and JSONL logger in one class. Risk that a future call path bypasses the logger.

**How it was fixed in the prompt.** **Not changed.** The reviewer themselves wrote "for a 3-hour build it's fine." The Non-goals section now states this explicitly: "Splitting AnthropicClient from the JSONL logger (review explicitly waived this for the 3h budget)." Documenting the conscious deferral is the fix; the code change would not pay back.

---

## MINOR 4 — `result.json` only written on success

**What was found.** A crash or budget-exhaust mid-run produces logs only, no artifact. For an internal tool, a partial `result.json` with `needs_human_review=true` on unfinished codes is far more useful.

**How it was fixed in the prompt.**
- Quality bar section: "Partial output is always written. A crash mid-run that leaves `result.json` absent is a bug, not a degraded mode."
- Budget-guard step 4: "Write `result.json` anyway — partial results are more useful than no results."
- A unit test for this case is explicitly listed: "Budget exceeded mid-run → remaining codes marked unmapped, `summary.budget_exhausted = true`, `result.json` still written."

---

## NIT — Picocli overkill

**What was found.** For two args and two env vars, Picocli adds a dependency and ~10 min of build time for no benefit.

**How it was fixed in the prompt.**
- Stack section: "**No** Picocli, Lombok, DI containers, logging framework. `String[] args` + `System.getenv` + `System.out` are enough."
- CLI section: positional args only, no flags. `java -jar decline-mapper.jar <input.txt> <provider-name> <provider-version> <output.json>`.

---

## Additional reinforcements (not from review, but landed in rev 2)

- **Per-block `[v2]` markers** in the prompt — every changed block is tagged inline so the next reviewer reads the diff without comparing files.
- **Stack section promoted to the top** (Java 21 + Maven + stdlib `HttpClient` instead of `anthropic-java` SDK). Rationale stated: saves ~10 min of dependency wiring and one transitive surface.
- **Enum as first line of defence**: explicitly noted that the Java `enum` for `Category` and `RetryStrategy` plus Jackson deserialization throws on unknown values *before* the Validator even runs — schema-level defence on top of the validator-level defence. Borders on MAJOR 3 territory; worth being explicit.
- **Exact model ID pinning**: `.env.example` must document the resolved model version (`claude-sonnet-4-5-20250929`), not just the alias. Without this, the cache and reproducibility diverge silently when Anthropic rolls the alias.

---

## Open questions before code

1. Is the v2 prompt fully approved? Specifically the two largest shifts: deterministic-first parser, and YAML ambiguity-patterns list as the central correctness defence.
2. Stdlib `HttpClient` instead of `anthropic-java` SDK — confirmed?
3. Is there an `ANTHROPIC_API_KEY` available for the implementation run, or does the run land on the author after?
