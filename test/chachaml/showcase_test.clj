(ns chachaml.showcase-test
  "Validates that the ML showcase produces meaningful results and that
  tracked data is correctly persisted.

  Runs a representative subset of use cases and checks:
  - ML metrics are in realistic ranges (not trivially 0 or 1)
  - Tracked data (params, metrics, artifacts, datasets, tags) is present
  - v0.4 features (log-table, with-batched-metrics, add-tag!, set-note!,
    create-experiment!, log-dataset!) were exercised"
  (:require [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [ml-showcase :as showcase]))

(use-fixtures :each h/with-fresh-store)

;; --- Helper ----------------------------------------------------------

(defn- metric-val
  "Extract the latest value for a metric key from a run's metrics."
  [run mk]
  (some (fn [{k :key v :value}]
          (when (= k mk) v))
        (:metrics run)))

;; --- Classification: reasonable accuracy -----------------------------

(deftest ^:integration uc01-produces-realistic-results
  (let [run-id (showcase/uc01-binary-classification)
        run    (ml/run run-id)]
    (testing "Run is completed with params and metrics"
      (is (= :completed (:status run)))
      (is (contains? (:params run) :algorithm))
      (is (pos? (count (:metrics run)))))
    (testing "Accuracy is in a realistic range"
      (let [acc (metric-val run :accuracy)]
        (is (some? acc))
        (is (>= acc 0.5) "Should be better than random")
        (is (<= acc 1.0))))
    (testing "Loss curve shows improvement"
      (let [losses (->> (:metrics run)
                        (filter #(= :loss (:key %)))
                        (sort-by :step))]
        (is (> (count losses) 10) "Should have many loss steps")
        (is (< (:value (last losses)) (:value (first losses)))
            "Loss should decrease over training")))
    (testing "v0.4: confusion-matrix stored as table artifact"
      (let [arts (:artifacts run)
            table-art (first (filter #(= "application/x-chachaml-table"
                                         (:content-type %)) arts))]
        (is (some? table-art) "Should have a table artifact")
        (is (= "confusion-matrix" (:name table-art)))))
    (testing "v0.4: datasets logged"
      (let [ds (:datasets run)]
        (is (= 2 (count ds)) "Should have train + test datasets")
        (is (some #(= "train" (:role %)) ds))
        (is (some #(= "test" (:role %)) ds))))
    (testing "Model registered"
      (let [versions (reg/model-versions "binary-classifier")]
        (is (pos? (count versions)))))))

;; --- Regression: meaningful R² ---------------------------------------

(deftest ^:integration uc08-linear-regression-converges
  (let [run-id (showcase/uc08-linear-regression)
        run    (ml/run run-id)]
    (testing "R² is high for linear data"
      (let [r2 (metric-val run :r2)]
        (is (some? r2))
        (is (> r2 0.8) "Linear regression on linear data should have R² > 0.8")))
    (testing "v0.4: batched metrics were flushed"
      (let [mse-rows (->> (:metrics run)
                          (filter #(= :train-mse (:key %))))]
        (is (pos? (count mse-rows)) "Batched metrics should be present")))))

;; --- Cross-validation: child runs + experiment metadata --------------

(deftest ^:integration uc17-creates-folds-and-experiment
  (let [run-id (showcase/uc17-cross-validation)
        run    (ml/run run-id)]
    (testing "Aggregated metrics present"
      (is (some? (metric-val run :mean-accuracy))))
    (testing "v0.4: experiment metadata created"
      (let [exp (ml/experiment "17-cross-validation")]
        (is (some? exp))
        (is (= "showcase" (:owner exp)))))
    (testing "Child runs created for each fold"
      (let [fold-runs (ml/runs {:experiment "17-cross-validation"
                                :limit 10})]
        (is (>= (count fold-runs) 5) "Should have at least 5 fold runs")))))

;; --- A/B evaluation: meaningful test + note --------------------------

(deftest ^:integration uc25-ab-test-produces-comparison
  (let [run-id (showcase/uc25-ab-evaluation)
        run    (ml/run run-id)]
    (testing "Both model accuracies are in range"
      (let [acc-a (metric-val run :accuracy-a)
            acc-b (metric-val run :accuracy-b)]
        (is (> acc-a 0.5))
        (is (> acc-b 0.3) "Even undertrained model should beat random")))
    (testing "Statistical test metrics present"
      (is (some? (metric-val run :t-statistic)))
      (is (some? (metric-val run :p-value))))
    (testing "v0.4: markdown note saved"
      (let [tags (ml/get-tags run-id)]
        (is (some? (:note tags)) "Note should be set")
        (is (re-find #"A/B Evaluation" (:note tags)))
        (is (re-find #"\$" (:note tags)) "Note should contain LaTeX math")))))

;; --- Model comparison: tags -----------------------------------------

(deftest ^:integration uc19-tags-winner
  (let [run-id (showcase/uc19-model-comparison)]
    (testing "v0.4: winner tag set after run"
      (let [tags (ml/get-tags run-id)]
        (is (some? (:winner tags)))))))

;; --- DB completeness: every run has params + metrics -----------------

(deftest ^:integration all-runs-have-tracked-data
  ;; Run a few diverse use cases
  (showcase/uc05-knn)
  (showcase/uc13-kmeans)
  (showcase/uc22-time-series)
  (let [all-runs (ml/runs {:limit 100})]
    (testing "Every run has at least one metric"
      (doseq [r all-runs]
        (let [full (ml/run (:id r))]
          (is (pos? (count (:metrics full)))
              (str "Run " (:id r) " (" (:experiment r)
                   ") should have metrics")))))))
