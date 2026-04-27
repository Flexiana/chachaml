# Tutorial — chachaml from zero to MCP

A 45-minute walkthrough that takes you from "I just heard of this
library" to a tracked experiment, a registered production model, a
running web UI, a Slack alert, and an LLM agent answering questions
about your runs.

We'll use a small synthetic k-means clustering as the running example
— it's deterministic, has no Python dependencies, and runs in
milliseconds, so you can copy each block straight into your REPL.

The tutorial deliberately escalates: solo at the REPL → small team →
LLM agent. Each step ends with what you should see. If something looks
off, jump to [Troubleshooting](TROUBLESHOOTING.md).

## What you'll need

- JDK 17 or 21
- Clojure CLI (`clojure -e '(println "hi")'` should work)
- A terminal and your favourite REPL editor

You do **not** need Python, Postgres, or Docker to follow most of the
tutorial. We'll add those at the end.

## Step 1 — Install

Add chachaml as a `:local/root` or `:git/url` dep. (Until the next
Clojars release, use one of the methods in
[USING-LOCALLY.md](USING-LOCALLY.md).)

```clojure
;; deps.edn
{:deps {com.flexiana/chachaml {:git/url "https://github.com/flexiana/chachaml"
                               :git/sha "LATEST_SHA"}}}
```

Start a REPL:

```bash
clj
```

```clojure
(require '[chachaml.core :as ml])
;; => nil
```

You'll see no output yet. The store is opened lazily on the first
write.

## Step 2 — Your first run

```clojure
(ml/with-run {:experiment "kmeans"
              :name       "first-try"
              :tags       {:author "you"}}
  (ml/log-params {:k 3 :max-iter 30 :seed 7})
  (ml/log-metric :final-inertia 12.4)
  :ok)
;; => :ok
```

That single block:

1. Created `./chachaml.db` (a SQLite file) on first call.
2. Opened a run in experiment `"kmeans"`.
3. Logged three params (immutable per run) and one metric.
4. Closed the run with status `:completed`.

Query it back:

```clojure
(ml/last-run)
;; => {:id "..." :experiment "kmeans" :name "first-try" :status :completed
;;     :params {:k 3 :max-iter 30 :seed 7}
;;     :metrics [{:key :final-inertia :value 12.4 :step 0 :ts ...}]
;;     :tags {:author "you"} ...}
```

Fetch by id:

```clojure
(ml/run (:id (ml/last-run)))
```

If you want to see who created the run:

```clojure
(ml/current-user)
;; => "<your system user>"
```

Set `CHACHA_USER` in your shell to override (`export CHACHA_USER=maria`).

## Step 3 — Real metrics, real artifacts

Log a metric per epoch (anything numeric, with an integer `step`):

```clojure
(ml/with-run {:experiment "kmeans" :name "with-curve"}
  (ml/log-params {:k 3 :max-iter 5})
  (doseq [iter (range 5)]
    (ml/log-metric :inertia (Math/exp (- (double iter))) iter))
  (ml/log-artifact "model" {:centroids [[0 0] [1 1] [2 2]]
                            :inertia    0.018
                            :iterations 5})
  :done)
```

Then load the artifact back:

```clojure
(ml/load-artifact (:id (ml/last-run)) "model")
;; => {:centroids [[0 0] [1 1] [2 2]] :inertia 0.018 :iterations 5}
```

