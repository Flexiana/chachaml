(ns chachaml.integration-test
  "End-to-end test mirroring a typical REPL session against a tempfile
  SQLite store. Tagged `^:integration`."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.store.sqlite :as sqlite]
            [clojure.test :refer [deftest is testing]])
  (:import [java.io File]))

(deftest ^:integration repl-session-roundtrip
  (testing "A full session: open store, run, log, query, close"
    (let [tmp      (File/createTempFile "chachaml-int-" ".db")
          tmp-path (.getAbsolutePath tmp)]
      (try
        (binding [ctx/*store* (sqlite/open {:path tmp-path})]
          (ml/with-run {:experiment "integration"
                        :name       "first"
                        :tags       {:author "ci"}}
            (ml/log-params {:lr 0.01 :epochs 5})
            (doseq [step (range 5)]
              (ml/log-metric :loss (- 1.0 (* 0.1 step)) step))
            (ml/log-metric :final-acc 0.93))
          (let [latest   (ml/last-run)
                detailed (ml/run (:id latest))]
            (is (= "integration" (:experiment latest)))
            (is (= "first" (:name latest)))
            (is (= :completed (:status latest)))
            (is (= {:author "ci"} (:tags latest)))
            (is (= {:lr 0.01 :epochs 5} (:params detailed)))
            (is (= 6 (count (:metrics detailed))))
            (is (= 5 (count (filter #(= :loss (:key %))
                                    (:metrics detailed)))))))
        (finally
          (.delete tmp))))))
