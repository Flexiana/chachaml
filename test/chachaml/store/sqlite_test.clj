(ns chachaml.store.sqlite-test
  "Unit tests for the SQLite RunStore implementation.

  Tagged `^:integration` tests use a real tempfile DB; the rest use
  the `:in-memory? true` mode which is faster and isolated per test."
  (:require [chachaml.store.protocol :as p]
            [chachaml.store.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

(defn- mem-store [] (sqlite/open {:in-memory? true}))

(defn- new-run [overrides]
  (merge {:id         (str (random-uuid))
          :experiment "test"
          :status     :running
          :start-time (System/currentTimeMillis)}
         overrides))

;; --- Run lifecycle ----------------------------------------------------

(deftest create-and-read-run
  (let [store (mem-store)
        run   (new-run {:name "first" :tags {:author "a"}})]
    (p/-create-run! store run)
    (let [back (p/-get-run store (:id run))]
      (is (= (:id run) (:id back)))
      (is (= "first" (:name back)))
      (is (= :running (:status back)))
      (is (= "test" (:experiment back)))
      (is (= {:author "a"} (:tags back))))))

(deftest get-run-missing-returns-nil
  (is (nil? (p/-get-run (mem-store) "no-such-id"))))

(deftest update-run-status-and-end-time
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-update-run! store (:id run) {:status :completed :end-time 42})
    (let [back (p/-get-run store (:id run))]
      (is (= :completed (:status back)))
      (is (= 42 (:end-time back))))))

(deftest update-with-empty-map-is-noop
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (is (nil? (p/-update-run! store (:id run) {})))))

(deftest update-ignores-unknown-keys
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-update-run! store (:id run) {:bogus "x" :status :completed})
    (is (= :completed (:status (p/-get-run store (:id run)))))))

;; --- Params -----------------------------------------------------------

(deftest log-and-read-params
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-log-params! store (:id run) {:lr 0.01 :epochs 10 :name "exp1"})
    (is (= {:lr 0.01 :epochs 10 :name "exp1"}
           (p/-get-params store (:id run))))))

(deftest empty-params-is-noop
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (is (nil? (p/-log-params! store (:id run) {})))
    (is (= {} (p/-get-params store (:id run))))))

(deftest params-preserve-rich-edn-values
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-log-params! store (:id run)
                    {:vec [1 2 3]
                     :map {:nested {:k :v}}
                     :kw  :foo/bar})
    (let [params (p/-get-params store (:id run))]
      (is (= [1 2 3]            (:vec params)))
      (is (= {:nested {:k :v}}  (:map params)))
      (is (= :foo/bar           (:kw params))))))

(deftest re-logging-same-param-throws
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-log-params! store (:id run) {:lr 0.01})
    (is (thrown? Exception
                 (p/-log-params! store (:id run) {:lr 0.02})))))

;; --- Metrics ----------------------------------------------------------

(deftest log-and-read-metrics
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-log-metrics! store (:id run)
                     [{:key :loss :value 0.5 :step 0}
                      {:key :loss :value 0.3 :step 1}
                      {:key :acc  :value 0.9 :step 0}])
    (let [rows (p/-get-metrics store (:id run))]
      (is (= 3 (count rows)))
      (is (= [:acc :loss :loss] (map :key rows)))
      (is (every? #(int? (:timestamp %)) rows)))))

(deftest metrics-default-step-is-zero
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-log-metrics! store (:id run) [{:key :acc :value 0.9}])
    (is (= 0 (:step (first (p/-get-metrics store (:id run))))))))

(deftest empty-metrics-is-noop
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (is (nil? (p/-log-metrics! store (:id run) [])))
    (is (= [] (p/-get-metrics store (:id run))))))

;; --- Querying ---------------------------------------------------------

(deftest query-empty-store
  (is (= [] (p/-query-runs (mem-store) {}))))

(deftest query-orders-most-recent-first
  (let [store  (mem-store)
        older  (new-run {:start-time 100})
        newer  (new-run {:start-time 200})]
    (p/-create-run! store older)
    (p/-create-run! store newer)
    (let [ids (mapv :id (p/-query-runs store {}))]
      (is (= [(:id newer) (:id older)] ids)))))

