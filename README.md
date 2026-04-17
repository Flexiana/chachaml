# chachaml

A practical, REPL-first MLOps library for Clojure.

Track experiments, store artifacts, manage models, run pipelines, set alerts, and chat with your data — all from the REPL, a web UI, or an LLM agent.

## Why chachaml?

MLflow is great but it's Python-native. If you do ML in Clojure (with or without libpython-clj2), you need something that feels like Clojure: plain maps, dynamic vars, REPL-driven, no ceremony.

chachaml gives you:

- **Zero-setup tracking** — `(with-run {} (log-metric :acc 0.9))` just works, SQLite default
- **Artifacts + model registry** — save models, promote to production, load by stage
- **Pipelines** — chain steps with result passing, each step a tracked run
- **Alerts** — "notify me if accuracy drops below 0.9"
- **Web UI** — runs dashboard, metric charts (Vega-Lite), model registry, search, CSV export
- **MCP server** — 16 tools for LLM agents (Claude, GPT) to query your experiments
- **Chat-with-data** — ask "which experiment has the best accuracy?" and get an answer backed by real data
- **Python interop** — tracked sklearn wrappers via libpython-clj2

## Quick start

### deps.edn

```clojure
{:deps {chachaml/chachaml {:git/url "https://github.com/flexiana/chachaml"
                           :git/sha "LATEST_SHA"}}}
```

### Leiningen

```clojure
;; Clone locally, then:
;; cd chachaml && lein install
[chachaml "0.4.0"]
```

### 30-second REPL session

```clojure
(require '[chachaml.core :as ml])

;; Track a run — auto-creates ./chachaml.db on first call
(ml/with-run {:experiment "quickstart"}
  (ml/log-params {:lr 0.01 :epochs 50})
  (ml/log-metric :accuracy 0.94)
  (ml/log-artifact "model" {:weights [1.0 2.0] :bias 0.3}))

;; Query it back
(ml/last-run)
;; => {:id "abc..." :experiment "quickstart" :status :completed ...}

(ml/load-artifact (:id (ml/last-run)) "model")
;; => {:weights [1.0 2.0] :bias 0.3}
```

## Core features

### Run tracking

```clojure
(require '[chachaml.core :as ml])

(ml/with-run {:experiment "iris" :name "baseline" :tags {:author "jiri"}}
  ;; Params (immutable per run)
  (ml/log-params {:lr 0.01 :epochs 100 :model "logistic-regression"})

  ;; Metrics (time-series with step)
  (doseq [epoch (range 100)]
    (ml/log-metric :loss (/ 1.0 (inc epoch)) epoch))
  (ml/log-metric :accuracy 0.94)

  ;; Artifacts (any Clojure value, serialized via nippy)
  (ml/log-artifact "model" trained-model)
  (ml/log-artifact "report" {:summary "ok"} {:format :edn})

  ;; Dataset metadata
  (ml/log-dataset! {:role "train" :n-rows 1000 :n-cols 10
                    :features [:age :income :score]}))
```

Exceptions inside `with-run` mark the run `:failed` and re-throw. Completed runs are `tap>`-ed for Portal/Reveal.

### Querying

```clojure
(ml/runs)                                    ;; recent runs
(ml/runs {:experiment "iris" :status :completed})
(ml/run "abc123")                            ;; full detail + params + metrics + artifacts
(ml/last-run)                                ;; most recent

;; Metric-based search
(ml/search-runs {:metric-key :accuracy :op :> :metric-value 0.9})
(ml/best-run {:experiment "iris" :metric :accuracy})
(ml/best-run {:experiment "iris" :metric :loss :direction :min})

;; Export to flat maps (for CSV/reporting)
(ml/export-runs {:experiment "iris"})
;; => [{:id "..." :lr 0.01 :accuracy 0.94 ...} ...]
```

### Mutable tags + notes

```clojure
;; Annotate runs after they complete
(ml/add-tag! run-id :reviewed "true")
(ml/set-note! run-id "## Analysis\nThis run has $R^2 = 0.98$.")

;; Tags are merged into the run map
(:tags (ml/run run-id))
;; => {:author "jiri" :reviewed "true" :note "## Analysis\n..."}
```

### Artifacts

```clojure
;; Save any Clojure value (default: nippy serialization)
(ml/log-artifact "model" {:weights w :bias b})

;; Save as human-readable EDN
(ml/log-artifact "config" {:lr 0.01} {:format :edn})

;; Save a file
(ml/log-file "plot.png" "/tmp/accuracy-curve.png")

;; Save a structured table (rendered in the UI)
(ml/log-table "confusion-matrix"
              {:headers ["pred-0" "pred-1"]
               :rows [[45 5] [3 47]]})

;; Load back
(ml/load-artifact run-id "model")
;; => {:weights [...] :bias ...}
```

