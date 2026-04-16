(ns chachaml.core-test
  "Unit + integration tests for chachaml.core's public API.

  All tests bind a fresh in-memory store via `with-store` so they
  cannot interact with each other or the user's default DB."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.store.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]]))

(defn- with-fresh-store [f]
  (binding [ctx/*store* (sqlite/open {:in-memory? true})]
    (f)))

;; --- Run lifecycle ----------------------------------------------------

(deftest start-and-end-run
  (with-fresh-store
    (fn []
      (let [run (ml/start-run! {:experiment "t"})]
        (is (string? (:id run)))
        (is (= :running (:status run)))
        (ml/end-run! run :completed)
        (let [back (ml/run (:id run))]
          (is (= :completed (:status back)))
          (is (some? (:end-time back))))))))

(deftest start-run-no-args
  (with-fresh-store
    (fn []
      (let [run (ml/start-run!)]
        (is (= "default" (:experiment run)))
        (is (= :running (:status run)))))))

(deftest start-run-rejects-bad-opts
  (with-fresh-store
    (fn []
      (is (thrown? Exception (ml/start-run! {:experiment 42}))))))

(deftest with-run-binds-current-run-and-completes
  (with-fresh-store
    (fn []
      (let [captured (atom nil)
            result   (ml/with-run {:experiment "t"}
                       (reset! captured (ctx/current-run))
                       :body-result)]
        (is (= :body-result result))
        (is (= "t" (:experiment @captured)))
        (is (= :completed (:status (ml/run (:id @captured)))))))))

(deftest with-run-marks-failed-on-exception
  (with-fresh-store
    (fn []
      (let [captured (atom nil)]
        (try
          (ml/with-run {:experiment "t"}
            (reset! captured (ctx/current-run))
            (throw (ex-info "boom" {})))
          (catch Exception _))
        (let [back (ml/run (:id @captured))]
          (is (= :failed (:status back)))
          (is (= "boom" (:error back))))))))

(deftest nested-with-run-creates-child
  (with-fresh-store
    (fn []
      (let [parent-id (atom nil)
            child-id  (atom nil)]
        (ml/with-run {:experiment "outer"}
          (reset! parent-id (:id (ctx/current-run)))
          (ml/with-run {:experiment "inner"}
            (reset! child-id (:id (ctx/current-run)))))
        (let [child (ml/run @child-id)]
          (is (= @parent-id (:parent-run-id child))))))))

;; --- Logging ----------------------------------------------------------

(deftest log-params-and-metrics
  (with-fresh-store
    (fn []
      (ml/with-run {:experiment "t"}
        (ml/log-params {:lr 0.01 :epochs 10})
        (ml/log-metric :accuracy 0.94)
        (ml/log-metric :loss 0.3 5))
      (let [r (ml/run (:id (ml/last-run)))]
        (is (= {:lr 0.01 :epochs 10} (:params r)))
        (is (= 2 (count (:metrics r))))
        (is (= #{:accuracy :loss} (set (map :key (:metrics r)))))))))

(deftest log-outside-run-throws
  (with-fresh-store
    (fn []
      (is (thrown? Exception (ml/log-param :x 1)))
      (is (thrown? Exception (ml/log-metric :y 1))))))

(deftest log-empty-maps-is-noop
  (with-fresh-store
    (fn []
      (ml/with-run {}
        (is (nil? (ml/log-params {})))
        (is (nil? (ml/log-metrics {})))))))

(deftest log-metrics-rejects-non-numeric
  (with-fresh-store
    (fn []
      (ml/with-run {}
        (is (thrown? Exception (ml/log-metrics {:bad "string"})))))))

;; --- Querying ---------------------------------------------------------

(deftest runs-orders-newest-first
  (with-fresh-store
    (fn []
      (ml/with-run {:experiment "a"})
      (Thread/sleep 5) ; ensure distinct start_time
      (ml/with-run {:experiment "b"})
      (let [rs (ml/runs)]
        (is (= 2 (count rs)))
        (is (= "b" (:experiment (first rs))))))))

(deftest runs-filter-by-experiment
  (with-fresh-store
    (fn []
      (ml/with-run {:experiment "a"})
      (ml/with-run {:experiment "b"})
      (ml/with-run {:experiment "a"})
      (is (= 2 (count (ml/runs {:experiment "a"}))))
      (is (= 1 (count (ml/runs {:experiment "b"})))))))

(deftest run-returns-nil-for-unknown-id
  (with-fresh-store
    (fn []
      (is (nil? (ml/run "no-such"))))))

(deftest last-run-on-empty-store
  (with-fresh-store
    (fn []
      (is (nil? (ml/last-run))))))

;; --- Store binding ----------------------------------------------------

(deftest with-store-rebinds-locally
  (let [a (sqlite/open {:in-memory? true})
        b (sqlite/open {:in-memory? true})]
    (ml/with-store a (ml/with-run {:experiment "in-a"}))
    (ml/with-store b (ml/with-run {:experiment "in-b"}))
    (testing "Each store sees only its own runs"
      (is (= 1 (count (ml/with-store a (ml/runs)))))
      (is (= "in-a" (:experiment (first (ml/with-store a (ml/runs))))))
      (is (= "in-b" (:experiment (first (ml/with-store b (ml/runs)))))))))