(deftest query-filters-by-experiment-and-status
  (let [store (mem-store)
        a     (new-run {:experiment "a"})
        b     (new-run {:experiment "b"})
        a-old (new-run {:experiment "a" :status :failed})]
    (run! #(p/-create-run! store %) [a b a-old])
    (is (= 2 (count (p/-query-runs store {:experiment "a"}))))
    (is (= 1 (count (p/-query-runs store {:experiment "a" :status :failed}))))))

(deftest query-respects-limit
  (let [store (mem-store)]
    (dotimes [_ 5] (p/-create-run! store (new-run {})))
    (is (= 2 (count (p/-query-runs store {:limit 2}))))))

;; --- Lifecycle / file-backed ------------------------------------------

;; --- Artifacts --------------------------------------------------------

(defn- bytes-of [^String s]
  (.getBytes s "UTF-8"))

(deftest put-and-list-artifacts
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (let [a1 (p/-put-artifact! store (:id run) "model.bin"
                               (bytes-of "abc") "application/octet-stream")
          a2 (p/-put-artifact! store (:id run) "report.txt"
                               (bytes-of "hello") "text/plain")]
      (is (string? (:id a1)))
      (is (= 3 (:size a1)))
      (is (= 5 (:size a2)))
      (is (string? (:hash a1)))
      (is (= 64 (count (:hash a1))) "SHA-256 hex is 64 chars")
      (is (= 2 (count (p/-list-artifacts store (:id run))))))))

(deftest get-and-find-artifact
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (let [art (p/-put-artifact! store (:id run) "blob"
                                (bytes-of "data") "application/octet-stream")]
      (is (= "data" (String. ^bytes (p/-get-artifact-bytes store (:id art)))))
      (is (= (:id art) (:id (p/-find-artifact store (:id run) "blob"))))
      (is (nil? (p/-find-artifact store (:id run) "missing"))))))

(deftest duplicate-artifact-name-rejected
  (let [store (mem-store)
        run   (new-run {})]
    (p/-create-run! store run)
    (p/-put-artifact! store (:id run) "x" (bytes-of "a") "")
    (is (thrown? Exception
                 (p/-put-artifact! store (:id run) "x"
                                   (bytes-of "b") "")))))

(deftest ^:integration artifacts-persist-across-opens
  (let [tmp (File/createTempFile "chachaml-art-" ".db")
        tmp-path (.getAbsolutePath tmp)
        run (new-run {})]
    (try
      (let [s1 (sqlite/open {:path tmp-path})]
        (p/-create-run! s1 run)
        (p/-put-artifact! s1 (:id run) "model.bin"
                          (bytes-of "trained") "application/octet-stream")
        (p/close! s1))
      (let [s2 (sqlite/open {:path tmp-path})
            art (p/-find-artifact s2 (:id run) "model.bin")]
        (is (= "trained"
               (String. ^bytes (p/-get-artifact-bytes s2 (:id art)))))
        (p/close! s2))
      (finally
        (.delete tmp)
        (#'sqlite/delete-tree! (java.io.File. (#'sqlite/default-artifact-dir tmp-path)))))))

(deftest ^:integration tempfile-backed-store-roundtrip
  (testing "Schema persists across opens; data round-trips through a real file"
    (let [tmp (File/createTempFile "chachaml-" ".db")
          tmp-path (.getAbsolutePath tmp)
          run (new-run {:name "persisted"})]
      (try
        (let [s1 (sqlite/open {:path tmp-path})]
          (p/-create-run! s1 run)
          (p/-log-params! s1 (:id run) {:lr 0.01})
          (p/close! s1))
        ;; Re-opening must not re-create (idempotent migrate!) and must
        ;; see the previously written data.
        (let [s2 (sqlite/open {:path tmp-path})]
          (is (= "persisted" (:name (p/-get-run s2 (:id run)))))
          (is (= {:lr 0.01} (p/-get-params s2 (:id run))))
          (p/close! s2))
        (finally
          (.delete tmp))))))
