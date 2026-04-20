(ns chachaml.ui.api
  "JSON API endpoints for the chachaml web UI.

  These endpoints power both the HTMX front-end and serve as the
  foundation for a future chat-with-data layer. All handlers are
  Ring-compatible functions returning response maps."
  (:require [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.repl :as repl]
            [chachaml.store.protocol :as p]
            [chachaml.context :as ctx]
            [clojure.data.json :as json]
            [clojure.string :as str]))

(defn- json-response
  "Wrap `data` as a Ring response with JSON body."
  [data]
  {:status  200
   :headers {"Content-Type" "application/json; charset=utf-8"}
   :body    (json/write-str data)})

(defn list-runs-handler
  "GET /api/runs?experiment=…&status=…&limit=…"
  [request]
  (let [params (:query-params request)
        exp    (get params "experiment")
        stat   (get params "status")
        lim    (get params "limit")
        filters (cond-> {:limit 100}
                  exp  (assoc :experiment exp)
                  stat (assoc :status (keyword stat))
                  lim  (assoc :limit (parse-long lim)))]
    (json-response (ml/runs filters))))

(defn get-run-handler
  "GET /api/runs/:id"
  [request]
  (let [run-id (get-in request [:path-params :id])]
    (if-let [r (ml/run run-id)]
      (json-response r)
      {:status 404 :body "Not found"})))