Artifacts are arbitrary Clojure values, serialised with
[nippy](https://github.com/taoensso/nippy) by default. They live under
`./chachaml-artifacts/` next to the SQLite file. Pass `:format :edn`
in opts if you need a human-readable file.

## Step 4 — Open the web UI

In a separate terminal (keep your REPL alive), start the UI:

```bash
clojure -M:ui
;; → Started chachaml UI on http://localhost:8080
```

Open `http://localhost:8080` in your browser. You'll land on
`/runs` — every run you've created so far, newest first. Click into a
run to see params, metrics, an inertia chart (Vega-Lite), tags, notes,
and artifacts.

The full UI tour is in [WEB_UI.md](WEB_UI.md). For now, click around;
the only state is your `chachaml.db`.

## Step 5 — Register a model, promote it to production

Run a couple more experiments so the registry has something to work
with:

```clojure
(require '[chachaml.registry :as reg])

(dotimes [i 3]
  (ml/with-run {:experiment "kmeans" :name (str "candidate-" i)}
    (ml/log-params {:k 3 :max-iter (+ 10 (* i 10))})
    (ml/log-metric :final-inertia (- 5.0 i))
    (ml/log-artifact "model" {:centroids [[0 0] [1 1] [2 2]] :version i})
    (reg/register-model "kmeans-prod"
                        {:artifact    "model"
                         :stage       :staging
                         :description (format "candidate %d" i)})))
```

List the model's versions:

```clojure
(reg/model-versions "kmeans-prod")
;; => [{:version 1 :stage :staging ...}
;;     {:version 2 :stage :staging ...}
;;     {:version 3 :stage :staging ...}]
```

Promote v3 to production. Production is *exclusive* — promoting one
version automatically demotes the previous prod version (see
[ADR-0006](adr/0006-production-stage-exclusivity.md)).

```clojure
(reg/promote! "kmeans-prod" 3 :production)
;; => {:model-name "kmeans-prod" :version 3 :stage :production ...}
```

Anywhere downstream:

```clojure
(reg/load-model "kmeans-prod")              ;; latest production
(reg/load-model "kmeans-prod" :production)  ;; explicit
(reg/load-model "kmeans-prod" 1)            ;; specific version
;; => the artifact value, deserialised
```

The `/models` UI page shows the same data with a one-click promote
button.

## Step 6 — Wrap your training fn with `deftracked`

Manual `with-run` is fine for ad-hoc REPL work, but production code
benefits from a `defn`-shaped wrapper:

```clojure
(require '[chachaml.tracking :refer [deftracked]])

(deftracked train-kmeans
  "Toy k-means trainer."
  {:experiment "kmeans"
   :tags       {:family "lloyd"}}
  [data {:keys [k max-iter]}]
  (ml/log-params {:k k :max-iter max-iter :n (count data)})
  (let [model {:centroids (take k data) :iterations max-iter}]
    (ml/log-metric :final-inertia 0.42)
    (ml/log-artifact "model" model)
    model))

(train-kmeans [[0 0] [1 1] [2 2] [3 3] [4 4]] {:k 2 :max-iter 30})
```

Each call opens its own run, logs whatever the body logs, and closes
the run on return or exception. `deftracked` nests under any ambient
`with-run`, so calling it from inside a parent run wires up the
parent–child relationship automatically.

## Step 7 — Chain steps with a pipeline

`chachaml.pipeline/run-pipeline!` runs a sequence of steps, each as
its own tracked run, passing the previous step's return value into the
next:

```clojure
(require '[chachaml.pipeline :as pipe])

(pipe/run-pipeline!
 "iris-end-to-end"
 [[:load
   (fn [_]
     (ml/log-metric :rows 150)
     {:dataset [[5.1 3.5 1.4] [4.9 3.0 1.4] [6.2 3.4 5.4]]})]

  [:train
   (fn [{:keys [prev-result]}]
     (ml/log-params {:model "kmeans" :k 3})
     (ml/log-metric :final-inertia 0.7)
     {:model {:centroids (:dataset prev-result)}})]

  [:evaluate
   (fn [{:keys [prev-result]}]
     (ml/log-metric :silhouette 0.61)
     {:silhouette 0.61 :model (:model prev-result)})]])
```

Each step's `fn` receives a context map with `:prev-result`,
`:pipeline-id`, `:step-name`, `:step-order`. Browse the result on the
`/pipelines` UI page.

## Step 8 — Set an alert

```clojure
(require '[chachaml.alerts :as alerts])

(alerts/set-alert! "inertia-too-high"
                   {:experiment   "kmeans"
                    :metric-key   :final-inertia
                    :op           :>
                    :threshold    1.0
                    :webhook-url  "https://hooks.slack.com/services/T.../B.../xxxx"})

(alerts/check-alerts!)
;; => evaluates every alert, posts to Slack on breach,
;;    appends to alert history.
```

If you don't have a Slack webhook handy, leave `:webhook-url` off and
just inspect `(alerts/alert-history "inertia-too-high")` after a
breach. The
[wire-up-slack-alerts](howto/wire-up-slack-alerts.md) how-to walks
through getting a webhook URL.

## Step 9 — Ask a question

The `chachaml.chat/ask` function injects chachaml's tools into a
Claude or GPT call so the model can query your data:

```clojure
(require '[chachaml.chat :as chat])

(chat/ask "Which run in experiment 'kmeans' has the lowest final-inertia?"
          {:provider :anthropic
           :api-key  (System/getenv "ANTHROPIC_API_KEY")
           :model    "claude-sonnet-4-5-20250929"})
;; => {:answer "Run abc... has the lowest final-inertia at 2.0."
;;     :iterations 2}
```

Same flow is available in the UI under `/chat`. Your API key is held
in browser `localStorage`; it's never written to the server.

## Step 10 — Wire up MCP for Claude Code

Now let an MCP-compatible client (Claude Code, Continue, Cursor) ask
the same question itself.

In the REPL terminal you can keep your existing session; we just need
the JSON-RPC server runnable as a sidecar:

```bash
clojure -M:mcp     # speaks MCP over stdin/stdout, default ./chachaml.db
```

In Claude Code's `.claude/mcp.json` (or your editor's equivalent):

