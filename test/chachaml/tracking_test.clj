(ns chachaml.tracking-test
  "Tests for the deftracked macro: shape, behavior, and nesting."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.test-helpers :as h]
            [chachaml.tracking :refer [deftracked]]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

;; --- Macro expansion shape -------------------------------------------

(deftest expansion-uses-with-run
  (let [expanded (macroexpand-1 '(chachaml.tracking/deftracked f [x] x))]
    (is (= 'clojure.core/defn (first expanded)))
    (is (= 'f (second expanded)))
    (is (= '[x] (nth expanded 2)))
    ;; Body is the wrapped (with-run …) form
    (let [body (last expanded)]
      (is (= 'chachaml.core/with-run (first body))))))

(deftest expansion-supports-docstring-and-opts
  (let [expanded (macroexpand-1
                  '(chachaml.tracking/deftracked f
                     "doc"
                     {:experiment "exp" :name "thing"}
                     [x] x))]
    (is (= 'clojure.core/defn (first expanded)))
    (is (= "doc" (nth expanded 2)))
    (is (= '[x] (nth expanded 3)))))

;; --- Behavior --------------------------------------------------------

(deftracked greet
  "Greets and returns the greeting."
  [who]
  (let [g (str "hello, " who)]
    (ml/log-params {:name who})
    (ml/log-metric :greeting-length (count g))
    g))

(deftracked exploder
  "Always throws."
  [_]
  (throw (ex-info "boom" {})))

(deftest tracked-fn-creates-completed-run-and-returns-value
  (let [result (greet "world")]
    (is (= "hello, world" result))
    (let [r (ml/last-run)]
      (is (= :completed (:status r)))
      (is (= "greet" (:name r)))
      (is (= "chachaml.tracking-test/greet" (:tracked-fn (:tags r))))
      (is (= {:name "world"}
             (:params (ml/run (:id r))))))))

(deftest tracked-fn-marks-failed-on-exception
  (try (exploder :ignored) (catch Exception _))
  (let [r (ml/last-run)]
    (is (= :failed (:status r)))
    (is (= "boom" (:error r)))))

(deftracked inner-call
  "Trivial nested-tracking helper."
  []
  :inner-result)

(deftracked outer
  "Calls a tracked inner fn so the inner fn nests under this run."
  []
  (inner-call))

(deftest tracked-fn-nests-under-existing-run
  (let [outer-id (atom nil)]
    (ml/with-run {:experiment "manual-outer"}
      (reset! outer-id (:id (ctx/current-run)))
      (outer))
    (let [runs (ml/runs)
          inner-run (first (filter #(= "inner-call" (:name %)) runs))
          inner-parent (:parent-run-id inner-run)
          outer-fn-run (first (filter #(= "outer" (:name %)) runs))]
      (is (= (:id outer-fn-run) inner-parent)
          "inner deftracked nests under the outer deftracked")
      (is (= @outer-id (:parent-run-id outer-fn-run))
          "outer deftracked nests under the user's manual with-run"))))

(deftracked tagged-fn
  "Tracked fn that overrides experiment, name, and tags."
  {:experiment "custom-exp" :name "renamed" :tags {:author "ci"}}
  []
  :ok)

(deftest tracked-fn-respects-opts
  (tagged-fn)
  (let [r (ml/last-run)]
    (is (= "custom-exp" (:experiment r)))
    (is (= "renamed"    (:name r)))
    (is (= "ci"         (:author (:tags r))))
    (testing "tracked-fn tag is auto-added even when :tags overrides exist"
      (is (= "chachaml.tracking-test/tagged-fn"
             (:tracked-fn (:tags r)))))))
