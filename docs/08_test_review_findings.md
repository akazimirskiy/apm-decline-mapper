# Test Strategy Review — Findings & Decisions

**Subject**: AI Decline Code Mapper, pre-implementation test-strategy review
**Reviewer**: independent agent (Opus), no prior context
**Review prompt**: [apm_decline_mapper_test_review_prompt.md](./apm_decline_mapper_test_review_prompt.md)
**Test strategy under review**: [apm_decline_mapper_testing_concept.md](./apm_decline_mapper_testing_concept.md) (rev 1, pre-revision)
**Architecture context**: [apm_decline_mapper_solution_architecture.md](./apm_decline_mapper_solution_architecture.md) (v3)
**Date**: 2026-05-30

---

## Reviewer's verdict

**GO-WITH-FIXES.** Structure (mock seam, pyramid, layer-per-test mapping) is sound, but three concrete gaps — named-codes assertion, recording-hash workflow, chaos test — leave the headline "LLM is controlled" claim un-proven; fix them before writing the first test class.

## Scores (1–10)

| Axis | Score | Reviewer's note |
|---|---|---|
| Coverage breadth | 7 | Every layer touched, combinatorial coverage of layer-pairs is thin |
| Coverage depth (negative paths) | 7 | Parser garbage and Sanitizer have strong negatives; Validator's negatives are one-per-layer |
| Mock fidelity (anti-tautology rigor) | 5 | `ScriptedLlmClient` is sound; hand-authored recordings risk being "what the author expected" and no chaos test to prove otherwise |
| Determinism / flake resistance | 6 | `@TempDir` good; `Random(42)` ownership unstated; cache-dir leakage not ruled out |
| Maintainability (golden-file workflow) | 4 | 24-entry byte-for-byte JSON diff is unreviewable; re-record procedure hand-wavy; hash-keyed filenames will rot |
| Adversarial data quality | 5 | FastBank too clean; no fixture stresses real-world doc messiness |

## Reviewer's coverage scorecard

| Defence layer | Targeted? | Adversarial? | Reviewer's note |
|---|---|---|---|
| B0 Bootstrap | Y | Partial | Bad regex covered; overly-broad regex not |
| P1 ParseOutcome | Y | Y | Strong — 8 garbage scenarios |
| R1 Sanitizer | Y | Y | Strong — 8 syntactic edge cases |
| V1 Schema/enum | Y | N | No "valid enum but wrong category" combined with V1 |
| V2 Coverage | Y | N | Single missing-code; no "all missing" or "extra+missing" |
| V3 Hedge demotion | Y | N | Tested alone; never against V4 simultaneously |
| V4 Ambiguity patterns | Y | Partial | One pattern adversarial; 4 named codes not pinned by name |
| V5 Re-validation | Y | Partial | Stickiness tested; only V4→V5 order |
| Budget guard | Y | Y | Three angles covered; cache-vs-budget missing |

---

## Top 3 must-fix (reviewer)

1. Add an explicit by-name assertion that the 4 ambiguous codes (QP-005, QP-008, QP-009, QP-401) are flagged for human review in the QuickPay e2e, plus one named V4 unit test per code.
2. Resolve the recorded-response hashing contradiction: either bootstrap recordings from one real LLM run, or drop hash-keyed filenames in favour of `(provider, batchIndex)` keys.
3. Add the chaos test where `ScriptedLlmClient` always returns `HIGH/ANTIFRAUD` regardless of input, and assert the QuickPay e2e still produces correct categories for the 4 ambiguous codes.

---

## Raw findings (verbatim from reviewer)

### BLOCKER

