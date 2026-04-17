(ns chachaml.v04-features-test
  "Tests for v0.4 features: mutable tags, dataset tracking, metric
  search, batch logging, table artifacts, export, experiments, version
  diff."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

;; --- Mutable tags + notes --------------------------------------------

(deftest add-tag-after-run-completes
  (let [rid (atom nil)]
    (ml/with-run {:experiment "t"}
      (reset! rid (:id (ctx/current-run))))
    (ml/add-tag! @rid :reviewed "true")
    (ml/add-tag! @rid :quality "good")
    (let [tags (ml/get-tags @rid)]
      (is (= "true" (:reviewed tags)))
      (is (= "good" (:quality tags))))))

(deftest add-tag-overwrites
  (let [rid (atom nil)]
    (ml/with-run {} (reset! rid (:id (ctx/current-run))))
    (ml/add-tag! @rid :status "pending")
    (ml/add-tag! @rid :status "approved")
    (is (= "approved" (:status (ml/get-tags @rid))))))

(deftest set-note-convenience
  (let [rid (atom nil)]
    (ml/with-run {} (reset! rid (:id (ctx/current-run))))
    (ml/set-note! @rid "This run had a data leak")
    (is (= "This run had a data leak" (:note (ml/get-tags @rid))))))

(deftest run-includes-mutable-tags
  (let [rid (atom nil)]
    (ml/with-run {:tags {:author "ci"}}
      (reset! rid (:id (ctx/current-run))))
    (ml/add-tag! @rid :reviewed "yes")
    (let [r (ml/run @rid)]
      (is (= "ci" (:author (:tags r))))
      (is (= "yes" (:reviewed (:tags r)))))))

;; --- Dataset tracking ------------------------------------------------

(deftest log-dataset-basic
  (ml/with-run {:experiment "ds"}
    (let [ds (ml/log-dataset! {:role     "train"
                               :n-rows   100
                               :n-cols   5
                               :features [:a :b :c :d :e]
                               :hash     "abc123"
                               :source   "/data/train.csv"})]
      (is (string? (:id ds)))
      (is (= 100 (:n-rows ds)))
      (is (= [:a :b :c :d :e] (:features ds))))))

(deftest get-datasets-returns-all
  (let [rid (atom nil)]
    (ml/with-run {}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-dataset! {:role "train" :n-rows 80 :n-cols 3})
      (ml/log-dataset! {:role "test" :n-rows 20 :n-cols 3}))
    (let [ds (ml/get-datasets @rid)]
      (is (= 2 (count ds)))
      (is (= #{"train" "test"} (set (map :role ds)))))))

(deftest run-includes-datasets
  (let [rid (atom nil)]
    (ml/with-run {}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-dataset! {:role "train" :n-rows 100}))
    (is (= 1 (count (:datasets (ml/run @rid)))))))

;; --- Metric-based search ---------------------------------------------

(deftest search-runs-by-metric
  (ml/with-run {:experiment "search"}
    (ml/log-metric :accuracy 0.95))
  (ml/with-run {:experiment "search"}
    (ml/log-metric :accuracy 0.80))
  (let [results (ml/search-runs {:experiment "search"
                                 :metric-key :accuracy
                                 :op :> :metric-value 0.9})]
    (is (= 1 (count results)))))

(deftest best-run-returns-top
  (ml/with-run {:experiment "best"}
    (ml/log-metric :accuracy 0.70))
  (ml/with-run {:experiment "best"}
    (ml/log-metric :accuracy 0.95))
  (ml/with-run {:experiment "best"}
    (ml/log-metric :accuracy 0.85))
  (let [b (ml/best-run {:experiment "best" :metric :accuracy})]
    (is (some? b))
    (is (= "best" (:experiment b)))))

(deftest best-run-min-direction
  (ml/with-run {:experiment "loss"}
    (ml/log-metric :loss 0.5))
  (ml/with-run {:experiment "loss"}
    (ml/log-metric :loss 0.1))
  (let [b (ml/best-run {:experiment "loss" :metric :loss :direction :min})]
    (is (some? b))))

;; --- Batch metric logging --------------------------------------------

(deftest with-batched-metrics-flushes
  (let [rid (atom nil)]
    (ml/with-run {:experiment "batch"}
      (reset! rid (:id (ctx/current-run)))
      (ml/with-batched-metrics
        (dotimes [i 50]
          (ml/log-metric :loss (/ 1.0 (inc i)) i))))
    (is (= 50 (count (:metrics (ml/run @rid)))))))

(deftest with-batched-metrics-flushes-on-exception
  (let [rid (atom nil)]
    (ml/with-run {:experiment "batch-err"}
      (reset! rid (:id (ctx/current-run)))
      (try
        (ml/with-batched-metrics
          (ml/log-metric :x 1.0)
          (throw (ex-info "boom" {})))
        (catch Exception _)))
    (is (= 1 (count (:metrics (ml/run @rid))))
        "Buffered metrics should be flushed even on exception")))

;; --- Table artifacts -------------------------------------------------

(deftest log-table-stores-with-content-type
  (let [rid (atom nil)]
    (ml/with-run {}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-table "confusion-matrix"
                    {:headers ["pred-0" "pred-1"]
                     :rows    [[50 5] [3 42]]}))
    (let [arts (ml/list-artifacts @rid)]
      (is (= 1 (count arts)))
      (is (= "application/x-chachaml-table" (:content-type (first arts)))))
    (let [table (ml/load-artifact @rid "confusion-matrix")]
      (is (= ["pred-0" "pred-1"] (:headers table)))
      (is (= [[50 5] [3 42]] (:rows table))))))

;; --- Experiment metadata ---------------------------------------------

(deftest create-and-get-experiment
  (ml/create-experiment! "iris" {:description "Iris classification"
                                 :owner "jiri"})
  (let [e (ml/experiment "iris")]
    (is (= "iris" (:name e)))
    (is (= "Iris classification" (:description e)))
    (is (= "jiri" (:owner e)))))

(deftest create-experiment-is-idempotent
  (ml/create-experiment! "x" {:description "first"})
  (ml/create-experiment! "x" {:description "updated"})
  (is (= "updated" (:description (ml/experiment "x")))))

(deftest list-experiments-returns-all
  (ml/create-experiment! "a" {})
  (ml/create-experiment! "b" {})
  (is (= 2 (count (ml/experiments)))))

;; --- Export ----------------------------------------------------------

(deftest export-runs-flat-maps
  (ml/with-run {:experiment "exp"}
    (ml/log-params {:lr 0.01})
    (ml/log-metric :accuracy 0.9))
  (let [rows (ml/export-runs)]
    (is (= 1 (count rows)))
    (is (= 0.01 (:lr (first rows))))
    (is (= 0.9 (:accuracy (first rows))))))

;; --- Model version diff ---------------------------------------------

(deftest diff-versions-compares-runs
  (ml/with-run {:experiment "v-diff"}
    (ml/log-params {:lr 0.01})
    (ml/log-metric :accuracy 0.9)
    (ml/log-artifact "model" {:w [1.0]})
    (reg/register-model "diff-model" {:artifact "model"}))
  (ml/with-run {:experiment "v-diff"}
    (ml/log-params {:lr 0.05})
    (ml/log-metric :accuracy 0.95)
    (ml/log-artifact "model" {:w [2.0]})
    (reg/register-model "diff-model" {:artifact "model"}))
  (let [diff (reg/diff-versions "diff-model" 1 2)]
    (is (some? diff))
    (is (= 2 (count (:runs diff))))
    (is (contains? (:differ (:params diff)) :lr))))
