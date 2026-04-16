# Changelog

All notable changes to this project will be documented in this file.

The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

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