- **Finding**: The 4 named ambiguous codes (QP-005, QP-008, QP-009, QP-401) are not tested **by name as a set** anywhere — only QP-008 and QP-009 appear in the gold standard snippet and individual unit tests, with QP-005 and QP-401 mentioned only in a parenthetical bullet under "borderline mappings."
  - Why it matters: The assignment's headline correctness claim is "exactly these 4 codes come back `LOW + needs_human_review:true`." A typo in `ambiguity_patterns.yaml` (e.g., "not permited" instead of "not permitted") that silently drops QP-005 from the review set would pass every test — the e2e gold-file diff would flag it, but only one human review of a 24-mapping JSON blob stands between that bug and shipping. There is no test that asserts `result.mappings.filter(needsReview).map(code).toSet() == {QP-005, QP-008, QP-009, QP-401}`.
  - Fix: Add one explicit assertion in the QuickPay integration test: `assertThat(reviewedCodes).containsExactlyInAnyOrder("QP-005","QP-008","QP-009","QP-401")`. Plus one unit test per code against V4 in isolation, each pinned by code name in the test name (`v4_forcesLowReview_forQp005_transactionNotPermitted`, etc.) so a YAML edit that breaks one of the four fails a *named* test, not a JSON diff.

- **Finding**: The hand-authored "recorded LLM responses" are pinned to a `sha256(canonical(request))` hash, but the strategy says recordings are authored by hand. There is no documented procedure for computing the request hash without running the system, and no guard against the hash drifting silently.
  - Why it matters: The first time someone runs the e2e tests, the canonical-form hash will not match any of the 11 committed filenames. The test fails with "No recording for <hash>" — not because of a code bug, but because the author guessed the canonical form wrong. Worse: a small whitespace change in the system prompt (added by Enricher tweak) shifts every hash, invalidates every recording, and the only remediation listed is `RECORD_MODE=true` — which requires an API key the CI doesn't have, contradicting the "zero tokens" guarantee.
  - Fix: Either (a) bootstrap the recordings with one real run gated behind a single human-approved commit, document `canonical(request)` formally, and add a unit test that asserts the canonical form is stable across reorderings; or (b) drop hash-keyed filenames for the hand-authored recordings and key by `(provider, batchIndex)` instead — accept that prompt changes require manual re-review, but at least the tests run on day one without an API key.

### MAJOR

