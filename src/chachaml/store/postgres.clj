(ns chachaml.store.postgres
  "PostgreSQL-backed implementation of chachaml storage protocols.

  For teams: multiple engineers connect to the same Postgres instance.
  Artifacts are stored on the filesystem (or S3 in a future version);
  Postgres holds metadata only, same as the SQLite backend.

  Usage:

      (require '[chachaml.store.postgres :as pg])

      (def store (pg/open {:jdbc-url \"jdbc:postgresql://localhost:5432/chachaml\"
                           :username \"chachaml\"
                           :password \"secret\"
                           :artifact-dir \"/shared/artifacts\"}))

      ;; Then either:
      (ml/use-store! store)
      ;; Or pass to the UI/MCP servers.

  Requires the `:postgres` deps alias (adds postgresql driver + HikariCP)."
  (:require [chachaml.store.protocol :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as jsql])
  (:import [com.zaxxer.hikari HikariDataSource HikariConfig]
           [java.io File]
           [java.nio.file Files Path]
           [java.security MessageDigest]))

;; --- Schema (Postgres DDL) -------------------------------------------

(def ^:private schema-statements
  ["CREATE TABLE IF NOT EXISTS runs (
      id            TEXT PRIMARY KEY,
      experiment    TEXT NOT NULL DEFAULT 'default',
      name          TEXT,
      status        TEXT NOT NULL,
      start_time    BIGINT NOT NULL,
      end_time      BIGINT,
      error         TEXT,
      tags          TEXT,
      env           TEXT,
      parent_run_id TEXT,
      created_by    TEXT
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
      value     DOUBLE PRECISION NOT NULL,
      step      INTEGER NOT NULL DEFAULT 0,
      timestamp BIGINT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_metrics_run ON metrics(run_id)"
   "CREATE TABLE IF NOT EXISTS artifacts (
      id           TEXT PRIMARY KEY,
      run_id       TEXT NOT NULL,
      name         TEXT NOT NULL,
      path         TEXT NOT NULL,
      content_type TEXT,
      size         INTEGER,
      hash         TEXT,
      created_at   BIGINT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_artifacts_run ON artifacts(run_id)"
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_artifacts_run_name ON artifacts(run_id, name)"
   "CREATE TABLE IF NOT EXISTS models (
      name        TEXT PRIMARY KEY,
      description TEXT,
      created_at  BIGINT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS model_versions (
      model_name  TEXT NOT NULL,
      version     INTEGER NOT NULL,
      run_id      TEXT NOT NULL,
      artifact_id TEXT NOT NULL,
      stage       TEXT NOT NULL DEFAULT 'none',
      description TEXT,
      created_at  BIGINT NOT NULL,
      PRIMARY KEY (model_name, version)
    )"
   "CREATE INDEX IF NOT EXISTS idx_versions_stage ON model_versions(model_name, stage)"
   "CREATE TABLE IF NOT EXISTS tags (
      run_id TEXT NOT NULL,
      key    TEXT NOT NULL,
      value  TEXT NOT NULL,
      PRIMARY KEY (run_id, key)
    )"
   "CREATE TABLE IF NOT EXISTS datasets (
      id         TEXT PRIMARY KEY,
      run_id     TEXT NOT NULL,
      role       TEXT NOT NULL DEFAULT 'train',
      n_rows     INTEGER,
      n_cols     INTEGER,
      features   TEXT,
      hash       TEXT,
      source     TEXT,
      created_at BIGINT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_datasets_run ON datasets(run_id)"
   "CREATE TABLE IF NOT EXISTS experiments (
      name        TEXT PRIMARY KEY,
      description TEXT,
      owner       TEXT,
      created_at  BIGINT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS pipelines (
      id            TEXT PRIMARY KEY,
      name          TEXT NOT NULL,
      description   TEXT,
      status        TEXT NOT NULL DEFAULT 'pending',
      parent_run_id TEXT,
      created_at    BIGINT NOT NULL,
      finished_at   BIGINT
    )"
   "CREATE TABLE IF NOT EXISTS pipeline_steps (
      id          TEXT PRIMARY KEY,
      pipeline_id TEXT NOT NULL,
      step_name   TEXT NOT NULL,
      step_order  INTEGER NOT NULL,
      run_id      TEXT,
      status      TEXT NOT NULL DEFAULT 'pending',
      started_at  BIGINT,
      finished_at BIGINT
    )"
   "CREATE INDEX IF NOT EXISTS idx_pipeline_steps_pipeline ON pipeline_steps(pipeline_id)"
   "CREATE TABLE IF NOT EXISTS alerts (
      id            TEXT PRIMARY KEY,
      name          TEXT NOT NULL,
      experiment    TEXT,
      metric_key    TEXT NOT NULL,
      op            TEXT NOT NULL,
      threshold     DOUBLE PRECISION NOT NULL,
      active        INTEGER NOT NULL DEFAULT 1,
      last_checked  BIGINT,
      last_triggered BIGINT,
      created_at    BIGINT NOT NULL
    )"
   "CREATE TABLE IF NOT EXISTS alert_events (
      id           TEXT PRIMARY KEY,
      alert_id     TEXT NOT NULL,
      run_id       TEXT NOT NULL,
      metric_value DOUBLE PRECISION NOT NULL,
      triggered_at BIGINT NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_alert_events_alert ON alert_events(alert_id)"])

(defn- migrate! [datasource]
  (with-open [conn (jdbc/get-connection datasource)]
    (doseq [stmt schema-statements]
      (jdbc/execute! conn [stmt]))))

;; --- Shared encoding helpers (same as SQLite) ------------------------

(defn- enc [v] (when (some? v) (pr-str v)))
(defn- dec-edn [s]
  (when (and s (not (str/blank? s)))
    (edn/read-string s)))
(defn- enc-key [k]
  (cond (string? k) k (keyword? k) (subs (str k) 1) :else (str k)))
(defn- kw->db [k] (when k (name k)))
(defn- db->kw [s] (when s (keyword s)))

;; --- Row mappers (same shape as SQLite) ------------------------------

(defn- ->run-map [row]
  (when row
    (let [{run-id     :runs/id       experiment :runs/experiment
           run-name   :runs/name     status     :runs/status
           start-time :runs/start_time end-time :runs/end_time
           error      :runs/error    tags       :runs/tags
           env        :runs/env      parent-rid :runs/parent_run_id
           created-by :runs/created_by} row]
      (cond-> {:id run-id :experiment experiment
               :status (db->kw status) :start-time start-time}
        run-name   (assoc :name run-name)
        end-time   (assoc :end-time end-time)
        error      (assoc :error error)
        tags       (assoc :tags (dec-edn tags))
        env        (assoc :env (dec-edn env))
        parent-rid (assoc :parent-run-id parent-rid)
        created-by (assoc :created-by created-by)))))

(defn- ->model-map [row]
  (when row
    {:name        (:models/name row)
     :description (:models/description row)
     :created-at  (:models/created_at row)}))

(defn- ->version-map [row]
  (when row
    (cond-> {:model-name  (:model_versions/model_name row)
             :version     (:model_versions/version row)
             :run-id      (:model_versions/run_id row)
             :artifact-id (:model_versions/artifact_id row)
             :stage       (db->kw (:model_versions/stage row))
             :created-at  (:model_versions/created_at row)}
      (:model_versions/description row)
      (assoc :description (:model_versions/description row)))))

(defn- ->experiment-map [row]
  (when row
    {:name        (:experiments/name row)
     :description (:experiments/description row)
     :owner       (:experiments/owner row)
     :created-at  (:experiments/created_at row)}))

(defn- ->artifact-map [row]
  (when row
    (cond-> {:id         (:artifacts/id row)
             :run-id     (:artifacts/run_id row)
             :name       (:artifacts/name row)
             :path       (:artifacts/path row)
             :created-at (:artifacts/created_at row)}
      (:artifacts/content_type row) (assoc :content-type (:artifacts/content_type row))
      (:artifacts/size row)         (assoc :size (:artifacts/size row))
      (:artifacts/hash row)         (assoc :hash (:artifacts/hash row)))))

;; --- Filesystem artifact helpers (same as SQLite) --------------------

(defn- ensure-dir! ^File [^String dir]
  (let [f (io/file dir)] (.mkdirs f) f))

(defn- sha256-hex ^String [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")
        h  (.digest md data)
        sb (StringBuilder. (* 2 (alength h)))]
    (doseq [b h]
      (.append sb (format "%02x" (bit-and (long b) 0xff))))
    (.toString sb)))

(defn- write-bytes! [^File dir ^String rel-path ^bytes data]
  (let [target (io/file dir rel-path)]
    (-> target .getParentFile .mkdirs)
    (with-open [out (io/output-stream target)]
      (.write out data))
    target))

;; --- Query helpers ---------------------------------------------------

(def ^:private query-key->col
  {:experiment "experiment" :status "status" :name "name"
   :parent-run-id "parent_run_id" :created-by "created_by"})

(defn- where-clause [filters]
  (let [pairs (keep (fn [[k v]]
                      (when-let [col (query-key->col k)]
                        [col (cond-> v (= k :status) kw->db)]))
                    filters)]
    (if (seq pairs)
      [(str "WHERE " (str/join " AND " (map #(str (first %) " = ?") pairs)))
       (mapv second pairs)]
      ["" []])))

(defn- execute-query! [datasource {:keys [limit] :or {limit 100} :as filters}]
  (let [[where ps] (where-clause filters)
        sql        (str "SELECT * FROM runs " where
                        " ORDER BY start_time DESC LIMIT ?")]
    (->> (jdbc/execute! datasource (into [sql] (conj ps (long limit))))
         (mapv ->run-map))))

(def ^:private update-key->col
  {:experiment "experiment" :name "name" :status "status"
   :start-time "start_time" :end-time "end_time" :error "error"
   :tags "tags" :env "env" :parent-run-id "parent_run_id"})

(defn- update-value [k v]
  (case k :status (kw->db v) (:tags :env) (enc v) v))

;; --- The store -------------------------------------------------------

(defrecord PostgresStore [datasource artifact-dir]
  p/RunStore
  (-create-run! [_ run]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO runs (id, experiment, name, status, start_time,
                         end_time, error, tags, env, parent_run_id, created_by)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"
      (:id run) (or (:experiment run) "default") (:name run)
      (kw->db (:status run)) (:start-time run) (:end-time run)
      (:error run) (enc (:tags run)) (enc (:env run))
      (:parent-run-id run) (:created-by run)])
    run)

  (-update-run! [_ run-id updates]
    (when (seq updates)
      (let [pairs (->> updates
                       (map (fn [[k v]]
                              (when-let [col (update-key->col k)]
                                [col (update-value k v)])))
                       (remove nil?))
            cols  (mapv first pairs)
            vs    (mapv second pairs)
            sql   (str "UPDATE runs SET "
                       (str/join ", " (map #(str % " = ?") cols))
                       " WHERE id = ?")]
        (jdbc/execute-one! datasource (into [sql] (conj vs run-id)))))
    nil)

  (-get-run [_ run-id]
    (->run-map (jdbc/execute-one! datasource
                                  ["SELECT * FROM runs WHERE id = ?" run-id])))

  (-query-runs [_ filters]
    (execute-query! datasource filters))

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
                 [run-id (enc-key k) (double v) (long s)
                  (long (or ts now))])
               metrics)
         {:return-keys false})))
    nil)

  (-get-metrics [_ run-id]
    (let [rows (jdbc/execute! datasource
                              ["SELECT key, value, step, timestamp FROM metrics
                                WHERE run_id = ? ORDER BY key, step, timestamp" run-id]
                              {:builder-fn rs/as-unqualified-maps})]
      (mapv (fn [{k :key v :value s :step ts :timestamp}]
              {:key (keyword k) :value v :step s :timestamp ts})
            rows)))

  (-set-tag! [_ run-id k v]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO tags (run_id, key, value) VALUES (?, ?, ?)
       ON CONFLICT(run_id, key) DO UPDATE SET value = EXCLUDED.value"
      run-id (enc-key k) (str v)])
    nil)

  (-get-tags [_ run-id]
    (let [rows (jdbc/execute! datasource
                              ["SELECT key, value FROM tags WHERE run_id = ? ORDER BY key" run-id]
                              {:builder-fn rs/as-unqualified-maps})]
      (into {} (map (fn [{k :key v :value}] [(keyword k) v])) rows)))

  (-log-dataset! [_ run-id dataset]
    (let [ds-id (str (random-uuid))
          now   (System/currentTimeMillis)]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO datasets (id, run_id, role, n_rows, n_cols, features, hash, source, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)"
        ds-id run-id (or (:role dataset) "train")
        (:n-rows dataset) (:n-cols dataset)
        (enc (:features dataset)) (:hash dataset) (:source dataset) now])
      (assoc dataset :id ds-id :run-id run-id :created-at now)))

  (-get-datasets [_ run-id]
    (let [rows (jdbc/execute! datasource
                              ["SELECT * FROM datasets WHERE run_id = ? ORDER BY created_at" run-id])]
      (mapv (fn [row]
              {:id (:datasets/id row) :run-id (:datasets/run_id row)
               :role (:datasets/role row) :n-rows (:datasets/n_rows row)
               :n-cols (:datasets/n_cols row)
               :features (dec-edn (:datasets/features row))
               :hash (:datasets/hash row) :source (:datasets/source row)
               :created-at (:datasets/created_at row)})
            rows)))

  (-query-runs-by-metric [_ {:keys [experiment metric-key op metric-value
                                    sort-by-metric sort-dir limit]
                             :or   {limit 100 sort-dir :desc}}]
    (let [base   (if experiment "WHERE r.experiment = ?" "WHERE 1=1")
          mc     (when (and metric-key op metric-value)
                   (str " AND r.id IN (SELECT run_id FROM metrics WHERE key = ? AND value "
                        (case op :> ">" :>= ">=" :< "<" :<= "<=" := "=" ">")
                        " ?)"))
          order  (if sort-by-metric
                   (str " ORDER BY (SELECT MAX(value) FROM metrics WHERE run_id = r.id AND key = '"
                        (enc-key sort-by-metric) "') "
                        (if (= sort-dir :asc) "ASC" "DESC"))
                   " ORDER BY r.start_time DESC")
          sql    (str "SELECT * FROM runs r " base mc order " LIMIT ?")
          params (cond-> []
                   experiment   (conj experiment)
                   metric-key   (conj (enc-key metric-key))
                   metric-value (conj (double metric-value))
                   true         (conj (long limit)))]
      (->> (jdbc/execute! datasource (into [sql] params))
           (mapv ->run-map))))

  (-upsert-experiment! [_ experiment]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO experiments (name, description, owner, created_at)
       VALUES (?, ?, ?, ?)
       ON CONFLICT(name) DO UPDATE SET
         description = COALESCE(EXCLUDED.description, experiments.description),
         owner = COALESCE(EXCLUDED.owner, experiments.owner)"
      (:name experiment) (:description experiment)
      (:owner experiment) (System/currentTimeMillis)])
    (->experiment-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM experiments WHERE name = ?"
                         (:name experiment)])))

  (-get-experiment [_ experiment-name]
    (->experiment-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM experiments WHERE name = ?" experiment-name])))

  (-list-experiments [_]
    (->> (jdbc/execute! datasource ["SELECT * FROM experiments ORDER BY name"])
         (mapv ->experiment-map)))

  p/ArtifactStore
  (-put-artifact! [_ run-id art-name data content-type]
    (let [art-id   (str (random-uuid))
          rel-path (str run-id File/separator art-name)
          _        (write-bytes! (ensure-dir! artifact-dir) rel-path data)
          size     (alength ^bytes data)
          digest   (sha256-hex data)
          now      (System/currentTimeMillis)]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO artifacts (id, run_id, name, path, content_type, size, hash, created_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
        art-id run-id art-name rel-path content-type size digest now])
      {:id art-id :run-id run-id :name art-name :path rel-path
       :content-type content-type :size size :hash digest :created-at now}))

  (-get-artifact [_ artifact-id]
    (->artifact-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM artifacts WHERE id = ?" artifact-id])))

  (-get-artifact-bytes [_ artifact-id]
    (when-let [row (jdbc/execute-one!
                    datasource
                    ["SELECT path FROM artifacts WHERE id = ?" artifact-id])]
      (let [^Path p (.toPath (io/file artifact-dir (:artifacts/path row)))]
        (Files/readAllBytes p))))

  (-find-artifact [_ run-id art-name]
    (->artifact-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM artifacts WHERE run_id = ? AND name = ?"
                         run-id art-name])))

  (-list-artifacts [_ run-id]
    (->> (jdbc/execute! datasource
                        ["SELECT * FROM artifacts WHERE run_id = ?
                          ORDER BY created_at, name" run-id])
         (mapv ->artifact-map)))

  p/ModelRegistry
  (-register-model! [_ model]
    (jdbc/execute-one!
     datasource
     ["INSERT INTO models (name, description, created_at) VALUES (?, ?, ?)
       ON CONFLICT(name) DO NOTHING"
      (:name model) (:description model) (System/currentTimeMillis)])
    (->model-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM models WHERE name = ?" (:name model)])))

  (-create-version! [_ version]
    (jdbc/with-transaction [tx datasource]
      (let [model-name (:model-name version)
            next-v     (or (-> (jdbc/execute-one!
                                tx
                                ["SELECT COALESCE(MAX(version), 0) + 1 AS v
                                  FROM model_versions WHERE model_name = ?" model-name])
                               :v)
                           1)
            stage      (or (:stage version) :none)
            now        (System/currentTimeMillis)]
        (when (= :production stage)
          (jdbc/execute! tx
                         ["UPDATE model_versions SET stage = 'archived'
                           WHERE model_name = ? AND stage = 'production'" model-name]))
        (jdbc/execute-one!
         tx
         ["INSERT INTO model_versions (model_name, version, run_id, artifact_id,
                                       stage, description, created_at)
           VALUES (?, ?, ?, ?, ?, ?, ?)"
          model-name next-v (:run-id version) (:artifact-id version)
          (kw->db stage) (:description version) now])
        {:model-name model-name :version next-v :run-id (:run-id version)
         :artifact-id (:artifact-id version) :stage stage
         :description (:description version) :created-at now})))

  (-get-model [_ model-name]
    (->model-map (jdbc/execute-one! datasource
                                    ["SELECT * FROM models WHERE name = ?" model-name])))

  (-get-version [_ model-name v]
    (->version-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM model_versions WHERE model_name = ? AND version = ?"
                         model-name v])))

  (-list-models [_]
    (->> (jdbc/execute! datasource ["SELECT * FROM models ORDER BY name"])
         (mapv ->model-map)))

  (-list-versions [_ model-name]
    (->> (jdbc/execute! datasource
                        ["SELECT * FROM model_versions WHERE model_name = ?
                          ORDER BY version" model-name])
         (mapv ->version-map)))

  (-set-stage! [_ model-name v stage]
    (jdbc/with-transaction [tx datasource]
      (when (= :production stage)
        (jdbc/execute! tx
                       ["UPDATE model_versions SET stage = 'archived'
                         WHERE model_name = ? AND stage = 'production' AND version <> ?"
                        model-name v]))
      (jdbc/execute-one! tx
                         ["UPDATE model_versions SET stage = ?
                           WHERE model_name = ? AND version = ?"
                          (kw->db stage) model-name v]))
    (->version-map
     (jdbc/execute-one! datasource
                        ["SELECT * FROM model_versions WHERE model_name = ? AND version = ?"
                         model-name v])))

  (-find-version [_ model-name selector]
    (let [row (cond
                (:version selector)
                (jdbc/execute-one!
                 datasource
                 ["SELECT * FROM model_versions
                   WHERE model_name = ? AND version = ?" model-name (:version selector)])
                (:stage selector)
                (jdbc/execute-one!
                 datasource
                 ["SELECT * FROM model_versions
                   WHERE model_name = ? AND stage = ?
                   ORDER BY version DESC LIMIT 1"
                  model-name (kw->db (:stage selector))])
                :else
                (jdbc/execute-one!
                 datasource
                 ["SELECT * FROM model_versions
                   WHERE model_name = ? AND stage = 'production'
                   ORDER BY version DESC LIMIT 1" model-name]))]
      (->version-map row)))

  p/Lifecycle
  (close! [_]
    (when (instance? HikariDataSource datasource)
      (.close ^HikariDataSource datasource))))

;; --- Public constructor -----------------------------------------------

(defn open
  "Open a Postgres-backed store with a HikariCP connection pool.

  Required options:
  - `:jdbc-url`      e.g. `\"jdbc:postgresql://localhost:5432/chachaml\"`

  Optional:
  - `:username`      Postgres user (default: from URL)
  - `:password`      Postgres password (default: from URL)
  - `:artifact-dir`  Directory for artifact bytes (default `./chachaml-artifacts`)
  - `:pool-size`     HikariCP max pool size (default 10)

  Returns a `PostgresStore` record implementing all protocols."
  [{:keys [jdbc-url username password artifact-dir pool-size]
    :or   {artifact-dir "chachaml-artifacts" pool-size 10}}]
  (let [config (doto (HikariConfig.)
                 (.setJdbcUrl jdbc-url)
                 (.setMaximumPoolSize pool-size))
        _      (when username (.setUsername config username))
        _      (when password (.setPassword config password))
        ds     (HikariDataSource. config)]
    (migrate! ds)
    (ensure-dir! artifact-dir)
    (->PostgresStore ds artifact-dir)))
