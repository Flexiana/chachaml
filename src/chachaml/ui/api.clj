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
        filters (cond-> {}
                  (get params "experiment") (assoc :experiment (get params "experiment"))
                  (get params "status")     (assoc :status (keyword (get params "status")))
                  (get params "limit")      (assoc :limit (parse-long (get params "limit"))))]
    (json-response (ml/runs (merge {:limit 100} filters)))))

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

(defn export-csv-handler
  "GET /api/runs/export?format=csv&experiment=..."
  [request]
  (let [params  (:query-params request)
        filters (cond-> {}
                  (get params "experiment") (assoc :experiment (get params "experiment"))
                  (get params "limit")      (assoc :limit (parse-long (get params "limit"))))
        rows    (ml/export-runs filters)
        all-keys (vec (sort (distinct (mapcat keys rows))))
        header  (str/join "," (map #(str "\"" (name %) "\"") all-keys))
        lines   (map (fn [row]
                       (str/join "," (map (fn [k] (let [v (get row k "")]
                                                    (str "\"" (str/replace (str v) "\"" "\"\"") "\"")))
                                          all-keys)))
                     rows)
        csv     (str header "\n" (str/join "\n" lines) "\n")]
    {:status  200
     :headers {"Content-Type" "text/csv; charset=utf-8"
               "Content-Disposition" "attachment; filename=\"runs.csv\""}
     :body    csv}))