### Model registry

```clojure
(require '[chachaml.registry :as reg])

;; Register inside a run
(ml/with-run {:experiment "iris"}
  (ml/log-artifact "model" trained-model)
  (reg/register-model "iris-classifier"
                      {:artifact "model" :stage :staging}))

;; Promote
(reg/promote! "iris-classifier" 1 :production)

;; Load latest production model
(reg/load-model "iris-classifier")

;; Compare versions
(reg/diff-versions "iris-classifier" 1 2)
```

### deftracked

```clojure
(require '[chachaml.tracking :refer [deftracked]])

(deftracked train-model
  "Each call auto-creates a run."
  [config data]
  (ml/log-params config)
  (let [model (fit data config)]
    (ml/log-metric :accuracy (evaluate model))
    (ml/log-artifact "model" model)
    model))

(train-model {:lr 0.01} training-data)
;; => creates a run named "train-model", logs everything, completes on return
```

### Pipelines

```clojure
(require '[chachaml.pipeline :as pipe])

(pipe/run-pipeline! "train-and-deploy"
  [{:name "preprocess" :fn (fn [_ctx]
                             (ml/log-params {:method "standardize"})
                             preprocessed-data)}
   {:name "train"      :fn (fn [ctx]
                             (let [data (:prev-result ctx)]
                               (ml/log-params {:lr 0.01})
                               (train data)))}
   {:name "evaluate"   :fn (fn [ctx]
                             (let [model (:prev-result ctx)]
                               (ml/log-metric :accuracy (eval-model model))
                               model))}
   {:name "register"   :fn (fn [ctx]
                             (ml/log-artifact "model" (:prev-result ctx))
                             (reg/register-model "my-model"
                               {:artifact "model" :stage :staging}))}])
```

Each step runs inside a tracked `with-run`. Results chain via `:prev-result`.

### Alerts

```clojure
(require '[chachaml.alerts :as alerts])

(alerts/set-alert! "accuracy-drop"
  {:experiment "production"
   :metric-key :accuracy
   :op         :<
   :threshold  0.9})

;; Check after training runs
(alerts/check-alerts!)
;; => [{:alert-name "accuracy-drop" :metric-value 0.85 :run-id "..." ...}]

(alerts/alert-history "accuracy-drop")
```

### Batch metric logging

```clojure
;; For tight loops with thousands of steps — buffer metrics in memory,
;; flush once on exit (reduces SQL round-trips ~100x)
(ml/with-run {:experiment "training"}
  (ml/with-batched-metrics
    (dotimes [epoch 10000]
      (ml/log-metric :loss (/ 1.0 (inc epoch)) epoch))))
```

### Experiment metadata

```clojure
(ml/create-experiment! "iris"
  {:description "Iris classification experiments"
   :owner "jiri"})

(ml/experiments)
;; => [{:name "iris" :description "..." :owner "jiri" ...}]
```

## Web UI

Start the dashboard:

```bash
clojure -M:ui              # default localhost:8080, ./chachaml.db
clojure -M:ui my.db 9090   # custom DB + port
```

**Pages:**

| Page | URL | What it shows |
|---|---|---|
| Runs | `/runs` | Sortable table, experiment/status filters, auto-refresh (10s), CSV export |
| Run detail | `/runs/:id` | Params, metric charts (Vega-Lite), datasets, artifacts (image preview), markdown notes with LaTeX math |
| Compare | `/compare?ids=a,b` | Param diff table, overlaid metric curves |
| Models | `/models` | Registry with version counts, stage badges |
| Model detail | `/models/:name` | Version history, run links, markdown description |
| Experiments | `/experiments` | Experiment metadata, run counts |
| Search | `/search` | Find runs by metric thresholds |
| Chat | `/chat` | Ask questions about your data (requires API key) |

All JS dependencies (HTMX, Vega-Lite, Tailwind, marked.js, KaTeX) are bundled locally — no CDN requests.

### JSON API

Every UI view has a corresponding JSON endpoint:

```
GET  /api/runs?experiment=...&status=...
GET  /api/runs/:id
GET  /api/compare?ids=a,b
GET  /api/search?metric_key=accuracy&op=>&metric_value=0.9
GET  /api/export?experiment=...          (CSV download)
GET  /api/models
GET  /api/models/:name
GET  /api/experiments
GET  /api/artifacts/:id/download
GET  /api/tags/:id
POST /api/tags/:id                       {"key":"k","value":"v"}
POST /api/note/:id                       {"note":"markdown..."}
GET  /api/datasets/:id
GET  /api/diff/:name/:v1/:v2
POST /api/chat                           {"question":"...","provider":"anthropic","api_key":"sk-..."}
```

