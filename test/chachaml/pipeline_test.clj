(ns chachaml.pipeline-test
  "Tests for pipeline tracking and alerts."
  (:require [chachaml.alerts :as alerts]
            [chachaml.core :as ml]
            [chachaml.pipeline :as pipe]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

;; --- Pipeline tracking -----------------------------------------------

(deftest run-pipeline-creates-steps
  (let [result (pipe/run-pipeline!
                "test-pipe"
                [{:name "step-1" :fn (fn [_] (ml/log-params {:x 1}) :one)}
                 {:name "step-2" :fn (fn [ctx]
                                       (ml/log-params {:prev (str (:prev-result ctx))})
                                       :two)}])]
    (is (= "completed" (:status result)))
    (is (= 2 (count (:steps result))))
    (is (= :two (:result result)))
    (is (every? #(= "completed" (:status %)) (:steps result)))
    (is (every? #(some? (:run-id %)) (:steps result)))))

(deftest pipeline-step-creates-tracked-run
  (pipe/run-pipeline!
   "tracked"
   [{:name "only-step" :fn (fn [_] (ml/log-metric :acc 0.9))}])
  (let [runs (ml/runs {:experiment "tracked"})]
    (is (= 1 (count runs)))
    (is (= "only-step" (:name (first runs))))))

(deftest pipeline-chains-results
  (let [result (pipe/run-pipeline!
                "chain"
                [{:name "a" :fn (fn [_] 10)}
                 {:name "b" :fn (fn [ctx] (* 2 (:prev-result ctx)))}
                 {:name "c" :fn (fn [ctx] (+ 1 (:prev-result ctx)))}])]
    (is (= 21 (:result result)))))

(deftest pipeline-fails-on-step-error
  (is (thrown? Exception
               (pipe/run-pipeline!
                "fail"
                [{:name "ok"   :fn (fn [_] :fine)}
                 {:name "boom" :fn (fn [_] (throw (ex-info "step failed" {})))}]))))

(deftest pipeline-query
  (pipe/run-pipeline!
   "query-test"
   [{:name "s" :fn (fn [_] nil)}])
  (let [all (pipe/pipelines)]
    (is (pos? (count all)))
    (is (= "query-test" (:name (first all))))))

(deftest pipeline-detail-includes-steps
  (let [result (pipe/run-pipeline!
                "detail"
                [{:name "a" :fn (fn [_] 1)}
                 {:name "b" :fn (fn [_] 2)}])
        detail (pipe/pipeline (:pipeline-id result))]
    (is (= "detail" (:name detail)))
    (is (= 2 (count (:steps detail))))))

;; --- Alerts ----------------------------------------------------------

(deftest set-and-list-alerts
  (alerts/set-alert! "low-acc"
                     {:metric-key :accuracy :op :< :threshold 0.8
                      :experiment "test"})
  (let [all (alerts/alerts)]
    (is (= 1 (count all)))
    (is (= "low-acc" (:name (first all))))))

(deftest check-alerts-triggers-on-match
  (alerts/set-alert! "acc-alert"
                     {:metric-key :accuracy :op :< :threshold 0.95
                      :experiment "alert-test"})
  (ml/with-run {:experiment "alert-test"}
    (ml/log-metric :accuracy 0.80))
  (let [events (alerts/check-alerts!)]
    (is (pos? (count events)))
    (is (= "acc-alert" (:alert-name (first events))))
    (is (= 0.80 (:metric-value (first events))))))

(deftest check-alerts-does-not-trigger-when-ok
  (alerts/set-alert! "fine"
                     {:metric-key :accuracy :op :< :threshold 0.5})
  (ml/with-run {:experiment "fine-test"}
    (ml/log-metric :accuracy 0.95))
  (let [events (alerts/check-alerts!)]
    (is (empty? events))))

(deftest alert-history-records-events
  (alerts/set-alert! "hist"
                     {:metric-key :loss :op :> :threshold 0.5})
  (ml/with-run {:experiment "hist-test"}
    (ml/log-metric :loss 0.8))
  (alerts/check-alerts!)
  (let [history (alerts/alert-history "hist")]
    (is (= 1 (count history)))))

(deftest deactivate-alert
  (alerts/set-alert! "temp" {:metric-key :x :op :> :threshold 0})
  (alerts/deactivate-alert! "temp")
  (is (empty? (alerts/alerts))))
