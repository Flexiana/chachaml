(ns chachaml.store.sqlite
  "SQLite-backed implementation of `chachaml.store.protocol/RunStore`.

  This is the default backend. A single SQLite file holds runs, params,
  and metrics. ArtifactStore and ModelRegistry implementations land in
  M3 and M5 respectively.

  Use `(open)` for the default `./chachaml.db`, or `(open {:path \"…\"})`
  for a specific location. `:in-memory? true` opens an ephemeral DB,
  intended for tests. The store implements `Lifecycle/close!` but the
  default unpooled `next.jdbc` datasource holds no resources to close.

  All schema creation is idempotent (`CREATE TABLE IF NOT EXISTS`); the
  schema is applied on every `open`."
  (:require [chachaml.store.protocol :as p]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as jsql])
  (:import [java.sql Connection]))

;; --- Schema -----------------------------------------------------------

(def ^:private schema-statements
  ["CREATE TABLE IF NOT EXISTS runs (
      id            TEXT PRIMARY KEY,
      experiment    TEXT NOT NULL DEFAULT 'default',
      name          TEXT,
      status        TEXT NOT NULL,
      start_time    INTEGER NOT NULL,
      end_time      INTEGER,
      error         TEXT,
      tags          TEXT,
      env           TEXT,
      parent_run_id TEXT
    )"
   "CREATE INDEX IF NOT EXISTS idx_runs_experiment ON runs(experiment)"
   "CREATE INDEX IF NOT EXISTS idx_runs_start_time ON runs(start_time)"
   "CREATE TABLE IF NOT EXISTS params (
      run_id TEXT NOT NULL,
      key    TEXT NOT NULL,
      value  TEXT NOT NULL,
      PRIMARY KEY (run_id, key)
    )"
   "CREATE TABLE IF NOT EXISTS metrics (
      run_id    TEXT NOT NULL,
      key       TEXT NOT NULL,
      value     REAL NOT NULL,
      step      INTEGER NOT NULL DEFAULT 0,
      timestamp INTEGER NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_metrics_run ON metrics(run_id)"])

(defn- migrate!
  "Apply (idempotent) schema statements against `connectable`. Accepts a
  DataSource or an open Connection."
  [connectable]
  (doseq [stmt schema-statements]
    (jdbc/execute! connectable [stmt])))

;; --- EDN encoding helpers ---------------------------------------------

(defn- enc
  "Encode an arbitrary Clojure value for storage as TEXT."
  [v]
  (when (some? v) (pr-str v)))

(defn- dec-edn
  "Decode an EDN-encoded TEXT cell, or nil if the cell is nil/blank."
  [s]
  (when (and s (not (str/blank? s)))
    (edn/read-string s)))

(defn- enc-key
  "Encode a key (keyword or string) as a stable TEXT primary-key column."
  [k]
  (cond
    (string? k)  k
    (keyword? k) (subs (str k) 1)
    :else        (str k)))

(defn- status->db [s] (when s (name s)))
(defn- status<-db [s] (when s (keyword s)))

;; --- Row <-> map mapping ----------------------------------------------

(defn- ->run-map
  "Convert a `runs` row (with `:runs/`-namespaced keys) to a public run map."
  [row]
  (when row
    (let [{run-id        :runs/id
           experiment    :runs/experiment
           run-name      :runs/name
           status        :runs/status
           start-time    :runs/start_time
           end-time      :runs/end_time
           error         :runs/error
           tags          :runs/tags
           env           :runs/env
           parent-run-id :runs/parent_run_id} row]
      (cond-> {:id         run-id
               :experiment experiment
               :status     (status<-db status)
               :start-time start-time}
        run-name      (assoc :name run-name)
        end-time      (assoc :end-time end-time)
        error         (assoc :error error)
        tags          (assoc :tags (dec-edn tags))
        env           (assoc :env (dec-edn env))
        parent-run-id (assoc :parent-run-id parent-run-id)))))

(def ^:private update-key->col
  {:experiment    "experiment"
   :name          "name"
   :status        "status"
   :start-time    "start_time"
   :end-time      "end_time"
   :error         "error"
   :tags          "tags"
   :env           "env"
   :parent-run-id "parent_run_id"})

(defn- update-value [k v]
  (case k
    :status (status->db v)
    (:tags :env) (enc v)
    v))

;; --- Querying ---------------------------------------------------------

(def ^:private query-key->col
  {:experiment    "experiment"
   :status        "status"
   :name          "name"
   :parent-run-id "parent_run_id"})

