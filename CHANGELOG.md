# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added

- M9 libpython-clj2 sklearn interop (`chachaml.interop.sklearn`):
  - `tracked-fit!` — fit a sklearn-compatible model, logging
    training time + model hyperparameters.
  - `tracked-predict` — predict with timing.
  - `evaluate!` — compute + log accuracy/R² metrics.
  - `train-and-evaluate!` — full pipeline: fit, predict, evaluate,
    save artifact, optionally register model.
  - `extract-params` — pull sklearn's `.get_params()`.
  - All Python calls go through a `*bridge*` dynamic var so tests
    run without Python (mock bridge replaces libpython-clj2).
  - `requiring-resolve` for the Python bridge — namespace compiles
    without libpython-clj2 on the classpath.
  - `:python` alias in deps.edn for users with Python + sklearn.
  - `examples/sklearn_iris.clj` — runnable with
    `clojure -M:python:examples -m sklearn-iris`.
  - 8 mock-based tests covering fit, predict, evaluate, the full
    pipeline, and param extraction.

## [0.2.0] - 2026-04-17

MCP server, web UI with local JS deps, and SQLite WAL mode for
concurrent multi-agent access.

### Added

- M7 MCP server (`chachaml.mcp`):
  - JSON-RPC 2.0 over stdio; run via `clojure -M:mcp [db-path]`.
  - 6 tools: `list_runs`, `get_run`, `compare_runs`, `list_models`,
    `get_model`, `get_model_version`.
  - Configurable in `.claude/mcp.json` or any MCP-compatible client.
  - `org.clojure/data.json` added as an optional dep (`:mcp` alias
    for runtime, test/coverage aliases for CI).
  - 18 tests covering handshake, all tools, error paths, and a
    ByteArrayStream integration test.

- SQLite WAL mode + `busy_timeout=5000` enabled on every `open` —
  concurrent readers (UI) never block writers (training agents), and
  simultaneous writers wait instead of failing with SQLITE_BUSY.
  Includes a concurrent-write integration test.
- M8 Web UI (`chachaml.ui.*`):
  - Ring + Reitit + Hiccup + HTMX + Vega-Lite + Tailwind CDN.
  - Start with `clojure -M:ui [db-path] [port]` (default 8080).
  - 5 screens: runs dashboard (auto-refreshes every 10s via HTMX),
    run detail (params, scalar metrics, time-series Vega-Lite charts,
    artifacts), run comparison (param diff + overlaid metric charts),
    model registry, model version detail.
  - JSON API at `/api/{runs,runs/:id,compare,models,models/:name,
    experiments}` — foundation for a future chat-with-data layer.
  - 22 UI tests via `ring-mock` (HTML + JSON routes, 404s, filters).

### Changed

- Cleanup pass after the v0.1.0 review:
  - Stale namespace docstrings in `chachaml.core` and
    `chachaml.store.sqlite` updated to reflect shipped milestones.
  - Collapsed duplicated `status->db`/`stage->db` (and inverse)
    helpers into a single `kw->db`/`db->kw` pair in
    `chachaml.store.sqlite`.
  - `chachaml.serialize/auto-format` no longer has a dead `:nippy`
    branch for strings; behavior is unchanged (strings still go to
    `:nippy`) but documented as intentional.
  - `chachaml.repl/pad` reduced to a one-line `format` call.
  - `chachaml.schema/validate` docstring tightened (no behavior
    change).

### Internal

- New shared test fixture `chachaml.test-helpers/with-fresh-store`,
  used via `(use-fixtures :each h/with-fresh-store)` across
  `core_test`, `registry_test`, `repl_test`, `tracking_test`, and
  `examples_test`. Drops ~30 lines of inline `(with-fresh-store
  (fn [] …))` boilerplate per file.
- New `use-store!-returns-resolved-store` test for the
  store-value path.

## [0.1.0] - 2026-04-16

First feature-complete release covering the full minimal MLOps loop:
run tracking, artifacts, model registry, tracking macro, REPL helpers.

### Added

- M0 project skeleton: dual build (deps.edn + project.clj), Babashka task
  runner (`bb.edn`), `tools.build` entry points (`build.clj`).
- Quality tooling: `clj-kondo` config (warning-level fail), `cljfmt`
  config, kaocha runner (`tests.edn`), `cloverage` 85% line gate.
- Namespace skeletons for `chachaml.{core,context,env,serialize,
  registry,tracking,repl,schema}` and `chachaml.store.{protocol,sqlite}`.
- Baseline load test asserting every namespace compiles.
- GitHub Actions CI: lint + format + 4-job test matrix
  (`{deps,lein} × JDK {17,21}`) + coverage gate.
- Process artifacts: `CONTRIBUTING.md`, `LICENSE` (MIT),
  `.github/PULL_REQUEST_TEMPLATE.md`, ADRs 0001–0004.
