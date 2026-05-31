# Work log

Chronological list of steps actually executed on this project, from reading the assignment to the green-on-master final state. Numbered sequentially across all phases — not the same numbering as the 8 implementation steps inside the build, nor the W1–W9 post-submission workflow.

## Design / documentation

1. Read the assignment (PDF).
2. Drafted the implementation prompt (rev 1) — self-contained spec.
3. Drafted the architecture (v1) — 4 stages + budget guard.
4. Wrote the **architecture review prompt** (reusable template).
5. Ran the arch review with an independent Opus agent → findings + scorecard.
6. Adopted the arch-review findings → architecture v3, prompt rev 2.
7. Drafted the testing concept (rev 1) — 49-test pyramid.
8. Wrote the **test-strategy review prompt** (reusable template).
9. Ran the test review with an independent Opus agent → findings + scorecard.
10. Adopted the test-review findings → testing rev 2, architecture v3.1, prompt rev 3 (fully self-contained).

## Repository + scaffold

11. Created `github.com/akazimirskiy/apm-decline-mapper`, set up SSH, force-pushed an initial scaffold over GitHub's auto-init commit.

## Implementation (8 steps, ~3 hours, 8 commits)

12. **Step 1** — Bootstrap (env + YAML + dangerously-broad regex guard). 17 tests.
13. **Step 2** — Parser (state machine + `ParseOutcome` sealed type + garbage detection). 15 tests.
14. **Step 3** — Enricher (deterministic system-prompt builder). 8 tests.
15. **Step 4** — `LlmClient` + 3 implementations (`AnthropicLlmClient`, `ScriptedLlmClient`, `FixtureLlmClient`) + `LlmMapper` (cache, per-call `Random(42)`). 15 tests.
16. **Step 5** — `ResponseSanitizer` (R1 defence layer). 11 tests.
17. **Step 6** — `Validator` V1–V5 + headline **chaos test**. 16 tests.
18. **Step 7** — `BudgetGuard` + `Pipeline` wiring. 4 tests.
19. **Step 8** — End-to-end tests + fixtures + initial `README.md` + `samples/`. 2 tests.
    
    Subtotal: **88/88 green**.

## Packaging + audit

20. Copied the design trail (assignment PDF + 8 review/design `.md` files) into `docs/` with numbered prefixes and an index.
21. Renamed the root package `com.bp.declinemapper` → `com.kazimir.declinemapper`, updated `pom.xml`.
22. Pinned the base package name in the implementation prompt with a "replace for your org" note so the prompt is reusable.

## Code review + pre-submission workflow (7 steps)

23. Wrote the **code-review prompt** (reusable template).
24. Ran the code review with an independent Opus agent → 1 BLOCKER + 5 MAJOR + 7 MINOR + 1 NIT.
25. **Step 9** — Applied all 13 accepted findings. Added 2 regression tests locking in the partial-write BLOCKER fix.
26. **Step 10** — `mvn package` + CLI smoke test with a fake API key (Anthropic returned HTTP 401, confirming the request shape and wiring are correct end-to-end).
27. **Step 11** — Added GitHub Actions CI (`.github/workflows/test.yml`) running `mvn test` and `mvn package` on push/PR.
28. **Step 12** — Refactored `AnthropicLlmClient` to use an `HttpSender` seam and added 8 retry unit tests (502/429/503×3/400/401/429-then-400/200/IOException).
29. **Step 13** — Added `messy_provider.txt` adversarial fixture + 3 parser tests against it.
30. **Step 14** — README hygiene: replaced `cat .env | xargs` with `set -a; source .env; set +a`; added disclaimer that `samples/quickpay_v2.4.result.json` has synthetic reasoning text.
31. **Step 15** — Added a **Pre-submission workflow (W1–W9)** section to the implementation prompt so the post-build discipline is reusable as a template for future projects.

## Final state

- **13 commits** on `master` at `github.com/akazimirskiy/apm-decline-mapper`
- **101/101 tests** green (66 unit / 32 component / 3 integration)
- **0 LLM tokens** consumed in CI
- **11 design documents** under `docs/` (00–10 plus this log and an index)
- **3 reusable review prompts** (architecture, test-strategy, code)
- **Pre-submission workflow (W1–W9)** baked into the implementation prompt for reuse on future projects
- **GitHub Actions CI** configured on push and pull-request

## Reusable artefacts (apply to next project)

- `docs/01_implementation_prompt.md` — self-contained implementation spec template + Pre-submission workflow.
- `docs/04_arch_review_prompt.md` — independent architecture review template.
- `docs/07_test_review_prompt.md` — independent test-strategy review template.
- `docs/09_code_review_prompt.md` — independent post-implementation code review template.

For a new project of similar shape: copy these four prompts, replace the domain-specific bits in `01_*`, and run the same flow (draft → arch review → test review → build → code review → pre-submission workflow).
