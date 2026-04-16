(ns chachaml.examples-test
  "Tests for the runnable ML examples under `examples/`. Each test
  invokes the example's `run-experiment!` against a fresh in-memory
  store, then asserts the persisted run looks correct.

  Tagged `^:integration` because they execute real (small) ML loops."
  (:require [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kmeans]
            [linear-regression :as lr]))

(use-fixtures :each h/with-fresh-store)

(deftest ^:integration linear-regression-converges
  (let [run-id (lr/run-experiment! {:n      80
                                    :epochs 200
                                    :lr     0.05
                                    :seed   123})
        run    (ml/run run-id)
        params (:params run)
        metric (fn [k] (some #(when (= k (:key %)) (:value %))
                             (:metrics run)))]
    (testing "Run is persisted with the expected metadata"
      (is (= "linear-regression" (:experiment run)))
      (is (= :completed (:status run)))
      (is (= 80 (:n params))))
    (testing "Loss curve was logged once per epoch (incl. epoch 0)"
      (let [loss-rows (filter #(= :loss (:key %)) (:metrics run))]
        (is (= 201 (count loss-rows)))
        (is (< (:value (last loss-rows))
               (:value (first loss-rows)))
            "Loss must decrease from start to end")))
    (testing "Recovered weights are within tolerance of true values"
      (is (< (metric :w-abs-error) 0.5))
      (is (< (metric :b-abs-error) 0.5)))
    (testing "Trained model is persisted as a loadable artifact"
      (let [model (ml/load-artifact run-id "model")]
        (is (= :linear-regression (:type model)))
        (is (number? (:w model)))
        (is (number? (:b model)))
        (is (= (metric :final-w) (:w model)))
        (is (= (metric :final-b) (:b model)))))
    (testing "Model is registered in the registry as a staging version"
      (let [versions (reg/model-versions "linear-regression-baseline")
            from-reg (reg/load-model "linear-regression-baseline"
                                     {:stage :staging})]
        (is (pos? (count versions)))
        (is (= :staging (:stage (last versions))))
        (is (= :linear-regression (:type from-reg)))))))

(deftest ^:integration kmeans-converges-and-clusters
  (let [run-id (kmeans/run-experiment! {:k             3
                                        :n-per-cluster 30
                                        :max-iter      30
                                        :seed          7})
        run    (ml/run run-id)
        metric (fn [k] (some #(when (= k (:key %)) (:value %))
                             (:metrics run)))]
    (testing "Run is persisted with expected metadata"
      (is (= "kmeans" (:experiment run)))
      (is (= :completed (:status run)))
      (is (= 90 (:n-points (:params run)))))
    (testing "Inertia curve was logged and is non-increasing"
      (let [values (->> (:metrics run)
                        (filter #(= :inertia (:key %)))
                        (mapv :value))]
        (is (pos? (count values)))
        (is (apply >= values)
            "Inertia must be non-increasing across iterations")
        (is (= (last values) (metric :final-inertia))
            "final-inertia metric must match the last iteration's value")))
    (testing "All three clusters have at least one member"
      (let [sizes (->> (:metrics run)
                       (filter #(re-find #"^:size-c" (str (:key %))))
                       (mapv :value))]
        (is (= 3 (count sizes)))
        (is (every? pos? sizes))))
    (testing "Trained model is persisted as a loadable artifact"
      (let [model (ml/load-artifact run-id "model")]
        (is (= :kmeans (:type model)))
        (is (= 3 (count (:centroids model))))
        (is (every? #(= 2 (count %)) (:centroids model)))
        (is (= (metric :final-inertia) (:inertia model)))))
    (testing "Model is registered in the registry as a staging version"
      (let [from-reg (reg/load-model "kmeans-baseline" {:stage :staging})]
        (is (= :kmeans (:type from-reg)))
        (is (= 3 (count (:centroids from-reg))))))))
