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
