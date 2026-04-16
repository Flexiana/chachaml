# 6. At most one `:production` version per model

Date: 2026-04-16

## Status

Accepted.

## Context

Each model in the registry has multiple versions, each carrying a
stage: `:none`, `:staging`, `:production`, or `:archived`. Two
defensible answers exist for what `:production` means:

1. **Loose**: a label any version can carry; multiple versions can be
   `:production` simultaneously. Promotion is just a stage update.
2. **Strict (exclusive)**: at most one version per model is
   `:production` at a time. Promoting another version implicitly
   moves the previous production to `:archived`.

The MLflow convention (loose) reflects historical caution about
implicit transitions. The strict convention reflects the actual
deployment reality: there is one production model serving traffic;
promoting a new one demotes the old.

## Decision

Production stage is **exclusive per model**. Promoting a version to
`:production` (via `chachaml.registry/promote!` or by passing
`:stage :production` to `register-model`) atomically sets any
previously-production version of the same model to `:archived`.

The transition happens inside a single SQLite transaction in
`SQLiteStore.-set-stage!` and `-create-version!`. Other stages
(`:none`, `:staging`, `:archived`) have no exclusivity rule.

`(load-model name)` defaults to "the latest production version" —
which is unambiguous under this rule.

## Consequences

- The deployment story is clear: there is one `:production` model.
  `(load-model name)` always returns it (or nil if never promoted).
- Auditing remains intact: archived versions are not deleted, just
  re-staged. History is preserved in `model_versions`.
- A property test (`registry-test/production-stage-exclusivity-property`)
  asserts the invariant survives arbitrary `add` + `promote`
  sequences.
- This is a one-way ratchet for users used to MLflow semantics. We
  consider the safety win worth the divergence.