(defn- where-clause
  "Build a `[sql-fragment params]` pair for supported equality filters."
  [filters]
  (let [pairs (keep (fn [[k v]]
                      (when-let [col (query-key->col k)]
                        [col (cond-> v (= k :status) status->db)]))
                    filters)]
    (if (seq pairs)
      [(str "WHERE " (str/join " AND " (map #(str (first %) " = ?") pairs)))
       (mapv second pairs)]
      ["" []])))

(defn- execute-query!
  [{:keys [datasource]} {:keys [limit] :or {limit 100} :as filters}]
  (let [[where ps] (where-clause filters)
        sql        (str "SELECT * FROM runs " where
                        " ORDER BY start_time DESC LIMIT ?")]
    (->> (jdbc/execute! datasource (into [sql] (conj ps (long limit))))
         (mapv ->run-map))))

;; --- The store --------------------------------------------------------

(defrecord SQLiteStore [datasource path in-memory?]
  p/RunStore
  (-create-run! [_ run]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO runs (id, experiment, name, status, start_time,
                         end_time, error, tags, env, parent_run_id)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      (:id run)
      (or (:experiment run) "default")
      (:name run)
      (status->db (:status run))
      (:start-time run)
      (:end-time run)
      (:error run)
      (enc (:tags run))
      (enc (:env run))
      (:parent-run-id run)])
    run)

  (-update-run! [_ run-id updates]
    (when (seq updates)
      (let [pairs   (->> updates
                         (map (fn [[k v]]
                                (when-let [col (update-key->col k)]
                                  [col (update-value k v)])))
                         (remove nil?))
            cols    (mapv first pairs)
            vs      (mapv second pairs)
            set-sql (str/join ", " (map #(str % " = ?") cols))]
        (jdbc/execute-one!
         datasource
         (into [(str "UPDATE runs SET " set-sql " WHERE id = ?")]
               (conj vs run-id)))))
    nil)

  (-get-run [_ run-id]
    (->run-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM runs WHERE id = ?" run-id])))

  (-query-runs [this filters]
    (execute-query! this filters))

  (-log-params! [_ run-id params]
    (when (seq params)
      (jsql/insert-multi!
       datasource :params [:run_id :key :value]
       (mapv (fn [[k v]] [run-id (enc-key k) (enc v)]) params)
       {:return-keys false}))
    nil)

  (-get-params [_ run-id]
    (let [rows (jdbc/execute! datasource
                              ["SELECT key, value FROM params
                                WHERE run_id = ? ORDER BY key" run-id]
                              {:builder-fn rs/as-unqualified-maps})]
      (into {} (map (fn [{k :key v :value}] [(keyword k) (dec-edn v)])) rows)))

  (-log-metrics! [_ run-id metrics]
    (when (seq metrics)
      (let [now (System/currentTimeMillis)]
        (jsql/insert-multi!
         datasource :metrics [:run_id :key :value :step :timestamp]
         (mapv (fn [{k :key v :value s :step ts :timestamp :or {s 0}}]
                 [run-id
                  (enc-key k)
                  (double v)
                  (long s)
                  (long (or ts now))])
               metrics)
         {:return-keys false})))
    nil)

  (-get-metrics [_ run-id]
    (let [rows (jdbc/execute!
                datasource
                ["SELECT key, value, step, timestamp FROM metrics
                  WHERE run_id = ? ORDER BY key, step, timestamp" run-id]
                {:builder-fn rs/as-unqualified-maps})]
      (mapv (fn [{k :key v :value s :step ts :timestamp}]
              {:key (keyword k) :value v :step s :timestamp ts})
            rows)))

  p/Lifecycle
  (close! [_]
    ;; In-memory mode holds an open Connection (closing it destroys the
    ;; database). File-backed mode uses an unpooled DataSource with no
    ;; resources to release.
    (when (instance? Connection datasource)
      (.close ^Connection datasource))))

;; --- Public constructor -----------------------------------------------

(defn open
  "Open a SQLite-backed store. Idempotently creates the schema.

  Options:
  - `:path`        File path to the SQLite database. Defaults to
                   `./chachaml.db`. Ignored when `:in-memory? true`.
  - `:in-memory?`  Open an ephemeral SQLite database backed by a single
                   in-process Connection. Intended for tests; not safe
                   for concurrent use across threads."
  ([] (open {}))
  ([{:keys [path in-memory?] :or {path "chachaml.db"}}]
   (if in-memory?
     (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname ":memory:"})]
       (migrate! conn)
       (->SQLiteStore conn nil true))
     (let [datasource (jdbc/get-datasource {:dbtype "sqlite" :dbname path})]
       (migrate! datasource)
       (->SQLiteStore datasource path false)))))
