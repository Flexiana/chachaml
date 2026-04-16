(ns chachaml.store.sqlite
  "SQLite-backed implementation of `chachaml.store.protocol`'s
  `RunStore`, `ArtifactStore`, and `Lifecycle` protocols. The
  `ModelRegistry` impl lands in M5.

  This is the default backend. A single SQLite file holds runs, params,
  metrics, and artifact metadata. Artifact bytes live on the filesystem
  under an artifact directory paired with the database file (see
  `default-artifact-dir`).

  Use `(open)` for the default `./chachaml.db` + `./chachaml-artifacts/`,
  or `(open {:path \"…\"})` for a specific location. `:in-memory? true`
  opens an ephemeral DB plus a temporary artifact directory; both are
  removed on `close!`.

  All schema creation is idempotent (`CREATE TABLE IF NOT EXISTS`); the
  schema is applied on every `open`."
  (:require [chachaml.store.protocol :as p]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as jsql])
  (:import [java.io File]
           [java.nio.file Files Path]
           [java.security MessageDigest]
           [java.sql Connection]))

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
   "CREATE INDEX IF NOT EXISTS idx_metrics_run ON metrics(run_id)"
   "CREATE TABLE IF NOT EXISTS artifacts (
      id           TEXT PRIMARY KEY,
      run_id       TEXT NOT NULL,
      name         TEXT NOT NULL,
      path         TEXT NOT NULL,
      content_type TEXT,
      size         INTEGER,
      hash         TEXT,
      created_at   INTEGER NOT NULL
    )"
   "CREATE INDEX IF NOT EXISTS idx_artifacts_run ON artifacts(run_id)"
   "CREATE UNIQUE INDEX IF NOT EXISTS idx_artifacts_run_name ON artifacts(run_id, name)"])

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

;; --- Artifact filesystem layer ---------------------------------------

(defn default-artifact-dir
  "Derive a sibling artifact directory for a database file path. The
  default `./chachaml.db` pairs with `./chachaml-artifacts/`."
  [db-path]
  (let [trimmed (str/replace db-path #"\.db$" "")]
    (str trimmed "-artifacts")))

(defn- ensure-dir! ^File [^String dir]
  (let [f (io/file dir)]
    (.mkdirs f)
    f))

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

(defn- delete-tree! [^File f]
  (when (.exists f)
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-tree! child)))
    (.delete f)))

(defn- ->artifact-map
  "Convert an `artifacts` row to a public artifact map."
  [row]
  (when row
    (let [{art-id     :artifacts/id
           run-id     :artifacts/run_id
           art-name   :artifacts/name
           rel-path   :artifacts/path
           ct         :artifacts/content_type
           size       :artifacts/size
           digest     :artifacts/hash
           created-at :artifacts/created_at} row]
      (cond-> {:id           art-id
               :run-id       run-id
               :name         art-name
               :path         rel-path
               :created-at   created-at}
        ct     (assoc :content-type ct)
        size   (assoc :size size)
        digest (assoc :hash digest)))))

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

(defrecord SQLiteStore [datasource path in-memory? artifact-dir]
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

  p/ArtifactStore
  (-put-artifact! [_ run-id art-name data content-type]
    (let [art-id   (str (random-uuid))
          rel-path (str run-id File/separator art-name)
          dir      (ensure-dir! artifact-dir)
          _        (write-bytes! dir rel-path data)
          size     (alength ^bytes data)
          digest   (sha256-hex data)
          now      (System/currentTimeMillis)]
      (jdbc/execute-one!
       datasource
       ["INSERT INTO artifacts (id, run_id, name, path, content_type,
                                size, hash, created_at)
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
     (jdbc/execute-one!
      datasource
      ["SELECT * FROM artifacts WHERE run_id = ? AND name = ?"
       run-id art-name])))

  (-list-artifacts [_ run-id]
    (->> (jdbc/execute!
          datasource
          ["SELECT * FROM artifacts WHERE run_id = ?
            ORDER BY created_at, name" run-id])
         (mapv ->artifact-map)))

  p/Lifecycle
  (close! [_]
    ;; In-memory mode holds an open Connection (closing it destroys the
    ;; database) and a temporary artifact directory we should remove.
    ;; File-backed mode uses an unpooled DataSource with no resources
    ;; to release; we leave the artifact directory in place.
    (when (instance? Connection datasource)
      (.close ^Connection datasource))
    (when (and in-memory? artifact-dir)
      (delete-tree! (io/file artifact-dir)))))

;; --- Public constructor -----------------------------------------------

(defn open
  "Open a SQLite-backed store. Idempotently creates the schema and
  ensures the artifact directory exists.

  Options:
  - `:path`         File path to the SQLite database. Defaults to
                    `./chachaml.db`. Ignored when `:in-memory? true`.
  - `:artifact-dir` Directory for artifact bytes. Defaults to
                    `default-artifact-dir` for file mode (a sibling of
                    the DB) or a fresh tempdir for in-memory mode.
  - `:in-memory?`   Open an ephemeral SQLite database backed by a
                    single in-process Connection plus a temp artifact
                    directory; both are removed on `close!`. Intended
                    for tests; not safe for concurrent use across
                    threads."
  ([] (open {}))
  ([{:keys [path in-memory? artifact-dir] :or {path "chachaml.db"}}]
   (if in-memory?
     (let [conn (jdbc/get-connection {:dbtype "sqlite" :dbname ":memory:"})
           dir  (or artifact-dir
                    (str (.toFile (Files/createTempDirectory
                                   "chachaml-mem-" (make-array java.nio.file.attribute.FileAttribute 0)))))]
       (migrate! conn)
       (ensure-dir! dir)
       (->SQLiteStore conn nil true dir))
     (let [datasource (jdbc/get-datasource {:dbtype "sqlite" :dbname path})
           dir        (or artifact-dir (default-artifact-dir path))]
       (migrate! datasource)
       (ensure-dir! dir)
       (->SQLiteStore datasource path false dir)))))