(defn compare-runs-handler
  "GET /api/runs/compare?ids=a,b,c"
  [request]
  (let [ids-str (get-in request [:query-params "ids"])
        ids     (when ids-str (str/split ids-str #","))]
    (if (and ids (>= (count ids) 2))
      (json-response (repl/compare-runs ids))
      {:status 400 :body "Provide at least 2 comma-separated ids"})))

(defn list-models-handler
  "GET /api/models"
  [_request]
  (json-response (reg/models)))

(defn get-model-handler
  "GET /api/models/:name"
  [request]
  (let [model-name (get-in request [:path-params :name])]
    (if-let [m (reg/model model-name)]
      (json-response {:model    m
                      :versions (reg/model-versions model-name)})
      {:status 404 :body "Not found"})))

(defn experiments-handler
  "GET /api/experiments — distinct experiment names."
  [_request]
  (let [all-runs (ml/runs {:limit 10000})
        exps     (vec (sort (distinct (map :experiment all-runs))))]
    (json-response exps)))

(defn artifact-download-handler
  "GET /api/artifacts/:id/download — serve raw artifact bytes."
  [request]
  (let [artifact-id (get-in request [:path-params :id])
        store       (ctx/current-store)]
    (if-let [art (p/-get-artifact store artifact-id)]
      (let [data (p/-get-artifact-bytes store artifact-id)]
        {:status  200
         :headers {"Content-Type"        (or (:content-type art) "application/octet-stream")
                   "Content-Disposition" (str "inline; filename=\"" (:name art) "\"")}
         :body    (java.io.ByteArrayInputStream. data)})
      {:status 404 :body "Artifact not found"})))

(defn- csv-cell
  "Escape and quote a value for CSV output."
  [v]
  (str "\"" (str/replace (str (or v "")) "\"" "\"\"") "\""))

(defn export-csv-handler
  "GET /api/runs/export?format=csv&experiment=..."
  [request]
  (let [params   (:query-params request)
        exp      (get params "experiment")
        lim      (get params "limit")
        filters  (cond-> {}
                   exp (assoc :experiment exp)
                   lim (assoc :limit (parse-long lim)))
        rows     (ml/export-runs filters)
        all-keys (->> rows (mapcat keys) distinct sort vec)
        header   (->> all-keys (map #(csv-cell (name %))) (str/join ","))
        format-row (fn [row]
                     (->> all-keys
                          (map #(csv-cell (get row %)))
                          (str/join ",")))
        csv      (str header "\n" (str/join "\n" (map format-row rows)) "\n")]
    {:status  200
     :headers {"Content-Type" "text/csv; charset=utf-8"
               "Content-Disposition" "attachment; filename=\"runs.csv\""}
     :body    csv}))

(defn get-tags-handler
  "GET /api/runs/:id/tags"
  [request]
  (json-response (ml/get-tags (get-in request [:path-params :id]))))

(defn add-tag-handler
  "POST /api/runs/:id/tags — JSON body `{\"key\":\"k\",\"value\":\"v\"}`."
  [request]
  (let [body (json/read-str (slurp (:body request)) :key-fn keyword)]
    (ml/add-tag! (get-in request [:path-params :id])
                 (keyword (:key body)) (:value body))
    (json-response {:ok true})))

(defn set-run-note-handler
  "POST /api/runs/:id/note — JSON body `{\"note\":\"...\"}`."
  [request]
  (let [body (json/read-str (slurp (:body request)) :key-fn keyword)]
    (ml/set-note! (get-in request [:path-params :id]) (:note body))
    (json-response {:ok true})))

(defn get-datasets-handler
  "GET /api/runs/:id/datasets"
  [request]
  (json-response (ml/get-datasets (get-in request [:path-params :id]))))

(defn search-runs-handler
  "GET /api/search?metric_key=accuracy&op=>&metric_value=0.9&experiment=..."
  [request]
  (let [params (:query-params request)
        opts   (cond-> {:limit (or (some-> (get params "limit") parse-long) 100)}
                 (get params "experiment")   (assoc :experiment (get params "experiment"))
                 (get params "metric_key")   (assoc :metric-key (keyword (get params "metric_key")))
                 (get params "op")           (assoc :op (keyword (get params "op")))
                 (get params "metric_value") (assoc :metric-value (parse-double (get params "metric_value"))))]
    (json-response (ml/search-runs opts))))

(defn set-model-note-handler
  "POST /api/models/:name/note — JSON body `{\"note\":\"...\"}`."
  [request]
  (let [model-name (get-in request [:path-params :name])
        body       (json/read-str (slurp (:body request)) :key-fn keyword)]
    (p/-upsert-experiment! (ctx/current-store)
                           {:name model-name :description (:note body)})
    (json-response {:ok true})))

(defn chat-handler
  "POST /api/chat — proxy a question to an LLM with chachaml tools.

  JSON body: `{\"question\":\"...\",\"provider\":\"anthropic\",
               \"api_key\":\"sk-...\",\"model\":\"claude-sonnet-4-20250514\"}`"
  [request]
  (try
    (let [body    (json/read-str (slurp (:body request)) :key-fn keyword)
          chat-fn (requiring-resolve 'chachaml.chat/ask)
          result  (chat-fn (:question body)
                           {:provider (keyword (:provider body))
                            :api-key  (:api_key body)
                            :model    (:model body)})]
      (json-response result))
    (catch Exception e
      {:status 500
       :headers {"Content-Type" "application/json"}
       :body (json/write-str {:error (ex-message e)})})))

(defn diff-versions-handler
  "GET /api/diff/:name/:v1/:v2"
  [request]
  (let [{model-name :name v1-str :v1 v2-str :v2} (:path-params request)]
    (if-let [diff (reg/diff-versions model-name (parse-long v1-str) (parse-long v2-str))]
      (json-response diff)
      {:status 404 :body "Versions not found"})))

;; === Write API (M17) — for non-Clojure clients =======================
;;
;; These endpoints let Python, Go, curl, etc. create and update runs
;; via HTTP. The Clojure library writes directly via JDBC; these are
;; the HTTP equivalent.

(defn- read-json-body
  "Parse JSON request body into a Clojure map with keyword keys."
  [request]
  (json/read-str (slurp (:body request)) :key-fn keyword))

(defn start-run-handler
  "POST /api/w/runs — start a new run.

  JSON body: `{\"experiment\":\"...\",\"name\":\"...\",\"tags\":{...}}`
  Returns the run map with `:id`."
  [request]
  (let [body (read-json-body request)
        run  (ml/start-run! (cond-> {}
                              (:experiment body) (assoc :experiment (:experiment body))
                              (:name body)       (assoc :name (:name body))
                              (:tags body)       (assoc :tags (:tags body))
                              (:created_by body) (assoc :created-by (:created_by body))))]
    (json-response run)))

(defn log-params-handler
  "POST /api/w/runs/:id/params — log params on a run.

  JSON body: `{\"lr\":0.01,\"epochs\":100}`"
  [request]
  (let [run-id (get-in request [:path-params :id])
        params (read-json-body request)]
    (binding [ctx/*run* {:id run-id}]
      (ml/log-params params))
    (json-response {:ok true})))

(defn log-metrics-handler
  "POST /api/w/runs/:id/metrics — log metrics on a run.

  JSON body: `{\"accuracy\":0.94,\"loss\":0.3}` or with step:
  `{\"metrics\":{\"loss\":0.3},\"step\":5}`"
  [request]
  (let [run-id (get-in request [:path-params :id])
        body   (read-json-body request)
        step   (or (:step body) 0)
        metrics (or (:metrics body) (dissoc body :step))]
    (binding [ctx/*run* {:id run-id}]
      (ml/log-metrics metrics {:step step}))
    (json-response {:ok true})))

(defn end-run-handler
  "POST /api/w/runs/:id/end — end a run.

  JSON body: `{\"status\":\"completed\"}` or `{\"status\":\"failed\",\"error\":\"...\"}`"
  [request]
  (let [run-id  (get-in request [:path-params :id])
        body    (read-json-body request)
        status  (keyword (or (:status body) "completed"))
        error   (:error body)
        updated (ml/end-run! run-id status error)]
    (json-response updated)))

(defn log-artifact-handler
  "POST /api/w/runs/:id/artifacts — log an artifact (JSON value, nippy-serialized).

  JSON body: `{\"name\":\"model\",\"value\":{...}}` or
  `{\"name\":\"config\",\"value\":{...},\"format\":\"edn\"}`"
  [request]
  (let [run-id (get-in request [:path-params :id])
        body   (read-json-body request)
        opts   (cond-> {}
                 (:format body) (assoc :format (keyword (:format body))))]
    (binding [ctx/*run* {:id run-id}]
      (let [art (ml/log-artifact (:name body) (:value body) opts)]
        (json-response art)))))

(defn register-model-handler
  "POST /api/w/models — register a model version.

  JSON body: `{\"model_name\":\"iris\",\"run_id\":\"...\",
               \"artifact\":\"model\",\"stage\":\"staging\"}`"
  [request]
  (let [body (read-json-body request)]
    (binding [ctx/*run* {:id (:run_id body)}]
      (let [version (reg/register-model
                     (:model_name body)
                     (cond-> {:artifact (:artifact body)}
                       (:stage body)       (assoc :stage (keyword (:stage body)))
                       (:description body) (assoc :description (:description body))
                       (:run_id body)      (assoc :run-id (:run_id body))))]
        (json-response version)))))