## MCP server (for LLM agents)

chachaml exposes 16 tools via the Model Context Protocol (JSON-RPC over stdio). Any MCP-compatible client (Claude Code, VS Code + Continue, etc.) can query your experiments.

```bash
clojure -M:mcp              # default ./chachaml.db
clojure -M:mcp path/to.db   # custom DB
```

Configure in Claude Code's `.claude/mcp.json`:

```json
{
  "chachaml": {
    "command": "clojure",
    "args": ["-M:mcp"],
    "cwd": "/path/to/your/project"
  }
}
```

**Available tools:** `list_runs`, `get_run`, `compare_runs`, `search_runs`, `best_run`, `list_models`, `get_model`, `get_model_version`, `add_tag`, `set_note`, `get_tags`, `get_datasets`, `list_experiments`, `create_experiment`, `export_runs`, `diff_model_versions`.

## Chat-with-data

The `/chat` page in the UI lets you ask natural-language questions about your experiments. Behind the scenes, it sends your question to the Anthropic or OpenAI API with chachaml's tools injected. The LLM calls tools to query your data and formulates an answer.

Your API key is stored in your browser's `localStorage` — it's never persisted on the server.

From the REPL:

```clojure
(require '[chachaml.chat :as chat])

(chat/ask "Which run in experiment 'iris' has the best accuracy?"
          {:provider :anthropic
           :api-key  "sk-ant-..."
           :model    "claude-sonnet-4-20250514"})
;; => {:answer "Run abc123 has the highest accuracy at 0.94..." :iterations 2}
```

## Python interop (sklearn)

For ML practitioners using scikit-learn via libpython-clj2:

```clojure
;; Add :python alias to your clojure invocation
;; clojure -M:python ...

(require '[chachaml.interop.sklearn :as sk])

(ml/with-run {:experiment "sklearn-iris"}
  (sk/train-and-evaluate!
    (lm/LogisticRegression :max_iter 200)
    X-train y-train X-test y-test
    :register-as "iris-classifier"
    :stage :staging))
```

`tracked-fit!`, `tracked-predict`, `evaluate!` auto-log training time, model params, and accuracy metrics.

No Python required for the rest of chachaml — the interop layer uses `requiring-resolve` so it compiles without libpython-clj2 on the classpath.

## Examples

```bash
# Two standalone ML examples
clojure -M:examples -m linear-regression
clojure -M:examples -m kmeans

# 25 ML use cases demonstrating every tracking pattern
clojure -M:examples -m ml-showcase

# sklearn example (requires Python + sklearn)
clojure -M:python:examples -m sklearn-iris
```

The 25 use cases cover: binary/multiclass classification, regression variants (linear, polynomial, ridge, lasso), clustering (k-means, DBSCAN), PCA, anomaly detection, cross-validation, hyperparameter grid search, model comparison, ensemble voting, feature importance, time series, text classification, recommendation, and A/B testing.

## Architecture

```
chachaml.core + registry + tracking + pipeline + alerts   ← public API
chachaml.chat                                             ← LLM analysis
chachaml.mcp                                              ← MCP server (16 tools)
chachaml.ui.{server,api,views,charts,layout}              ← web UI
chachaml.interop.sklearn                                  ← Python bridge (optional)
chachaml.format + repl                                    ← shared formatting + REPL
chachaml.context + serialize + env + schema               ← infra
chachaml.store.{protocol,sqlite}                          ← storage (SQLite + WAL)
```

**Storage**: SQLite (WAL mode for concurrent access). Artifact bytes on the filesystem. No external services required.

**Concurrency**: multiple agents/REPLs can write to the same DB simultaneously. The UI reads without blocking writers.

## Development

```bash
bb test          # kaocha (deps-based)
bb lint          # clj-kondo, fails on warnings
bb fmt-check     # cljfmt
bb coverage      # cloverage (≥ 85% line gate)
bb lein-test     # proves dual build
bb ci            # everything above
```

Both `deps.edn` and `project.clj` are maintained. CI runs a 4-job matrix: `{deps, lein} × JDK {17, 21}`.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the quality bar, code conventions, and ADR process.

## Status

202 tests / 471 assertions. Coverage 85%+ forms / 94%+ lines.

| Version | What shipped |
|---|---|
| v0.1.0 | Core tracking, artifacts, model registry, deftracked, REPL helpers |
| v0.2.0 | MCP server (6 tools), web UI (5 pages), SQLite WAL |
| v0.3.0 | sklearn interop, 25 ML use case showcase |
| v0.4.0 | Tags, datasets, search, batch metrics, tables, export, experiments, notes, alerts, pipelines, chat-with-data, 16 MCP tools, 8 UI pages |

## License

[MIT](LICENSE)
