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
- **Docker** — `docker compose up` gives you Postgres + UI in one command
- **HTTP write API** — POST endpoints for Python/Go/curl clients
- **S3 artifacts** — shared artifact storage for teams (MinIO compatible)
- **Slack alerts** — webhook notifications when metrics cross thresholds
- **Run cleanup** — archive and delete old runs to control DB growth

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
  {:experiment  "production"
   :metric-key  :accuracy
   :op          :<
   :threshold   0.9
   :webhook-url "https://hooks.slack.com/services/T.../B.../xxx"})

;; Check after training runs — triggers Slack if threshold crossed
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

### Postgres backend (team use)

For teams, switch from SQLite to a shared Postgres instance:

```clojure
(require '[chachaml.store :as store])

;; Connect to shared Postgres (add :postgres alias to your deps)
(ml/use-store! (store/open {:type     :postgres
                            :jdbc-url "jdbc:postgresql://team-db:5432/chachaml"
                            :username "chachaml"
                            :password "secret"}))

;; Everything else is identical — same API, shared state
(ml/with-run {:experiment "iris"} ...)
```

HikariCP connection pool included. Same schema, same map shapes — swap backends without changing application code.

### User attribution

Runs automatically capture who created them:

```clojure
(ml/with-run {:experiment "iris"} ...)
(:created-by (ml/last-run))
;; => "maria"   (auto-captured from system user)

;; Filter by person
(ml/runs {:created-by "tomas"})
```

Override with `:created-by` in `start-run!` opts if needed.

### Run cleanup

```clojure
;; Archive runs older than 30 days
(ml/archive-runs! {:older-than-days 30})

;; Or scope to one experiment
(ml/archive-runs! {:older-than-days 7 :experiment "scratch"})

;; Permanently delete archived runs + all their data
(ml/delete-archived!)
```

### Docker (team deployment)

```bash
docker compose up     # Postgres + chachaml UI at localhost:8080
```

Set environment variables for production:

```yaml
environment:
  DB_TYPE: postgres
  JDBC_URL: jdbc:postgresql://db:5432/chachaml
  DB_USER: chachaml
  DB_PASSWORD: secret
```

### HTTP write API (for Python/Go/curl)

Non-Clojure engineers can log runs via REST:

```bash
# Start a run
RUN_ID=$(curl -s -X POST http://localhost:8080/api/w/runs \
  -H 'Content-Type: application/json' \
  -d '{"experiment":"iris","name":"from-python"}' | jq -r .id)

# Log params + metrics
curl -X POST http://localhost:8080/api/w/runs/$RUN_ID/params \
  -H 'Content-Type: application/json' -d '{"lr":0.01,"epochs":100}'

curl -X POST http://localhost:8080/api/w/runs/$RUN_ID/metrics \
  -H 'Content-Type: application/json' -d '{"accuracy":0.94}'

# End the run
curl -X POST http://localhost:8080/api/w/runs/$RUN_ID/end \
  -H 'Content-Type: application/json' -d '{"status":"completed"}'
```

All write endpoints live under `/api/w/` — see [JSON API](#json-api) for the full list.

### S3 artifact storage

For teams that need shared model storage:

```clojure
;; Add :s3 alias to your deps
(require '[chachaml.store.s3 :as s3])

(def art-store (s3/open {:bucket   "ml-artifacts"
                          :prefix   "chachaml/"
                          :endpoint "http://minio:9000"}))
```

Works with AWS S3 or any S3-compatible store (MinIO, DigitalOcean Spaces, etc.).

## Architecture

```
chachaml.core + registry + tracking + pipeline + alerts   ← public API
chachaml.chat                                             ← LLM analysis
chachaml.mcp                                              ← MCP server (16 tools)
chachaml.ui.{server,api,views,charts,layout}              ← web UI
chachaml.interop.sklearn                                  ← Python bridge (optional)
chachaml.format + repl                                    ← shared formatting + REPL
chachaml.context + serialize + env + schema               ← infra
chachaml.store.{protocol,sqlite,postgres}                 ← storage (SQLite or Postgres)
chachaml.store                                            ← backend dispatcher
```

**Storage**: SQLite (default, WAL mode) or Postgres (for teams). Artifact bytes on the filesystem. Dispatch via `(chachaml.store/open {:type :sqlite})` or `{:type :postgres}`.

**Concurrency**: multiple agents/REPLs can write simultaneously. SQLite uses WAL mode; Postgres uses connection pooling. The UI reads without blocking writers.

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

205 tests / 489 assertions. Coverage 85%+ forms / 93%+ lines.

| Version | What shipped |
|---|---|
| v0.1.0 | Core tracking, artifacts, model registry, deftracked, REPL helpers |
| v0.2.0 | MCP server (6 tools), web UI (5 pages), SQLite WAL |
| v0.3.0 | sklearn interop, 25 ML use case showcase |
| v0.4.0 | Tags, datasets, search, batch metrics, tables, export, experiments, markdown notes, 16 MCP tools, 8 UI pages |
| v0.5.0 | Postgres backend, user attribution, pipelines, alerts, chat-with-data, store dispatcher |
| v0.6.0 | Docker, HTTP write API, S3 artifacts, Slack webhooks, run cleanup |

## Publishing

### To GitHub

```bash
git remote add origin git@github.com:flexiana/chachaml.git
git push -u origin master --tags
```

Users can then depend on it via git:

```clojure
;; deps.edn
{:deps {io.github.flexiana/chachaml
        {:git/url "https://github.com/flexiana/chachaml"
         :git/sha "COMMIT_SHA"}}}
```

### To Clojars

1. Create a [Clojars](https://clojars.org) account and generate a deploy token.

2. Set credentials:
   ```bash
   export CLOJARS_USERNAME=your-username
   export CLOJARS_PASSWORD=your-deploy-token
   ```

3. Update the group-id in `build.clj` if needed (currently `org.clojars.jiriknesl/chachaml` — change to `com.flexiana/chachaml` or similar).

4. Deploy:
   ```bash
   clojure -T:build jar
   clojure -T:build deploy
   ```

   Note: `deploy` task needs `deps-deploy` (already in the `:build` alias). Add this to `build.clj` if not present:

   ```clojure
   (defn deploy [_]
     ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
      {:installer :remote
       :artifact  jar-file
       :pom-file  (str class-dir "/META-INF/maven/" (namespace lib) "/" (name lib) "/pom.xml")}))
   ```

5. Users can then:
   ```clojure
   ;; deps.edn
   {:deps {com.flexiana/chachaml {:mvn/version "0.6.0"}}}

   ;; project.clj
   [com.flexiana/chachaml "0.6.0"]
   ```

### Docker image

```bash
docker build -t flexiana/chachaml .
docker push flexiana/chachaml           # to Docker Hub
# or
docker tag flexiana/chachaml ghcr.io/flexiana/chachaml
docker push ghcr.io/flexiana/chachaml   # to GitHub Container Registry
```

## License

[MIT](LICENSE)
