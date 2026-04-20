(ns chachaml.ui-test
  "Tests for the chachaml web UI routes and API endpoints.

  Uses ring-mock to exercise the full handler stack (router + middleware)
  without starting Jetty."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [chachaml.ui.server :as ui]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is use-fixtures]]
            [ring.mock.request :as mock]))

(use-fixtures :each h/with-fresh-store)

(defn- handler [] (ui/app))

(defn- get-html [path]
  ((handler) (mock/request :get path)))

(defn- get-json [path]
  (let [resp ((handler) (mock/request :get path))]
    (assoc resp :parsed (when (= 200 (:status resp))
                          (json/read-str (:body resp) :key-fn keyword)))))

;; --- HTML routes -----------------------------------------------------

(deftest root-redirects-to-runs
  (let [resp (get-html "/")]
    (is (= 302 (:status resp)))
    (is (= "/runs" (get-in resp [:headers "Location"])))))

(deftest runs-page-empty
  (let [resp (get-html "/runs")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "No runs yet"))))

(deftest runs-page-with-data
  (ml/with-run {:experiment "test-exp" :name "run-1"}
    (ml/log-params {:lr 0.01}))
  (let [resp (get-html "/runs")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "test-exp"))
    (is (str/includes? (:body resp) "completed"))))

(deftest runs-page-filters
  (ml/with-run {:experiment "a"})
  (ml/with-run {:experiment "b"})
  (let [resp (get-html "/runs?experiment=a")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "a"))))

(deftest run-detail-page
  (let [rid (atom nil)]
    (ml/with-run {:experiment "demo"}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01})
      (ml/log-metric :loss 0.5 0)
      (ml/log-metric :loss 0.3 1)
      (ml/log-metric :acc 0.9)
      (ml/log-artifact "model" {:w [1.0]}))
    (let [resp (get-html (str "/runs/" @rid))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "demo"))
      (is (str/includes? (:body resp) "Params"))
      (is (str/includes? (:body resp) ":lr"))
      (is (str/includes? (:body resp) "vegaEmbed"))
      (is (str/includes? (:body resp) "Artifacts")))))

(deftest run-detail-404
  (is (= 404 (:status (get-html "/runs/no-such-id")))))

(deftest compare-page-renders
  (let [ids (atom [])]
    (ml/with-run {:experiment "x"}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01}))
    (ml/with-run {:experiment "x"}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.05}))
    (let [resp (get-html (str "/compare?ids=" (str/join "," @ids)))]
      (is (= 200 (:status resp)))
      (is (str/includes? (:body resp) "Run Comparison"))
      (is (str/includes? (:body resp) "differ")))))

(deftest compare-page-needs-2-ids
  (is (= 400 (:status (get-html "/compare?ids=one-only")))))

(deftest models-page-empty
  (let [resp (get-html "/models")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "No models registered"))))

(deftest models-page-with-data
  (ml/with-run {}
    (ml/log-artifact "m" {:w 1})
    (reg/register-model "iris" {:artifact "m" :stage :staging}))
  (let [resp (get-html "/models")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "iris"))))

(deftest model-detail-page
  (ml/with-run {}
    (ml/log-artifact "m" {:w 1})
    (reg/register-model "iris" {:artifact "m" :stage :staging}))
  (let [resp (get-html "/models/iris")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "v1"))
    (is (str/includes? (:body resp) "staging"))))

(deftest model-detail-404
  (is (= 404 (:status (get-html "/models/nope")))))

;; --- JSON API --------------------------------------------------------

(deftest api-runs
  (ml/with-run {:experiment "x"})
  (let [{:keys [status parsed]} (get-json "/api/runs")]
    (is (= 200 status))
    (is (= 1 (count parsed)))
    (is (= "x" (:experiment (first parsed))))))

(deftest api-run-detail
  (let [rid (atom nil)]
    (ml/with-run {}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-params {:k "v"}))
    (let [{:keys [status parsed]} (get-json (str "/api/runs/" @rid))]
      (is (= 200 status))
      (is (= "v" (get-in parsed [:params :k]))))))

(deftest api-models
  (ml/with-run {} (ml/log-artifact "m" {:w 1})
               (reg/register-model "demo" {:artifact "m"}))
  (let [{:keys [status parsed]} (get-json "/api/models")]
    (is (= 200 status))
    (is (= "demo" (:name (first parsed))))))

(deftest api-model-detail
  (ml/with-run {} (ml/log-artifact "m" {:w 1})
               (reg/register-model "demo" {:artifact "m" :stage :staging}))
  (let [{:keys [status parsed]} (get-json "/api/models/demo")]
    (is (= 200 status))
    (is (= "demo" (get-in parsed [:model :name])))
    (is (= 1 (count (:versions parsed))))))

(deftest api-compare
  (let [ids (atom [])]
    (ml/with-run {:experiment "x"}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01}))
    (ml/with-run {:experiment "x"}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.05}))
    (let [{:keys [status parsed]}
          (get-json (str "/api/compare?ids=" (str/join "," @ids)))]
      (is (= 200 status))
      (is (= 2 (count (:runs parsed)))))))

