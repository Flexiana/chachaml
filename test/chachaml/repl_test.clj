(ns chachaml.repl-test
  "Tests for the REPL helpers. Output goes to *out*; we capture with
  `with-out-str` and assert on substrings. compare-runs returns a
  plain map we can introspect directly."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.format :as fmt]
            [chachaml.repl :as repl]
            [chachaml.test-helpers :as h]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

(defn- seed-runs!
  "Create n runs, each with shared and varying params/metrics. Returns
  the seq of run ids in creation order."
  [n]
  (vec
   (for [i (range n)]
     (let [rid (atom nil)]
       (ml/with-run {:experiment "demo" :name (str "run-" i)}
         (reset! rid (:id (ctx/current-run)))
         (ml/log-params {:lr 0.01 :seed i})
         (ml/log-metric :acc (+ 0.8 (* 0.05 i)))
         (ml/log-metric :loss 0.5 0)
         (ml/log-metric :loss (- 0.5 (* 0.1 i)) 1))
       @rid))))

;; --- runs-table ------------------------------------------------------

(deftest runs-table-empty-store
  (let [out (with-out-str (repl/runs-table))]
    (is (str/includes? out "(no runs)"))))

(deftest runs-table-renders-header-and-rows
  (seed-runs! 2)
  (let [out (with-out-str (repl/runs-table))]
    (is (str/includes? out "EXPERIMENT"))
    (is (str/includes? out "demo"))
    (is (str/includes? out "completed"))))

;; --- inspect ---------------------------------------------------------

(deftest inspect-run-prints-summary
  (let [[rid] (seed-runs! 1)
        out   (with-out-str (repl/inspect rid))]
    (is (str/includes? out "Run "))
    (is (str/includes? out "demo"))
    (is (str/includes? out "Params"))
    (is (str/includes? out ":lr"))
    (is (str/includes? out "Metrics"))
    (is (str/includes? out ":loss"))
    (is (str/includes? out "2 points"))))

(deftest inspect-run-with-tags-and-artifacts
  (let [rid (atom nil)]
    (ml/with-run {:experiment "tagged"
                  :name       "labelled"
                  :tags       {:author "ci" :version "1.0"}}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-artifact "model"  {:weights [1.0]})
      (ml/log-artifact "report" "hello" {:format :edn}))
    (let [out (with-out-str (repl/inspect @rid))]
      (is (str/includes? out "Tags:"))
      (is (str/includes? out "author"))
      (is (str/includes? out "Artifacts (2):"))
      (is (str/includes? out "model"))
      (is (str/includes? out "report"))
      (is (str/includes? out "application/x-nippy"))
      (is (str/includes? out "application/edn")))))

(deftest inspect-run-with-failure
  (let [rid (atom nil)]
    (try
      (ml/with-run {:experiment "explosive"}
        (reset! rid (:id (ctx/current-run)))
        (throw (ex-info "kaboom" {})))
      (catch Exception _))
    (let [out (with-out-str (repl/inspect @rid))]
      (is (str/includes? out "Status:"))
      (is (str/includes? out ":failed"))
      (is (str/includes? out "Error:"))
      (is (str/includes? out "kaboom")))))

(deftest inspect-run-passed-as-map
  (let [[rid] (seed-runs! 1)
        r    (ml/run rid)
        out  (with-out-str (repl/inspect r))]
    (is (str/includes? out "Run "))))

(deftest inspect-nil-prints-placeholder
  (let [out (with-out-str (repl/inspect nil))]
    (is (str/includes? out "(nil)"))))

(deftest inspect-unknown-entity
  (let [out (with-out-str (repl/inspect 42))]
    (is (str/includes? out "(unknown entity)"))))

(deftest inspect-string-falls-back-to-model
  (ml/with-run {:experiment "x"}
    (ml/log-artifact "m" {:weights [1]})
    (reg/register-model "demo-model" {:artifact "m" :stage :staging}))
  (let [out (with-out-str (repl/inspect "demo-model"))]
    (is (str/includes? out "Model: demo-model"))
    (is (str/includes? out "v1"))))

(deftest inspect-unknown-id-prints-message
  (let [out (with-out-str (repl/inspect "no-such-id"))]
    (is (str/includes? out "no run or model"))))

(deftest inspect-version-map
  (ml/with-run {}
    (ml/log-artifact "m" {:x 1})
    (let [v (reg/register-model "m" {:artifact "m" :stage :staging})
          out (with-out-str (repl/inspect v))]
      (is (str/includes? out "v1"))
      (is (str/includes? out "Stage:"))
      (is (str/includes? out "staging")))))

;; --- compare-runs ----------------------------------------------------

(deftest compare-runs-categorises-keys
  (let [ids (seed-runs! 3)
        cmp (repl/compare-runs ids)]
    (is (= 3 (count (:runs cmp))))
    (is (= {:lr 0.01} (-> cmp :params :same)))
    (is (= [0 1 2] (-> cmp :params :differ :seed)))
    (is (= 3 (count (-> cmp :metrics :differ :acc))))))

(deftest compare-runs-detects-partial
  (let [ids (atom [])]
    (ml/with-run {}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01 :only-here 42}))
    (ml/with-run {}
      (swap! ids conj (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01}))
    (let [cmp (repl/compare-runs @ids)]
      (is (contains? (-> cmp :params :partial) :only-here)))))

(deftest compare-runs-empty-input
  (let [cmp (repl/compare-runs [])]
    (is (= [] (:runs cmp)))
    (testing "no keys → empty :same/:differ/:partial maps"
      (is (= {} (-> cmp :params :same)))
      (is (= {} (-> cmp :metrics :differ))))))

(deftest print-comparison-renders-sections
  (let [ids (seed-runs! 2)
        cmp (repl/compare-runs ids)
        out (with-out-str (repl/print-comparison cmp))]
    (is (str/includes? out "Comparing 2 runs"))
    (is (str/includes? out "Params"))
    (is (str/includes? out "differ"))
    (is (str/includes? out ":seed"))
    (is (str/includes? out "identical"))))

(deftest print-comparison-with-partial-keys
  (let [ids (atom [])]
    (ml/with-run {} (swap! ids conj (:id (ctx/current-run)))
                 (ml/log-params {:lr 0.01 :only-here 42}))
    (ml/with-run {} (swap! ids conj (:id (ctx/current-run)))
                 (ml/log-params {:lr 0.01}))
    (let [cmp (repl/compare-runs @ids)
          out (with-out-str (repl/print-comparison cmp))]
      (is (str/includes? out "partial"))
      (is (str/includes? out ":only-here")))))

;; --- Private formatter coverage --------------------------------------

(deftest size-str-renders-units
  (is (= "0 B" (fmt/size-str 0)))
  (is (= "512 B" (fmt/size-str 512)))
  (is (str/ends-with? (fmt/size-str 2048) "KiB"))
  (is (str/ends-with? (fmt/size-str (* 5 1024 1024)) "MiB"))
  (is (str/ends-with? (fmt/size-str (* 3 1024 1024 1024)) "GiB")))

(deftest fmt-duration-units
  (is (str/ends-with? (fmt/fmt-duration 0 500)         "ms"))
  (is (str/ends-with? (fmt/fmt-duration 0 5000)        "s"))
  (is (str/includes?  (fmt/fmt-duration 0 (* 90 1000)) "m ")))

(deftest short-id-handles-edge-cases
  (is (nil? (fmt/short-id nil)))
  (is (= "abc"      (fmt/short-id "abc")))
  (is (= "abcdefgh" (fmt/short-id "abcdefghij"))))
