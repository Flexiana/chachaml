<!-- Thanks for the PR. Fill in what's relevant; delete what isn't. -->

## What and why

<!-- One paragraph: what does this change do, and what problem does it solve? -->

## How

<!-- Sketch of the approach. Link to ADRs if architectural. -->

## Quality checklist

- [ ] Public vars have docstrings
- [ ] Public API additions have Malli schemas in `chachaml.schema`
- [ ] Tests added or updated; bug fixes include a regression test
- [ ] `bb lint` clean
- [ ] `bb fmt-check` clean
- [ ] `bb coverage` passes (≥ 85% line)
- [ ] Both `clojure -M:test` and `lein test` pass locally
- [ ] `CHANGELOG.md` updated under `## [Unreleased]` (for user-facing changes)
- [ ] New ADR added (for architectural changes)

## Risk

<!-- What could break? What's the blast radius if this is wrong? -->