- **Finding**: No "chaos / always-wrong LLM" test exists. The strategy has tests where the LLM is wrong on one code (test #18) but no test where `ScriptedLlmClient` returns `ANTIFRAUD/HIGH` for **every** code in the QuickPay fixture.
  - Why it matters: This is the single test that proves the "LLM is a controlled component" claim. Without it, a regression where the validator is silently disabled (V4 short-circuited, V3 regex broken, V1 enum check removed) would pass the suite because each existing test only exercises one defence layer with one wrong mapping. The pipeline could degrade to "trust the LLM completely" and the suite would be green.
  - Fix: Add one component test: feed QuickPay's 24 codes through pipeline with a `ScriptedLlmClient` that always returns `ANTIFRAUD/HIGH/no hedge`. Assert the 4 named ambiguous codes still come out `LOW + review:true` (V4), and that the validator's `recovery_exhausted` or re-prompt counters fire as expected for the rest.

- **Finding**: V3 (hedge demotion) and V4 (ambiguity patterns) precedence is never tested together. Test #17 tests V3 alone, #18 tests V4 alone, #21 tests V5-after-V4 stickiness — but no test exercises "LLM returns HIGH + 'could be' hedge on a 'Do not honor' code" where V3 wants MEDIUM and V4 wants LOW.
  - Why it matters: A reordering of the 6 validator checks (e.g., refactor swaps step 4 and 5) could let V3's MEDIUM demotion overwrite V4's LOW pin, and no test would notice — QP-008 might come back `MEDIUM, needs_review:false` and only a careful human reading of the gold JSON would catch it. The architecture says V4 wins; nothing enforces it under test.
  - Fix: Add unit test `v4_winsOver_v3_whenBothTriggerOnSameCode`: input mapping `HIGH/ANTIFRAUD` with reasoning containing "could be" and `provider_message: "Do not honor"`. Assert final confidence is `LOW`, not `MEDIUM`, and `reviewReason` starts with `known_ambiguous_pattern`.

- **Finding**: The gold-standard `quickpay_v2.4.result.json` is asserted "byte-for-byte" — but the file contains an LLM-generated `reasoning` field (free text) and a `summary.tokens_used: 12450` figure that depends on the actual model response.
  - Why it matters: Either the gold file is hand-authored (in which case `tokens_used` is a fiction — what does it assert?), or it's recorded (in which case any prompt-template tweak invalidates the byte-for-byte match and the test goes red for non-bug reasons). The strategy doesn't resolve this. Reviewers facing a 24-entry JSON diff in a PR will rubber-stamp it.
  - Fix: Split the assertion. Structural assertions (every code present, exactly 4 reviewed, exact category per code, exact retry_strategy per code, `budget_exhausted: false`) are pinned. `reasoning` strings and `tokens_used` are asserted as "non-empty / positive" only. The PR reviewer then only has to read changes to the structural pins, not 24 paragraphs of prose.

- **Finding**: No test asserts the **batch boundary** invariants. Test #9 says "5 codes → 1 LLM call." With 24 QuickPay codes and batch size 5–10, the partitioning is non-trivial. What happens at batch size exactly 10? Exactly 11 (one batch of 10, one of 1)? Exactly 5? At a 25-code input?
  - Why it matters: A boundary-off-by-one in the batch splitter (e.g., `partition.size() > batchSize` vs `>= batchSize`) would not be caught by the listed tests and would silently route the last code to a 0-code batch or duplicate it across two batches. Test #27 covers n=1, but not the splitter edges.
  - Fix: Add three component tests: input of exactly `batchSize` codes → 1 call; input of `batchSize+1` → 2 calls with sizes (batchSize, 1); input of `2*batchSize` → 2 calls with sizes (batchSize, batchSize). Assert the union of `received()` requests covers each code exactly once.

- **Finding**: Cache-isolation per test is unspecified. Tests #11 and #12 use `@TempDir`, but the production cache path is `logs/cache/<hash>.json` — relative path from the working directory. If any other test runs the real `LlmMapper` without overriding the cache dir, it pollutes the repo's `logs/cache/` and leaks between local dev runs.
  - Why it matters: A developer running `mvn test` once gets a clean state. Running it twice, with a different prompt template in between, gets stale cache hits and the V5 re-validation test may falsely pass (cached LOW response). CI on a fresh checkout passes; local dev fails subtly.
  - Fix: Mandate in the strategy that `LlmMapper` accepts the cache dir as a constructor argument, every test passes `@TempDir`, and there is one explicit test that asserts no file is created outside `@TempDir` after a pipeline run (walk the test's working directory, assert no `logs/cache/` written).

- **Finding**: The fixture `fastbank_v1.0.txt` is structurally too clean to be adversarial — every code has a quoted message immediately after, indentation is uniform, section headers use the same `== Name ==` format throughout, no marketing prose, no typos, no tables.
  - Why it matters: Real provider docs are PDFs converted to text, contain HTML artifacts (`&nbsp;`), inconsistent quote styles (`"smart quotes"` vs `"straight"`), tabs mixed with spaces, footnotes inline (`See section 4.2`), and codes embedded mid-paragraph. The parser will pass on FastBank and then crash on the first real second provider. The strategy explicitly says FastBank "proves the design isn't overfit to QuickPay" — but FastBank is just QuickPay with renamed codes.
  - Fix: Add Fixture 6 — `messy_provider.txt` with: one code in a markdown table row, one with a smart-quoted message (`"Card declined"`), one inside a footnote-style paragraph (`Note: codes 401 and 402 indicate auth issues`), one with `tab`-indented description, one section header in lowercase (`== auth ==`). Either the parser handles it (good) or it emits `Garbage` cleanly (also good) — either outcome must be a tested gold standard.

- **Finding**: The "test isolation" claim depends on a seeded `Random(42)`, but the strategy doesn't say whether the seed is set per-test or process-global. Test #10 asserts "deterministic with seed=42, run twice, assert identical request payloads" — but if `Random` is a static singleton, test execution order changes the sequence consumed.
  - Why it matters: Test #10 passes when run alone, fails when test #9 runs first and consumes random draws. Or worse — passes deterministically in CI (alphabetical order) and fails locally (IDE re-orders). Flake hunts will eat hours.
  - Fix: Strategy must state: `Random(42)` is constructed fresh per `LlmMapper` instance, never static. Add one unit test that runs the batch shuffler twice in the same JVM and asserts the output is identical — and one that runs it in two parallel threads and asserts each thread is independently reproducible.

### MINOR

- **Finding**: There is no test for what happens when `ambiguity_patterns.yaml` is **valid YAML but semantically wrong** — e.g., a pattern with an empty `match` string, or a `match` that matches every possible string (`.*`).
  - Why it matters: An over-broad pattern silently forces every code to `LOW + review`. The current Bootstrap test (#46) only catches uncompilable regex, not regex-that-matches-everything. The exit code of the QuickPay run would still be 1 (everything reviewed), but the headline "exactly 4 reviewed" assertion would catch it. Worth a dedicated test so the failure mode is named.
  - Fix: Add unit test #46b: `ambiguity_patterns.yaml` containing `match: ""` or `match: ".*"` → Bootstrap rejects with a "pattern is dangerously broad" error.

- **Finding**: No assertion that the run-level budget guard counts cache hits as **zero LLM calls**. The cache test (#11) asserts 0 LLM calls but doesn't run alongside a budget-exhausted scenario.
  - Why it matters: A budget guard that increments its counter on cache lookups (a plausible bug — counter sits in the wrong layer) would falsely exhaust the budget on the second cached run and mark codes `budget_exhausted`. Repeated dev runs would silently degrade.
  - Fix: Add one component test: cache pre-populated with all batches, `MAX_LLM_CALLS=0`, run pipeline, assert `summary.llm_calls == 0` and `summary.budget_exhausted == false` and every code mapped.

- **Finding**: `RecordedLlmClient`'s "No recording for <hash>" failure path is not itself tested.
  - Why it matters: If the lookup-or-fail logic is broken and silently returns null, every e2e test would NullPointerException somewhere in Stage 4 with a useless stack trace and no clear "you forgot to record this batch" diagnostic. The whole point of the VCR pattern is the loud failure mode.
  - Fix: One unit test on `RecordedLlmClient` itself: ask it for a hash that isn't on disk in non-record mode, assert it throws AssertionError with a message containing "RECORD_MODE=true".

- **Finding**: Test #19 ("New YAML pattern → triggers without code change") is a tautology as written.
  - Why it matters: It tests that adding an entry to YAML and running the validator picks up the entry — i.e., that the YAML loader reads the file. It doesn't test the **extensibility contract**: that the entry's `match` is applied with case-insensitivity, that the `reason` flows to `review_reason` verbatim, and that a third pattern doesn't shadow the first two via ordering.
  - Fix: Replace #19 with three asserts: a pattern with `(?i)` matches uppercased message; a pattern's `reason` field appears verbatim in `review_reason` after `known_ambiguous_pattern: `; when two patterns both match a message, the test pins which one wins (first match or all match) — the architecture doesn't say, so the test is the spec.

- **Finding**: The component-test count (~14) is light given the surface. R1 Sanitizer alone has 8 listed scenarios (#38–45), Mapper has 7, V5 has 1. That's already 16 — the totals don't add up, or several listed tests are unit tests being miscounted as component.
  - Why it matters: A pyramid that doesn't reconcile with its own inventory is a sign the inventory was written before the count was tallied. When implementation starts and the real count comes to ~22 component tests, someone will trim them under time pressure, and the wrong ones will go.
  - Fix: Re-tally and commit to an exact number per layer in section 1, with each test in section 3 tagged `[unit]`, `[component]`, or `[integration]`. Today many rows don't carry the tag and the reader has to guess from context.

### NIT

- **Finding**: Test naming convention isn't specified. Examples in section 5 use `v4_doNotHonorPatternOverridesConfidentLlmAntifraud` (good, sentence-form), but the inventory uses numbered scenarios (`# 38`, `# 39`) that will likely become `sanitizerTest38` in code.
  - Fix: One line in the strategy: "Test names follow the `<layer>_<expected behaviour>_when_<condition>` pattern. Scenario numbers are docs-only, never appear in code."

---

## Author's decisions

| # | Finding | Adopted? | Action |
|---|---|---|---|
| BLOCKER 1 | 4 ambiguous codes not tested by name | **Yes, fully** | 4 unit tests (`v4_forcesLowReview_forQp005_transactionNotPermitted` etc.) + explicit `containsExactlyInAnyOrder` assertion inside QuickPay e2e. |
| BLOCKER 2 | Hash-keyed hand-authored recordings impossible day-one | **Yes** | Introduce `FixtureLlmClient` keyed by `(provider, batchIndex)`. `RecordedLlmClient` stays as an optional hash-keyed mode for users with API key + `RECORD_MODE=true`. Default tests use `FixtureLlmClient`. |
| MAJOR 1 | No chaos / always-wrong LLM test | **Yes, central** | One component test: `ScriptedLlmClient` returns `HIGH/ANTIFRAUD` for every QuickPay code; assert the 4 ambiguous codes still come back `LOW+review` (V4 proof of work). |
| MAJOR 2 | V3/V4 precedence untested together | **Yes** | Unit test `v4_winsOver_v3_whenBothTriggerOnSameCode`. |
| MAJOR 3 | Byte-for-byte gold assert includes LLM prose + fictional tokens_used | **Yes** | Split assertion. Structural pins (codes present, exact category, retry_strategy, review-count, `budget_exhausted: false`) vs content (non-empty `reasoning`, positive `tokens_used`). |
| MAJOR 4 | Batch boundary tests missing | **Yes** | 3 component tests: n=batchSize, n=batchSize+1, n=2·batchSize. |
| MAJOR 5 | Cache dir leakage between local runs | **Yes** | Strategy mandates: cache dir is constructor arg; every test passes `@TempDir`; one test walks working dir to assert no stray `logs/cache/`. |
| MAJOR 6 | FastBank too clean to be adversarial | **Yes** | New fixture `messy_provider.txt` with smart quotes, tabs, lowercase header, markdown table, footnote-style code reference. New gold standard for it. |
| MAJOR 7 | `Random(42)` ownership unstated → flake risk | **Yes** | Strategy + architecture state: per-instance, never static. Two new tests: cross-instance reproducibility, cross-thread reproducibility. |
| MINOR 1 | No test for overly-broad YAML regex | **Yes** | Unit test #46b. Bootstrap also gains a "dangerously broad" guard. |
| MINOR 2 | Cache hits vs budget guard interaction untested | **Yes** | Component test: pre-populated cache + `MAX_LLM_CALLS=0` → all mapped, 0 calls. |
| MINOR 3 | `RecordedLlmClient`'s missing-fixture path untested | **Yes** | Unit test on client itself. |
| MINOR 4 | Test #19 is tautological | **Yes — rewrite** | Replace with 3 asserts: case-insensitivity, `reason` verbatim, first-match-wins ordering. |
| MINOR 5 | Pyramid numbers don't reconcile | **Yes** | Re-tally and tag every row `[unit]/[component]/[integration]`. New corrected totals: ~43 / ~22 / ~3. |
| NIT | Naming convention not pinned | **Yes** | One line: `<layer>_<expected_behaviour>_when_<condition>`. Numeric IDs docs-only. |

## Net impact

- **Test count**: 49 → ~68 (+19 net).
  - Unit: 32 → 43.
  - Component: 15 → 22.
  - Integration: 2 → 3.
- **New mock implementation**: `FixtureLlmClient` becomes the default for e2e; `RecordedLlmClient` becomes optional (hash-keyed, requires API key for first record).
- **New fixture**: `messy_provider.txt` (and matching gold standard) — adversarial-by-design.
- **Updated assertions**: e2e gold-file diff is no longer byte-for-byte; split into structural (pinned) + content (presence-only).
- **Architecture additions**: Bootstrap rejects overly-broad regex patterns; `Random(42)` is documented as instance-level (never static); cache dir is constructor-injected.
- **Workflow rule**: explicit naming convention; scenario numbers stay in docs only.

These land in [apm_decline_mapper_testing_concept.md](./apm_decline_mapper_testing_concept.md) (rev 2) and [apm_decline_mapper_solution_architecture.md](./apm_decline_mapper_solution_architecture.md) (v3.1).
