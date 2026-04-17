(ns chachaml.ui.server
  "Ring HTTP server for the chachaml web UI.

  Start from the command line:

      clojure -M:ui                    # default ./chachaml.db, port 8080
      clojure -M:ui path/to.db 9090   # custom DB + port

  Or from a REPL:

      (require '[chachaml.ui.server :as ui])
      (def srv (ui/start! {:path \"chachaml.db\" :port 8080}))
      (.stop srv)  ; to shut down"
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.repl :as repl]
            [chachaml.store.sqlite :as sqlite]
            [chachaml.ui.api :as api]
            [chachaml.ui.views :as views]
            [clojure.string :as str]
            [reitit.ring :as reitit]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]))

;; --- HTML handlers ---------------------------------------------------

(defn- runs-handler [request]
  (let [params      (:query-params request)
        exp         (not-empty (get params "experiment"))
        status      (not-empty (get params "status"))
        filters     (cond-> {:limit 200}
                      exp    (assoc :experiment exp)
                      status (assoc :status (keyword status)))
        runs        (ml/runs filters)
        all-runs    (ml/runs {:limit 10000})
        experiments (vec (sort (distinct (map :experiment all-runs))))]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (views/runs-page runs experiments exp status)}))

(defn- run-handler [request]
  (let [run-id (get-in request [:path-params :id])]
    (if-let [r (ml/run run-id)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (views/run-page r)}
      {:status 404 :body "Run not found"})))

(defn- compare-handler [request]
  (let [ids-str (get-in request [:query-params "ids"])
        ids     (when ids-str
                  (vec (remove str/blank? (str/split ids-str #","))))]
    (if (and ids (>= (count ids) 2))
      (let [full-runs  (mapv ml/run ids)
            comparison (repl/compare-runs ids)]
        {:status 200
         :headers {"Content-Type" "text/html; charset=utf-8"}
         :body (views/compare-page ids comparison full-runs)})
      {:status 400
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body "Provide at least 2 comma-separated run ids via ?ids=a,b"})))

(defn- models-handler [_request]
  (let [models (reg/models)
        vcounts (into {} (map (fn [m]
                                [(:name m)
                                 (count (reg/model-versions (:name m)))]))
                      models)]
    {:status 200
     :headers {"Content-Type" "text/html; charset=utf-8"}
     :body (views/models-page models vcounts)}))

(defn- model-handler [request]
  (let [model-name (get-in request [:path-params :name])]
    (if-let [m (reg/model model-name)]
      {:status 200
       :headers {"Content-Type" "text/html; charset=utf-8"}
       :body (views/model-page m (reg/model-versions model-name))}
      {:status 404 :body "Model not found"})))

;; --- Router ----------------------------------------------------------

(def ^:private routes
  [["/" {:get {:handler (fn [_] {:status 302 :headers {"Location" "/runs"}})}}]
   ["/runs" {:get {:handler runs-handler}}]
   ["/runs/:id" {:get {:handler run-handler}}]
   ["/compare" {:get {:handler compare-handler}}]
   ["/models" {:get {:handler models-handler}}]
   ["/models/:name" {:get {:handler model-handler}}]
   ;; JSON API
   ["/api/runs" {:get {:handler api/list-runs-handler}}]
   ["/api/runs/:id" {:get {:handler api/get-run-handler}}]
   ["/api/compare" {:get {:handler api/compare-runs-handler}}]
   ["/api/export" {:get {:handler api/export-csv-handler}}]
   ["/api/models" {:get {:handler api/list-models-handler}}]
   ["/api/models/:name" {:get {:handler api/get-model-handler}}]
   ["/api/experiments" {:get {:handler api/experiments-handler}}]
   ["/api/artifacts/:id/download" {:get {:handler api/artifact-download-handler}}]
   ;; v0.4 endpoints
   ["/api/tags/:id" {:get  {:handler api/get-tags-handler}
                     :post {:handler api/add-tag-handler}}]
   ["/api/note/:id" {:post {:handler api/set-run-note-handler}}]
   ["/api/datasets/:id" {:get {:handler api/get-datasets-handler}}]
   ["/api/search" {:get {:handler api/search-runs-handler}}]
   ["/api/model-note/:name" {:post {:handler api/set-model-note-handler}}]
   ["/api/diff/:name/:v1/:v2" {:get {:handler api/diff-versions-handler}}]])

(defn app
  "Build the Ring handler (with query-params middleware). Requires
  `chachaml.context/*store*` to be bound."
  []
  (-> (reitit/ring-handler
       (reitit/router routes)
       (reitit/create-default-handler))
      (wrap-resource "public")
      wrap-params))

;; --- Lifecycle -------------------------------------------------------

(defn start!
  "Start the Jetty server. Returns the Jetty Server instance (call
  `.stop` to shut down).

  Options:
  - `:path`  SQLite DB path (default `\"chachaml.db\"`)
  - `:port`  HTTP port (default `8080`)
  - `:join?` Block the calling thread (default `false`)"
  ([] (start! {}))
  ([{:keys [path port join?]
     :or   {path "chachaml.db" port 8080 join? false}}]
   (let [store (sqlite/open {:path path})]
     (alter-var-root #'ctx/*store* (constantly store))
     (println (str "[chachaml-ui] http://localhost:" port
                   "  (store: " path ")"))
     (jetty/run-jetty (app) {:port port :join? join?}))))

(defn -main
  "CLI entry: `clojure -M:ui [db-path] [port]`."
  [& args]
  (let [path (or (first args) "chachaml.db")
        port (if (second args) (parse-long (second args)) 8080)]
    (start! {:path path :port port :join? true})))
