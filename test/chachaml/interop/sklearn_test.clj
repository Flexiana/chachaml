(ns chachaml.interop.sklearn-test
  "Tests for the sklearn interop layer using a mock Python bridge.

  No Python or libpython-clj2 needed — the `*bridge*` dynamic var
  replaces all Python calls with pure Clojure mock implementations."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.interop.sklearn :as sk]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

;; --- Mock bridge -----------------------------------------------------

(def mock-model
  "A Clojure stand-in for a fitted sklearn model."
  {:type       :mock-classifier
   :fitted?    true
   :classes    [0 1 2]})

(def ^:private mock-bridge
  "Bridge map replacing all Python calls with pure Clojure mock logic."
  {:fit        (fn [_model _X _y] mock-model)
   :predict    (fn [_model _X] [0 1 2 1 0])
   :get-params (fn [_model] {:C 1.0 :max_iter 200 :solver "lbfgs"})
   :score      (fn [_model _X _y] 0.95)})

(defmacro ^:private with-mock
  "Bind the mock bridge for the body."
  [& body]
  `(binding [sk/*bridge* mock-bridge]
     ~@body))

;; --- tracked-fit! ----------------------------------------------------

(deftest tracked-fit-logs-time-and-params
  (with-mock
    (ml/with-run {:experiment "sklearn-test"}
      (let [fitted (sk/tracked-fit! :model :X :y)]
        (is (= mock-model fitted))
        (let [r (ml/run (:id (ctx/current-run)))]
          (is (some #(= :fit-time-ms (:key %)) (:metrics r))
              "fit-time-ms metric should be logged")
          (is (= {:C 1.0 :max_iter 200 :solver "lbfgs"}
                 (:params r))
              "Model params should be logged"))))))

(deftest tracked-fit-skips-params-when-told
  (with-mock
    (ml/with-run {}
      (sk/tracked-fit! :model :X :y :log-params? false)
      (is (= {} (:params (ml/run (:id (ctx/current-run)))))
          "Params should not be logged"))))

;; --- tracked-predict -------------------------------------------------

(deftest tracked-predict-logs-time
  (with-mock
    (ml/with-run {}
      (let [preds (sk/tracked-predict :model :X)]
        (is (= [0 1 2 1 0] preds))
        (is (some #(= :predict-time-ms (:key %))
                  (:metrics (ml/run (:id (ctx/current-run))))))))))

;; --- evaluate! -------------------------------------------------------

(deftest evaluate-classification
  (with-mock
    (ml/with-run {}
      (let [metrics (sk/evaluate! :model :X :y :task :classification)]
        (is (= 0.95 (:accuracy metrics)))
        (is (= 0.95 (:score metrics)))))))

(deftest evaluate-regression
  (with-mock
    (ml/with-run {}
      (let [metrics (sk/evaluate! :model :X :y :task :regression)]
        (is (= 0.95 (:r2 metrics)))))))

;; --- train-and-evaluate! --------------------------------------------

(deftest train-and-evaluate-full-pipeline
  (with-mock
    (ml/with-run {:experiment "pipeline"}
      (let [{:keys [model metrics predictions]}
            (sk/train-and-evaluate! :model :X-train :y-train :X-test :y-test
                                    :register-as "test-model"
                                    :stage :staging)]
        (is (= mock-model model))
        (is (= 0.95 (:accuracy metrics)))
        (is (= [0 1 2 1 0] predictions))
        (testing "Model artifact is persisted"
          (is (some? (ml/load-artifact (:id (ctx/current-run)) "model"))))
        (testing "Model is registered"
          (is (= 1 (count (reg/model-versions "test-model"))))
          (is (= :staging (:stage (first (reg/model-versions "test-model"))))))))))

(deftest train-and-evaluate-without-registration
  (with-mock
    (ml/with-run {}
      (sk/train-and-evaluate! :model :X :y :X :y)
      (is (= 0 (count (reg/models)))
          "No model registered when :register-as is nil"))))

;; --- extract-params --------------------------------------------------

(deftest extract-params-uses-bridge
  (with-mock
    (is (= {:C 1.0 :max_iter 200 :solver "lbfgs"}
           (sk/extract-params :model)))))
