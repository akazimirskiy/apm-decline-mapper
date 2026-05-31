# Design Documents

Self-contained trail of how the implementation was reasoned out — assignment → prompts → architecture → testing → independent reviews → resolutions. Numbered for reading order; you don't have to read all of them to understand the code, but each file stands alone if you do.

| # | File | What it is |
|---|---|---|
| 00 | [`00_assignment.pdf`](./00_assignment.pdf) | The original take-home assignment (verbatim). |
| 01 | [`01_implementation_prompt.md`](./01_implementation_prompt.md) | Self-contained spec for the implementer — stack, architecture, defence layers, testing requirements, deliverables. **Rev 3** (post both reviews). |
| 02 | [`02_solution_architecture.md`](./02_solution_architecture.md) | Architecture, data contracts, file layout, failure-handling matrix. **v3.1** (post both reviews). |
| 03 | [`03_testing_concept.md`](./03_testing_concept.md) | Pyramid, mock seam, test inventory (88 tests), gold-file strategy, naming convention. **Rev 2** (post test review). |
| 04 | [`04_arch_review_prompt.md`](./04_arch_review_prompt.md) | Reusable prompt for a hard architecture review. |
| 05 | [`05_arch_review_findings.md`](./05_arch_review_findings.md) | Findings of the independent arch review + adoption table. |
| 06 | [`06_arch_review_resolution.md`](./06_arch_review_resolution.md) | Per-finding prose: how each was resolved in the impl. |
| 07 | [`07_test_review_prompt.md`](./07_test_review_prompt.md) | Reusable prompt for a hard test-strategy review. |
| 08 | [`08_test_review_findings.md`](./08_test_review_findings.md) | Findings of the test review + adoption table. |
| 09 | [`09_code_review_prompt.md`](./09_code_review_prompt.md) | Reusable prompt for a hard post-implementation code review. |
| 10 | [`10_code_review_findings.md`](./10_code_review_findings.md) | Findings of the code review + adoption table. |
| 11 | [`11_work_log.md`](./11_work_log.md) | Chronological log of every step actually executed across all phases. |

## Workflow that produced these

1. Read the assignment (00).
2. Drafted the implementation prompt (01 rev 1).
3. Drafted the architecture (02 v1).
4. Independent **arch review** (04 → 05). Resolved findings (06). Architecture → v3.1; prompt → rev 2.
5. Drafted testing concept (03 rev 1).
6. Independent **test review** (07 → 08). Resolved findings. Testing → rev 2; arch → v3.1; prompt → rev 3.
7. Built code under rev 3 — 8 commits, 88 tests green, 0 LLM tokens consumed.
8. Independent **code review** (09 → 10). Resolved 1 BLOCKER + 5 MAJOR + 7 MINOR + 1 NIT. Tests → 98 green (+ 2 regression tests for the BLOCKER fix, + 8 retry tests for AnthropicLlmClient, + 3 messy-fixture parser tests).
9. Built and smoke-tested the shaded jar; added GitHub Actions CI; tightened README env-loading.

## Note on count

The PDF (00) and the early drafts of 01–03 say "24 codes" for QuickPay; the real PDF and the parsed fixture contain **26** (10+4+5+4+3). The implementation and tests use 26 against the actual fixture. The earlier docs were never reconciled — historical artifact, kept here for the audit trail.
