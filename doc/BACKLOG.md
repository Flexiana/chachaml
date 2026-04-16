# chachaml — Backlog

## M0 — Project skeleton + quality foundation

Full quality stack must be wired before feature work begins. See
`CONTRIBUTING.md` for the contributor-facing summary.

- M0.1  `deps.edn`, `project.clj`, `bb.edn`, `.gitignore`
- M0.2  `.clj-kondo/config.edn` (warning-level fail in CI)
- M0.3  `cljfmt.edn`
- M0.4  `tests.edn` (kaocha)
- M0.5  `build.clj` (tools.build skeleton)
- M0.6  Namespace skeletons under `src/chachaml/` per layering in
  `CLAUDE.md`
- M0.7  Load test: `test/chachaml/loads_test.clj`
- M0.8  GitHub Actions `.github/workflows/ci.yml` — 4-job matrix
  (`{deps, lein} × JDK {17, 21}`) plus lint + format + coverage gate
- M0.9  `CONTRIBUTING.md`, `CHANGELOG.md`, `LICENSE` (MIT),
  `.github/PULL_REQUEST_TEMPLATE.md`, issue templates
- M0.10 Initial ADRs `0001`–`0004`

**Done when:**

- All 4 CI jobs green on a no-op PR
- `bb test`, `bb lint`, `bb fmt-check`, `bb coverage` all run locally
- `clj-kondo --lint src test --fail-level warning` reports zero issues
- `clojure -M:fmt-check` reports no drift

## M1 — Storage Foundation

- [x] M1.1 Store protocols (`RunStore`, `ArtifactStore`, `ModelRegistry`,
  `Lifecycle`) — landed in M0
- [x] M1.2 SQLite schema + idempotent migrations
- [x] M1.3 SQLite `RunStore` (CRUD: runs, params, metrics)
- [x] M1.4 SQLite `ArtifactStore` — landed in M3
- [x] M1.5 SQLite `ModelRegistry` — landed in M5
- [x] M1.6 Store unit tests (in-memory + tempfile)

`tags` are stored on the run as inline EDN (no separate table). A
`log-tags` API for incremental updates is deferred until needed.

## M2 — Core API

- [x] M2.1 `chachaml.env` capture (git, jvm, os, clojure)
- [x] M2.2 `*store*` / `*run*` dynamic vars + default-store delay
- [x] M2.3 `start-run!` / `end-run!` / `with-run`
- [x] M2.4 `log-params` / `log-metrics` / `log-metric`
- [x] M2.5 Exception → failed run
- [x] M2.6 Acceptance test: full REPL-session integration test

## M3 — Artifacts

- [x] M3.1 Serialization multimethod: `:bytes` / `:file` / `:edn` / `:nippy`
  (default), with auto-detection
- [x] M3.2 `log-artifact` / `log-file`
- [x] M3.3 `load-artifact` round-trip (incl. property-based test)
- [x] M3.4 SHA-256 hash + size captured per artifact

## M4 — Tracking Macro

- [x] M4.1 `deftracked` (`defn`-shaped; opts map; auto `:tracked-fn` tag)
- [x] M4.2 Nested-run behavior — `with-run` already propagates
  `:parent-run-id`, so `deftracked` nests for free
- [x] M4.3 Macro-expansion + behavior tests

We do **not** auto-log function arguments as params: most ML inputs
(datasets, models) are too large or not EDN-serializable. Users log
explicitly inside the body.

## M5 — Model Registry

- [x] M5.1 `register-model` (idempotent model row; creates version
  from current run + named artifact)
- [x] M5.2 `promote!` with stage transitions, auto-archives previous
  `:production` (ADR-0006)
- [x] M5.3 `load-model` with `:version` / `:stage` selectors; default
  is latest production
- [x] M5.4 Tests including property-based stage-exclusivity invariant

## M6 — REPL Ergonomics

- [x] M6.1 `last-run`, `runs` (M2) + `inspect` (M6)
- [x] M6.2 `compare-runs` + `print-comparison`
- [x] M6.3 `tap>` on run completion (landed in M2 `end-run!`)
- [x] M6.4 `runs-table`, `inspect-run/-model/-version` pretty-printers

## Deferred (post-v0.1)

- UI server (Ring + HTMX + Vega-Lite)
- libpython-clj2 wrappers (sklearn / xgboost / torch)
- MCP server
- Pipelines / DAG
- Postgres / S3 backends
- Drift detection, alerts
- Skills, OpenAI-compatible endpoint

## Acceptance criteria per milestone

Every milestone after M0, in addition to its specific goals below, must
also clear the standing **quality bar** (from `CONTRIBUTING.md`):

- Public vars added have docstrings
- Public API additions have Malli schemas in `chachaml.schema`
- New behavior has unit tests; new invariants have property-based tests
- CI stays green; coverage does not regress
- `CHANGELOG.md` updated under `## [Unreleased]`

| Milestone | Done when                                                          |
| --------- | ------------------------------------------------------------------ |
| M0        | All 4 CI jobs green; full quality stack wired                      |
| M1        | All store protocol methods exercised by tests (incl. tempfile      |
|           | SQLite + a concurrency test for metric writes)                     |
| M2        | A REPL session can start a run, log params/metrics, end cleanly;   |
|           | nested `with-run` and exception → failed run both tested           |
| M3        | Artifacts round-trip across `:bytes`/`:file`/`:edn`/`:nippy`       |
|           | (property-based)                                                   |
| M4        | `deftracked` fn auto-creates run; nests under existing run;        |
|           | macro-expansion tests in place                                     |
| M5        | Promote latest version of a model and load by `:stage :production`;|
|           | production-stage exclusivity property-tested                       |
| M6        | `(ml/last-run)`, `(ml/compare-runs …)` work without ceremony;      |
|           | tested without explicit `use-store!` (default-store path)          |