- M1 storage: `chachaml.store.sqlite` implements `RunStore` and
  `Lifecycle` against either a file-backed or in-memory SQLite
  database. Schema migrates idempotently on every `open`. Tests cover
  CRUD, EDN value round-tripping, query filters, and tempfile
  persistence across opens.
- M2.1 `chachaml.env`: best-effort capture of git SHA/branch/dirty
  state, JVM, OS, user, and Clojure version.
- M2.2 `chachaml.context`: `*store*` and `*run*` dynamic vars plus
  a delay-initialized default SQLite store at `./chachaml.db`.
- M2.3–2.5 `chachaml.core` public API: `use-store!`, `with-store`,
  `start-run!`, `end-run!`, `with-run`, `log-params`, `log-param`,
  `log-metrics`, `log-metric`, `runs`, `run`, `last-run`. Exceptions
  inside `with-run` mark the run `:failed` and re-throw; completed
  runs are `tap>`-ed.
- `chachaml.schema`: Malli schemas for run lifecycle and logging
  inputs (`Status`, `Run`, `StartRunOpts`, `Params`, `Metrics`,
  `MetricRow`, `QueryFilters`) plus a `validate` helper.
- `doc/USING-LOCALLY.md`: how to depend on a local checkout from
  another Clojure project (`:local/root`, `lein install`, lein
  `checkouts/`).
- Runnable ML examples under `examples/` (added to the test classpath
  via the `:examples` alias):
  - `linear-regression` — gradient-descent univariate fit on synthetic
    data; logs per-epoch loss and final weight errors.
  - `kmeans` — Lloyd's algorithm on 2D Gaussian blobs; logs
    per-iteration inertia and final cluster sizes.
  Each has a `run-experiment!` fn (called by tests against an
  in-memory store) and a `-main` (called by `clojure -M:examples
  -m <name>`).
- M3 artifacts:
  - `chachaml.serialize`: multimethod-dispatched encoding for
    `:nippy` (default), `:edn`, `:bytes`, and `:file` formats.
    Auto-detects `byte[]`/`File` and falls back to `:nippy`.
  - SQLite `ArtifactStore` impl with filesystem-backed bytes under
    `<db>-artifacts/<run-id>/<name>` (default
    `./chachaml-artifacts/`). SHA-256 hash + size captured. Unique
    `(run_id, name)` index rejects duplicates.
  - `chachaml.core/log-artifact`, `log-file`, `load-artifact`,
    `list-artifacts`. `(run id)` now also includes `:artifacts`.
  - `Artifact` and `ArtifactFormat` Malli schemas.
  - In-memory mode now creates a per-store temp artifact directory
    that is removed on `close!`.
  - Examples now persist their trained models as nippy artifacts;
    tests round-trip and assert on the loaded values.
  - Property-based serialize round-trip (`test.check`) covers
    arbitrary nested EDN values for `:nippy` and `:edn`.
- ADR-0005: filesystem-backed artifacts (rationale for keeping bytes
  outside SQLite).
- M4 `chachaml.tracking/deftracked`: `defn`-shaped macro that wraps
  the body in `with-run`. Supports docstring + opts map (`:experiment`,
  `:name`, `:tags`); a `:tracked-fn` tag carrying the qualified symbol
  is auto-added. Nests under existing runs. clj-kondo `:lint-as`
  config registers it as a defn-like macro.
- M5 model registry:
  - `models` and `model_versions` tables added to the SQLite schema.
  - SQLite `ModelRegistry` impl with atomic stage transitions in a
    transaction.
  - `chachaml.registry` public API: `register-model`, `models`,
    `model`, `model-versions`, `promote!`, `load-model` (with
    `:version`/`:stage` selectors; default = latest production).
  - `Model`, `Version`, `Stage`, `RegisterOpts` Malli schemas.
  - Property-based test asserting at most one `:production` version
    per model survives arbitrary add/promote sequences.
  - ADR-0006 records production-stage exclusivity.
  - Both ML examples now register their trained model as a
    `:staging` version of `linear-regression-baseline` /
    `kmeans-baseline`. Tests round-trip through the registry too.
- M6 REPL helpers in `chachaml.repl`:
  - `runs-table` — compact one-row-per-run table of recent runs
  - `inspect` — polymorphic pretty-printer for runs (by id or map),
    models (by name or map), and model versions
  - `compare-runs` — diff params + latest metric values across N
    runs, returns `{:runs … :params {…} :metrics {…}}` with `:same`,
    `:differ`, `:partial` partitions
  - `print-comparison` — render the `compare-runs` map for humans
  - 10 tests covering empty store, multi-run diff, partial-key
    detection, version inspection, model fallback by string id
