# APM Decline Code Mapper

CLI that ingests a payment provider's error-code documentation (raw text) and
emits a JSON mapping of every code to one of seven internal decline categories,
with confidence, reasoning, retry strategy, and a human-review flag.

The LLM is used as a **controlled component inside an agent pipeline** — not as
a single black-box call. Eight deterministic defence layers wrap every LLM
interaction.

## Status

Pre-implementation. Scaffold only — no code yet.

## Stack

- Java 21, Maven
- Jackson (JSON), SnakeYAML (config)
- `java.net.http.HttpClient` (no SDK dependency on Anthropic)
- JUnit 5 + AssertJ

## Quickstart (target)

```bash
cp .env.example .env
# fill in ANTHROPIC_API_KEY

mvn package
java -jar target/decline-mapper.jar \
  fixtures/quickpay_v2.4.txt "QuickPay Global" "2.4" result.json
```

## Architecture (planned)

Bootstrap → Parser → Enricher → Mapper (LLM) → Validator → result.json

Each stage is independently testable. The LLM is called only in two places
(Parser fallback for ambiguous chunks, Mapper for the main categorization),
both bounded by a run-level budget guard.

Full design is captured in the design notes under `../*.md` of this project's
parent directory.

## Tests

Zero LLM tokens in CI. Three `LlmClient` implementations: production
(`AnthropicLlmClient`), `ScriptedLlmClient` for component tests,
`FixtureLlmClient` for end-to-end against canned responses keyed by
`(provider, batchIndex)`.

```bash
mvn test
```

## License

Internal — take-home assignment.
