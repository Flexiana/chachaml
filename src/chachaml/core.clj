(ns chachaml.core
  "Public API for chachaml.

  Run lifecycle (`with-run`, `start-run!`, `end-run!`), logging
  (`log-params`, `log-metrics`, `log-metric`), and querying (`runs`,
  `run`, `last-run`).

  Idiomatic usage from a REPL:

      (require '[chachaml.core :as ml])

      (ml/with-run {:experiment \"iris\"}
        (ml/log-params {:lr 0.01 :epochs 10})
        (ml/log-metric :accuracy 0.94)
        :ok)

      (ml/last-run)        ; => the run map just completed

  Artifacts (M3), the model registry (M5), `deftracked` (M4), and the
  remaining REPL helpers (M6) land in subsequent milestones."
  (:require [chachaml.context :as ctx]
            [chachaml.env :as env]
            [chachaml.schema :as schema]
            [chachaml.store.protocol :as p]
            [chachaml.store.sqlite :as sqlite]))

;; --- Store binding ----------------------------------------------------

(defn use-store!
  "Set the global default store for subsequent calls.

  Accepts either:
  - A store value (a record implementing the storage protocols), or
  - A config map for `chachaml.store.sqlite/open`, e.g.
    `{:path \"runs.db\"}` or `{:in-memory? true}`.

  Returns the resolved store."
  [config-or-store]
  (let [store (if (satisfies? p/RunStore config-or-store)
                config-or-store
                (sqlite/open (or config-or-store {})))]
    (alter-var-root #'ctx/*store* (constantly store))
    store))

(defmacro with-store
  "Lexically rebind `*store*` to `store` for the duration of `body`.
  Useful for tests and parallel work."
  [store & body]
  `(binding [ctx/*store* ~store] ~@body))

;; --- Run lifecycle ----------------------------------------------------

(defn- new-run-id [] (str (random-uuid)))

(defn start-run!
  "Open a new run and persist it. Returns the run map.

  `opts` may include:
  - `:experiment`    string, default \"default\"
  - `:name`          string, optional
  - `:tags`          map, optional
  - `:parent-run-id` string, optional. When omitted, the current run id
                     (if inside `with-run`) is used.

  Most callers should use `with-run` instead, which also handles
  `end-run!` on normal completion or exception."
  ([] (start-run! {}))
  ([opts]
   (schema/validate schema/StartRunOpts opts)
   (let [store  (ctx/current-store)
         parent (or (:parent-run-id opts)
                    (some-> (ctx/current-run) :id))
         run    {:id            (new-run-id)
                 :experiment    (or (:experiment opts) "default")
                 :name          (:name opts)
                 :status        :running
                 :start-time    (System/currentTimeMillis)
                 :tags          (:tags opts)
                 :env           (env/capture)
                 :parent-run-id parent}]
     (p/-create-run! store run)
     run)))

(defn end-run!
  "Mark `run` (or run map fetched by id) as ended.

  `status` defaults to `:completed`; pass `:failed` (and an optional
  `error` string) when terminating after an exception."
  ([run] (end-run! run :completed nil))
  ([run status] (end-run! run status nil))
  ([run status error]
   (let [store  (ctx/current-store)
         run-id (if (map? run) (:id run) run)]
     (p/-update-run! store run-id
                     (cond-> {:status   status
                              :end-time (System/currentTimeMillis)}
                       error (assoc :error (str error))))
     (let [updated (p/-get-run store run-id)]
       (when updated (tap> updated))
       updated))))

(defmacro with-run
  "Open a run, evaluate `body`, and end the run.

  On normal return, the run is marked `:completed` and `body`'s value is
  returned. On exception, the run is marked `:failed` (with the exception
  message recorded) and the exception re-thrown. The completed run map
  is `tap>`-ed for tooling such as Portal/Reveal.

  Inside the block, `(chachaml.context/current-run)` returns the new run."
  [opts & body]
  `(let [run# (start-run! ~opts)]
     (try
       (binding [ctx/*run* run#]
         (let [result# (do ~@body)]
           (end-run! run# :completed)
           result#))
       (catch Throwable t#
         (end-run! run# :failed (ex-message t#))
         (throw t#)))))

;; --- Logging ----------------------------------------------------------

(defn log-params
  "Persist a map of params on the current run. Params are immutable —
  re-logging an existing key for the same run is rejected by the store."
  [m]
  (when (seq m)
    (schema/validate schema/Params m)
    (p/-log-params! (ctx/current-store) (:id (ctx/require-run!)) m))
  nil)

(defn log-param
  "Convenience: persist a single param `k`/`v` on the current run."
  [k v]
  (log-params {k v}))

(defn log-metrics
  "Persist a map of numeric metrics on the current run.

  All metrics are recorded with the same `step` (default 0) and the
  current timestamp."
  ([m] (log-metrics m {}))
  ([m {:keys [step] :or {step 0}}]
   (when (seq m)
     (schema/validate schema/Metrics m)
     (let [now  (System/currentTimeMillis)
           rows (mapv (fn [[k v]]
                        {:key k :value v :step step :timestamp now})
                      m)]
       (p/-log-metrics! (ctx/current-store)
                        (:id (ctx/require-run!))
                        rows)))
   nil))

(defn log-metric
  "Convenience: persist a single numeric metric on the current run.

  `(log-metric :loss 0.3)` and `(log-metric :loss 0.3 5)` are equivalent
  to `(log-metrics {:loss 0.3} {:step 5})`."
  ([k v]      (log-metrics {k v} {}))
  ([k v step] (log-metrics {k v} {:step step})))

;; --- Querying ---------------------------------------------------------

(defn run
  "Fetch a run by id, returning the full run map (with params and
  metrics included), or nil if no such run exists."
  [run-id]
  (let [store (ctx/current-store)]
    (when-let [r (p/-get-run store run-id)]
      (assoc r
             :params  (p/-get-params store run-id)
             :metrics (p/-get-metrics store run-id)))))

(defn runs
  "List runs matching `filters`, most recent first.

  Supported filters: `:experiment`, `:status`, `:name`, `:parent-run-id`,
  `:limit` (default 100)."
  ([] (runs {}))
  ([filters]
   (when (seq filters) (schema/validate schema/QueryFilters filters))
   (p/-query-runs (ctx/current-store) filters)))

(defn last-run
  "Return the most recently started run (across all experiments), or nil
  if no runs exist. Equivalent to `(first (runs {:limit 1}))`."
  []
  (first (runs {:limit 1})))
