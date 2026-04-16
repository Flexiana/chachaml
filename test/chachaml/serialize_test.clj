(ns chachaml.serialize-test
  "Unit + property-based tests for the artifact codec."
  (:require [chachaml.serialize :as s]
            [clojure.test :refer [deftest is testing]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop])
  (:import [java.io File]))

;; --- Auto format detection -------------------------------------------

(deftest auto-format-defaults
  (is (= :bytes (s/auto-format (byte-array 0))))
  (is (= :file  (s/auto-format (File. "/tmp"))))
  (is (= :nippy (s/auto-format {:a 1})))
  (is (= :nippy (s/auto-format [1 2 3])))
  (is (= :nippy (s/auto-format "a string is data, not a path"))))

;; --- Round-trip across formats ---------------------------------------

(deftest nippy-round-trip
  (let [v {:vec [1 2 3] :map {:nested true} :kw :ns/foo :str "hi"}
        {data :bytes ct :content-type} (s/encode {:format :nippy :value v})]
    (is (= "application/x-nippy" ct))
    (is (= v (s/decode {:format :nippy :bytes data})))))

(deftest edn-round-trip
  (let [v {:foo "bar" :nums [1 2 3]}
        {data :bytes ct :content-type} (s/encode {:format :edn :value v})]
    (is (= "application/edn" ct))
    (is (= v (s/decode {:format :edn :bytes data})))))

(deftest bytes-round-trip
  (let [v (byte-array [(byte 1) (byte 2) (byte 3)])
        {data :bytes ct :content-type} (s/encode {:format :bytes :value v})]
    (is (= "application/octet-stream" ct))
    (is (= (seq v) (seq (s/decode {:format :bytes :bytes data}))))))

(deftest bytes-rejects-non-bytes
  (is (thrown? Exception (s/encode {:format :bytes :value "not bytes"}))))

(deftest file-round-trip
  (let [tmp (File/createTempFile "chachaml-codec-" ".bin")]
    (try
      (spit tmp "hello world")
      (let [{data :bytes ct :content-type}
            (s/encode {:format :file :value tmp})]
        (is (= "application/octet-stream" ct))
        (is (= "hello world"
               (String. ^bytes (s/decode {:format :file :bytes data})))))
      (finally (.delete tmp)))))

(deftest file-missing-throws
  (is (thrown? Exception (s/encode {:format :file
                                    :value  "/no/such/file.bin"}))))

(deftest content-type-resolves-to-format
  (is (= :nippy (s/format-from-content-type "application/x-nippy")))
  (is (= :edn   (s/format-from-content-type "application/edn")))
  (is (= :bytes (s/format-from-content-type "application/octet-stream")))
  (is (= :bytes (s/format-from-content-type "completely/unknown"))))

;; --- Property-based round-trip ---------------------------------------

(def edn-scalar-gen
  "Generator for EDN scalar values used in property-based tests."
  (gen/one-of [gen/small-integer gen/large-integer gen/string-ascii
               gen/keyword gen/keyword-ns gen/boolean]))

(def edn-value-gen
  "Generator for nested EDN values built from scalars + collections."
  (gen/recursive-gen
   (fn [inner]
     (gen/one-of [(gen/vector inner 0 5)
                  (gen/list inner)
                  (gen/set inner)
                  (gen/map edn-scalar-gen inner)]))
   edn-scalar-gen))

(deftest nippy-round-trip-property
  (testing "nippy round-trips arbitrary EDN values"
    (let [r (tc/quick-check
             50
             (prop/for-all [v edn-value-gen]
                           (let [{data :bytes} (s/encode {:format :nippy
                                                          :value  v})]
                             (= v (s/decode {:format :nippy :bytes data})))))]
      (is (:result r) (str r)))))

(deftest edn-round-trip-property
  (testing "edn round-trips most EDN values (skips :sets, which print fine
            but read back as `#{}` literals — which clojure.edn handles)"
    (let [r (tc/quick-check
             50
             (prop/for-all [v edn-value-gen]
                           (let [{data :bytes} (s/encode {:format :edn
                                                          :value  v})]
                             (= v (s/decode {:format :edn :bytes data})))))]
      (is (:result r) (str r)))))