(deftest api-compare-bad-request
  (is (= 400 (:status (get-json "/api/compare?ids=one")))))

(deftest api-experiments
  (ml/with-run {:experiment "a"})
  (ml/with-run {:experiment "b"})
  (let [{:keys [status parsed]} (get-json "/api/experiments")]
    (is (= 200 status))
    (is (= ["a" "b"] parsed))))

(deftest api-run-404
  (is (= 404 (:status (get-json "/api/runs/no-such")))))

(deftest api-model-404
  (is (= 404 (:status (get-json "/api/models/no-such")))))

;; --- Static assets ---------------------------------------------------

(deftest static-js-served
  (let [resp ((handler) (mock/request :get "/js/htmx.min.js"))]
    (is (= 200 (:status resp)))))

;; --- Write API (M17) -------------------------------------------------

(defn- post-json [path body]
  ((handler)
   (-> (mock/request :post path)
       (mock/content-type "application/json")
       (mock/body (json/write-str body)))))

(deftest write-api-full-lifecycle
  (let [;; 1. Start a run
        start-resp (post-json "/api/w/runs"
                              {:experiment "write-test" :name "api-run"})
        run        (json/read-str (:body start-resp) :key-fn keyword)
        run-id     (:id run)]
    (is (= 200 (:status start-resp)))
    (is (some? run-id))
    (is (= "write-test" (:experiment run)))

    ;; 2. Log params
    (let [resp (post-json (str "/api/w/runs/" run-id "/params")
                          {:lr 0.01 :epochs 50})]
      (is (= 200 (:status resp))))

    ;; 3. Log metrics
    (let [resp (post-json (str "/api/w/runs/" run-id "/metrics")
                          {:accuracy 0.94})]
      (is (= 200 (:status resp))))

    ;; 4. Log metrics with step
    (let [resp (post-json (str "/api/w/runs/" run-id "/metrics")
                          {:metrics {:loss 0.3} :step 5})]
      (is (= 200 (:status resp))))

    ;; 5. Log artifact
    (let [resp (post-json (str "/api/w/runs/" run-id "/artifacts")
                          {:name "model" :value {:weights [1 2 3]}})]
      (is (= 200 (:status resp))))

    ;; 6. End the run
    (let [resp (post-json (str "/api/w/runs/" run-id "/end")
                          {:status "completed"})
          body (json/read-str (:body resp) :key-fn keyword)]
      (is (= 200 (:status resp)))
      (is (= "completed" (name (:status body)))))

    ;; 7. Verify via read API
    (let [{:keys [parsed]} (get-json (str "/api/runs/" run-id))]
      (is (= 0.01 (get-in parsed [:params :lr])))
      (is (= 2 (count (:metrics parsed))))
      (is (= 1 (count (:artifacts parsed)))))))

;; --- v0.4 pages + endpoints -----------------------------------------

(deftest experiments-page-renders
  (ml/create-experiment! "exp-test" {:description "Test experiment"})
  (ml/with-run {:experiment "exp-test"})
  (let [resp (get-html "/experiments")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "exp-test"))))

(deftest search-page-renders
  (let [resp (get-html "/search")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "Search"))))

(deftest search-page-with-results
  (ml/with-run {:experiment "search-test"}
    (ml/log-metric :accuracy 0.95))
  (let [resp (get-html "/search?metric_key=accuracy&op=%3E&metric_value=0.9")]
    (is (= 200 (:status resp)))
    (is (str/includes? (:body resp) "search-test"))))

(deftest api-tags-crud
  (let [rid (atom nil)]
    (ml/with-run {} (reset! rid (:id (ctx/current-run))))
    ;; Add tag via POST
    (let [resp ((handler)
                (-> (mock/request :post (str "/api/tags/" @rid))
                    (mock/content-type "application/json")
                    (mock/body "{\"key\":\"quality\",\"value\":\"good\"}")))]
      (is (= 200 (:status resp))))
    ;; Read tags via GET
    (let [{:keys [status parsed]} (get-json (str "/api/tags/" @rid))]
      (is (= 200 status))
      (is (= "good" (:quality parsed))))))

(deftest api-datasets
  (let [rid (atom nil)]
    (ml/with-run {}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-dataset! {:role "train" :n-rows 100}))
    (let [{:keys [status parsed]} (get-json (str "/api/datasets/" @rid))]
      (is (= 200 status))
      (is (= 1 (count parsed))))))

(deftest api-search
  (ml/with-run {:experiment "api-search"}
    (ml/log-metric :accuracy 0.95))
  (let [{:keys [status parsed]}
        (get-json "/api/search?metric_key=accuracy&op=%3E&metric_value=0.9")]
    (is (= 200 status))
    (is (pos? (count parsed)))))

(deftest export-csv-downloads
  (ml/with-run {:experiment "csv"} (ml/log-params {:lr 0.01}))
  (let [resp ((handler) (mock/request :get "/api/export?experiment=csv"))]
    (is (= 200 (:status resp)))
    (is (str/includes? (get-in resp [:headers "Content-Type"]) "text/csv"))))