```json
{
  "chachaml": {
    "command": "clojure",
    "args": ["-M:mcp"],
    "cwd": "/path/to/your/project"
  }
}
```

Restart your editor. Ask Claude:

> "List the kmeans runs and tell me which one has the lowest
> final-inertia."

You'll see Claude call `list_runs` and `search_runs` and answer with
the run id. The full tool list is in [MCP.md](MCP.md).

## Step 11 — Tomorrow, when a colleague joins

Switch from solo SQLite to shared Postgres. Add the alias and connect:

```clojure
(require '[chachaml.store :as store])

(ml/use-store! (store/open {:type     :postgres
                            :jdbc-url "jdbc:postgresql://localhost:5432/chachaml"
                            :username "chachaml"
                            :password "secret"}))

;; Same API. Same map shapes. Now everyone sees the same runs.
(ml/with-run {:experiment "kmeans"} ...)
```

The schema is created on first connect. To run a Postgres + UI stack
for your team, the
[team-deployment-docker](howto/team-deployment-docker.md) how-to has a
copy-paste `docker-compose.yml`.

## Step 12 — Clean up

After a few weeks of experiments, the SQLite file grows. Prune old
runs:

```clojure
;; Archive runs older than 30 days (still in DB, marked archived,
;; hidden from default queries).
(ml/archive-runs! {:older-than-days 30})
;; => {:archived 124}

;; Permanently delete everything archived (and all its metrics + artifacts).
(ml/delete-archived!)
;; => {:deleted-runs 124 :deleted-artifacts 488 ...}
```

Stick this in a CI job or a cron script. The
[clean-up-old-runs](howto/clean-up-old-runs.md) how-to has both
patterns.

## You're done

You've now used every major capability of chachaml:

| Step | Capability | Where to go deeper |
|---|---|---|
| 2–3 | Tracking, params, metrics, artifacts | [API ref: chachaml.core](https://cljdoc.org/d/com.flexiana/chachaml/CURRENT/api/chachaml.core) |
| 4 | Web UI | [WEB_UI.md](WEB_UI.md) |
| 5 | Model registry | [API ref: chachaml.registry](https://cljdoc.org/d/com.flexiana/chachaml/CURRENT/api/chachaml.registry) · [ADR-0006](adr/0006-production-stage-exclusivity.md) |
| 6 | `deftracked` | [API ref: chachaml.tracking](https://cljdoc.org/d/com.flexiana/chachaml/CURRENT/api/chachaml.tracking) |
| 7 | Pipelines | [build-a-pipeline](howto/build-a-pipeline.md) |
| 8 | Alerts | [wire-up-slack-alerts](howto/wire-up-slack-alerts.md) |
| 9 | Chat-with-data | [API ref: chachaml.chat](https://cljdoc.org/d/com.flexiana/chachaml/CURRENT/api/chachaml.chat) |
| 10 | MCP | [MCP.md](MCP.md) · [configure-mcp-with-claude-code](howto/configure-mcp-with-claude-code.md) |
| 11 | Postgres backend | [migrate-sqlite-to-postgres](howto/migrate-sqlite-to-postgres.md) |
| 12 | Cleanup | [clean-up-old-runs](howto/clean-up-old-runs.md) |

For the *why* behind the design — dynamic vars, filesystem artifacts,
production-stage exclusivity — read [DESIGN.md](DESIGN.md).
