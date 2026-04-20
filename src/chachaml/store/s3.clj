(ns chachaml.store.s3
  "S3 artifact storage for chachaml.

  Implements `ArtifactStore` only — metadata lives in your RunStore
  (SQLite or Postgres). Artifact bytes go to S3/MinIO.

  Usage:

      ;; In store/open opts:
      (store/open {:type         :postgres
                   :jdbc-url     \"jdbc:postgresql://...\"
                   :artifact-store {:type   :s3
                                   :bucket \"ml-artifacts\"
                                   :prefix \"chachaml/\"}})

  Or directly:

      (require '[chachaml.store.s3 :as s3])
      (def art-store (s3/open {:bucket \"ml-artifacts\"
                               :prefix \"chachaml/\"
                               :endpoint \"http://minio:9000\"}))

  Requires the `:s3` deps alias (adds Cognitect aws-api)."
  (:require [chachaml.store.protocol :as p]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]))

;; --- AWS helpers (resolved at runtime) --------------------------------

(defn- aws-fn
  "Resolve a Cognitect aws-api function at runtime."
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info "aws-api not on classpath; add :s3 alias"
                      {:symbol sym}))))

(defn- make-client [{:keys [endpoint region]}]
  (let [client-fn (aws-fn 'cognitect.aws.client.api/client)]
    (client-fn (cond-> {:api :s3}
                 region   (assoc :region region)
                 endpoint (assoc :endpoint-override
                                 {:protocol :http
                                  :hostname (-> endpoint
                                                (str/replace #"^https?://" "")
                                                (str/replace #":\d+$" ""))
                                  :port (let [m (re-find #":(\d+)$" endpoint)]
                                          (when m (parse-long (second m))))})))))

(defn- s3-invoke [client op request]
  (let [invoke-fn (aws-fn 'cognitect.aws.client.api/invoke)]
    (invoke-fn client (assoc request :op op))))

;; --- Helpers ----------------------------------------------------------

(defn- sha256-hex ^String [^bytes data]
  (let [md (MessageDigest/getInstance "SHA-256")
        h  (.digest md data)]
    (apply str (map #(format "%02x" (bit-and (long %) 0xff)) h))))

(defn- s3-path [prefix run-id art-name]
  (str prefix run-id "/" art-name))

;; --- ArtifactStore implementation ------------------------------------

(defrecord S3ArtifactStore [client bucket prefix metadata-store]
  ;; Note: this record implements ArtifactStore only. It delegates
  ;; metadata operations to the underlying RunStore's metadata tables
  ;; via metadata-store (a SQLite or Postgres store).

  p/ArtifactStore
  (-put-artifact! [_ run-id art-name data content-type]
    (let [art-id   (str (random-uuid))
          s3key    (s3-path prefix run-id art-name)
          size     (alength ^bytes data)
          digest   (sha256-hex data)
          now      (System/currentTimeMillis)]
      ;; Upload to S3
      (s3-invoke client :PutObject
                 {:Bucket bucket
                  :Key    s3key
                  :Body   data
                  :ContentType (or content-type "application/octet-stream")})
      ;; Store metadata in the underlying store
      (let [ds (:datasource metadata-store)]
        (when ds
          ((requiring-resolve 'next.jdbc/execute-one!)
           ds
           ["INSERT INTO artifacts (id, run_id, name, path, content_type, size, hash, created_at)
             VALUES (?, ?, ?, ?, ?, ?, ?, ?)"
            art-id run-id art-name s3key content-type size digest now])))
      {:id art-id :run-id run-id :name art-name :path s3key
       :content-type content-type :size size :hash digest :created-at now}))

  (-get-artifact [_ artifact-id]
    (when-let [ds (:datasource metadata-store)]
      (let [row ((requiring-resolve 'next.jdbc/execute-one!)
                 ds ["SELECT * FROM artifacts WHERE id = ?" artifact-id])]
        (when row
          {:id           (:artifacts/id row)
           :run-id       (:artifacts/run_id row)
           :name         (:artifacts/name row)
           :path         (:artifacts/path row)
           :content-type (:artifacts/content_type row)
           :size         (:artifacts/size row)
           :hash         (:artifacts/hash row)
           :created-at   (:artifacts/created_at row)}))))

  (-get-artifact-bytes [_ artifact-id]
    (when-let [ds (:datasource metadata-store)]
      (let [row ((requiring-resolve 'next.jdbc/execute-one!)
                 ds ["SELECT path FROM artifacts WHERE id = ?" artifact-id])]
        (when row
          (let [resp (s3-invoke client :GetObject
                                {:Bucket bucket :Key (:artifacts/path row)})]
            (when (:Body resp)
              (let [bos (java.io.ByteArrayOutputStream.)]
                (io/copy (:Body resp) bos)
                (.toByteArray bos))))))))

  (-find-artifact [_ run-id art-name]
    (when-let [ds (:datasource metadata-store)]
      (let [row ((requiring-resolve 'next.jdbc/execute-one!)
                 ds ["SELECT * FROM artifacts WHERE run_id = ? AND name = ?"
                     run-id art-name])]
        (when row
          {:id           (:artifacts/id row)
           :run-id       (:artifacts/run_id row)
           :name         (:artifacts/name row)
           :path         (:artifacts/path row)
           :content-type (:artifacts/content_type row)
           :size         (:artifacts/size row)
           :hash         (:artifacts/hash row)
           :created-at   (:artifacts/created_at row)}))))

  (-list-artifacts [_ run-id]
    (when-let [ds (:datasource metadata-store)]
      (->> ((requiring-resolve 'next.jdbc/execute!)
            ds ["SELECT * FROM artifacts WHERE run_id = ? ORDER BY created_at, name" run-id])
           (mapv (fn [row]
                   {:id           (:artifacts/id row)
                    :run-id       (:artifacts/run_id row)
                    :name         (:artifacts/name row)
                    :path         (:artifacts/path row)
                    :content-type (:artifacts/content_type row)
                    :size         (:artifacts/size row)
                    :hash         (:artifacts/hash row)
                    :created-at   (:artifacts/created_at row)})))))

  p/Lifecycle
  (close! [_] nil))

;; --- Public constructor -----------------------------------------------

(defn open
  "Open an S3-backed artifact store.

  Options:
  - `:bucket`          S3 bucket name (required)
  - `:prefix`          Key prefix (default `\"chachaml/\"`)
  - `:endpoint`        S3-compatible endpoint URL (for MinIO: `\"http://minio:9000\"`)
  - `:region`          AWS region (default: from env/profile)
  - `:metadata-store`  The underlying SQLite/Postgres store for artifact metadata"
  [{:keys [bucket prefix endpoint region metadata-store]
    :or   {prefix "chachaml/"}}]
  (let [client (make-client {:endpoint endpoint :region region})]
    (->S3ArtifactStore client bucket prefix metadata-store)))
