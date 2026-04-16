# chachaml — Specification

## Vision

A practical, REPL-first MLOps library for Clojure. Single-process and
small-team scale. Optimized for the Clojure REPL workflow first; CLI/UI/
services come later.

## Non-goals (v0.x)

- Distributed training orchestration
- Multi-tenant SaaS
- Heavy DAG pipelines (Airflow-replacement)
- Replacing MLflow at scale

## Personas

1. Solo Clojure ML practitioner at REPL with libpython-clj2
2. Clojure data team running batch jobs / notebooks / services with shared
   registry
3. AI agent consuming runs/models programmatically (later via MCP)

## Core Concepts

| Concept           | Definition                                                     |
| ----------------- | -------------------------------------------------------------- |
| **Run**           | One execution. Has params, metrics, tags, artifacts, env.      |
| **Experiment**    | Named string label grouping runs. No schema.                   |
| **Param**         | Immutable k/v set during a run (config, hyperparam).           |
| **Metric**        | Numeric, time-stepped k/v (loss curves, accuracy@epoch).       |
| **Tag**           | Mutable string k/v on a run.                                   |
| **Artifact**      | Binary blob attached to a run (model, file, plot).             |
| **Model**         | Named entry in registry.                                       |
| **Model Version** | Immutable version → run + artifact, with a stage.              |
| **Stage**         | `:none` / `:staging` / `:production` / `:archived`.            |
| **Store**         | Backend persisting everything. SQLite default.                 |

## Data Model (SQLite)

- `runs` — id, experiment, name, status, start/end time, error, tags(EDN),
  env(EDN), parent_run_id
- `params` — run_id, key, value(EDN). PK (run_id, key).
- `metrics` — run_id, key, value(REAL), step(INT), timestamp
- `artifacts` — id, run_id, name, path, content_type, size, hash, created_at
- `models` — name (PK), description, created_at
- `model_versions` — (model_name, version) PK, run_id, artifact_id, stage,
  description, created_at

## Public API surface (target)

```clojure
;; setup (optional — auto-defaults to ./chachaml.db)
(ml/use-store! {:type :sqlite :path "..."})

;; runs
(ml/with-run opts & body)         ; macro
(ml/start-run! opts) (ml/end-run! run status & [error])
(ml/log-params m) (ml/log-param k v)
(ml/log-metrics m) (ml/log-metric k v & [step])
(ml/log-tags m)    (ml/log-tag k v)
(ml/set-name! name)

;; artifacts
(ml/log-artifact name value & [opts])     ; auto-serialize
(ml/log-file name path)                   ; copy file
(ml/load-artifact run-id name)            ; deserialize
(ml/list-artifacts run-id)

;; querying
(ml/runs)  (ml/runs filters)  (ml/run id)  (ml/last-run)
(ml/compare-runs ids)         ; -> {:params-diff … :metrics-diff …}

;; registry
(ml/register-model name {:artifact "model" :stage :staging})
(ml/models) (ml/model name) (ml/model-versions name)
(ml/promote! name version stage)
(ml/load-model name & [{:keys [version stage]}])

;; tracking
(ml/deftracked fn-name [args] body)

;; repl
(ml/inspect run-id)
```

## REPL ergonomics requirements

- Auto-init store on first call — no setup needed
- Re-evaluating `(with-run …)` doesn't leak open runs
- `(ml/last-run)` always works
- Returns plain Clojure maps, never opaque objects
- `tap>` integration on run completion
- Exception in `with-run` → run marked `:failed`, exception re-thrown

## Non-functional requirements

- Pure-Clojure core (Python interop is a separate layer)
- Store protocol allows swapping backends without API changes
- Both `deps.edn` and `project.clj` supported
- Zero config for "just track stuff" path
