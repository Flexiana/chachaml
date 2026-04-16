(ns chachaml.core-test
  "Unit + integration tests for chachaml.core's public API.

  An `:each` fixture binds a fresh in-memory store so tests cannot
  interact with each other or the user's default DB."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.store.sqlite :as sqlite]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each h/with-fresh-store)

;; --- Run lifecycle ----------------------------------------------------

(deftest start-and-end-run
  (let [run (ml/start-run! {:experiment "t"})]
    (is (string? (:id run)))
    (is (= :running (:status run)))
    (ml/end-run! run :completed)
    (let [back (ml/run (:id run))]
      (is (= :completed (:status back)))
      (is (some? (:end-time back))))))

(deftest start-run-no-args
  (let [run (ml/start-run!)]
    (is (= "default" (:experiment run)))
    (is (= :running (:status run)))))

(deftest start-run-rejects-bad-opts
  (is (thrown? Exception (ml/start-run! {:experiment 42}))))

(deftest with-run-binds-current-run-and-completes
  (let [captured (atom nil)
        result   (ml/with-run {:experiment "t"}
                   (reset! captured (ctx/current-run))
                   :body-result)]
    (is (= :body-result result))
    (is (= "t" (:experiment @captured)))
    (is (= :completed (:status (ml/run (:id @captured)))))))

(deftest with-run-marks-failed-on-exception
  (let [captured (atom nil)]
    (try
      (ml/with-run {:experiment "t"}
        (reset! captured (ctx/current-run))
        (throw (ex-info "boom" {})))
      (catch Exception _))
    (let [back (ml/run (:id @captured))]
      (is (= :failed (:status back)))
      (is (= "boom" (:error back))))))

(deftest nested-with-run-creates-child
  (let [parent-id (atom nil)
        child-id  (atom nil)]
    (ml/with-run {:experiment "outer"}
      (reset! parent-id (:id (ctx/current-run)))
      (ml/with-run {:experiment "inner"}
        (reset! child-id (:id (ctx/current-run)))))
    (let [child (ml/run @child-id)]
      (is (= @parent-id (:parent-run-id child))))))

;; --- Logging ----------------------------------------------------------

(deftest log-params-and-metrics
  (ml/with-run {:experiment "t"}
    (ml/log-params {:lr 0.01 :epochs 10})
    (ml/log-metric :accuracy 0.94)
    (ml/log-metric :loss 0.3 5))
  (let [r (ml/run (:id (ml/last-run)))]
    (is (= {:lr 0.01 :epochs 10} (:params r)))
    (is (= 2 (count (:metrics r))))
    (is (= #{:accuracy :loss} (set (map :key (:metrics r)))))))

(deftest log-outside-run-throws
  (is (thrown? Exception (ml/log-param :x 1)))
  (is (thrown? Exception (ml/log-metric :y 1))))

(deftest log-empty-maps-is-noop
  (ml/with-run {}
    (is (nil? (ml/log-params {})))
    (is (nil? (ml/log-metrics {})))))

(deftest log-metrics-rejects-non-numeric
  (ml/with-run {}
    (is (thrown? Exception (ml/log-metrics {:bad "string"})))))

;; --- Artifacts --------------------------------------------------------

(deftest log-and-load-nippy-artifact
  (let [model {:weights [1.0 2.0 3.0] :bias 0.5 :name "linreg"}
        run-id (atom nil)]
    (ml/with-run {}
      (reset! run-id (:id (ctx/current-run)))
      (let [art (ml/log-artifact "model" model)]
        (is (= "model" (:name art)))
        (is (= "application/x-nippy" (:content-type art)))
        (is (pos? (:size art)))
        (is (= 64 (count (:hash art))))))
    (is (= model (ml/load-artifact @run-id "model")))))

(deftest log-and-load-edn-artifact
  (let [run-id (atom nil)]
    (ml/with-run {}
      (reset! run-id (:id (ctx/current-run)))
      (ml/log-artifact "report" {:summary "ok"} {:format :edn}))
    (is (= {:summary "ok"} (ml/load-artifact @run-id "report")))))

(deftest log-file-artifact
  (let [tmp (java.io.File/createTempFile "chachaml-test-" ".bin")
        run-id (atom nil)]
    (try
      (spit tmp "file contents")
      (ml/with-run {}
        (reset! run-id (:id (ctx/current-run)))
        (ml/log-file "uploaded.bin" tmp))
      (is (= "file contents"
             (String. ^bytes (ml/load-artifact @run-id "uploaded.bin"))))
      (finally (.delete tmp)))))

(deftest list-and-run-include-artifacts
  (let [run-id (atom nil)]
    (ml/with-run {}
      (reset! run-id (:id (ctx/current-run)))
      (ml/log-artifact "a" {:x 1})
      (ml/log-artifact "b" {:y 2}))
    (is (= 2 (count (ml/list-artifacts @run-id))))
    (is (= 2 (count (:artifacts (ml/run @run-id)))))))

(deftest load-artifact-missing-returns-nil
  (let [run-id (atom nil)]
    (ml/with-run {}
      (reset! run-id (:id (ctx/current-run))))
    (is (nil? (ml/load-artifact @run-id "no-such-art")))))

(deftest log-artifact-outside-run-throws
  (is (thrown? Exception (ml/log-artifact "x" {:a 1}))))

;; --- Querying ---------------------------------------------------------

(deftest runs-orders-newest-first
  (ml/with-run {:experiment "a"})
  (Thread/sleep 5) ; ensure distinct start_time
  (ml/with-run {:experiment "b"})
  (let [rs (ml/runs)]
    (is (= 2 (count rs)))
    (is (= "b" (:experiment (first rs))))))

(deftest runs-filter-by-experiment
  (ml/with-run {:experiment "a"})
  (ml/with-run {:experiment "b"})
  (ml/with-run {:experiment "a"})
  (is (= 2 (count (ml/runs {:experiment "a"}))))
  (is (= 1 (count (ml/runs {:experiment "b"})))))

(deftest run-returns-nil-for-unknown-id
  (is (nil? (ml/run "no-such"))))

(deftest last-run-on-empty-store
  (is (nil? (ml/last-run))))

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

(deftest use-store!-returns-resolved-store
  ;; The fixture's `binding` masks `current-store`, so we can only
  ;; assert the return-value contract here. Effective behavior of the
  ;; root binding is exercised indirectly by every default-store path.
  (let [s (sqlite/open {:in-memory? true})]
    (try
      (is (identical? s (ml/use-store! s))
          "Passing a store value returns it as-is")
      (finally
        (alter-var-root #'ctx/*store* (constantly nil))))))
