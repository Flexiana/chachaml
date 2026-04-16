(ns chachaml.registry-test
  "Tests for the model registry public API and stage exclusivity."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.store.sqlite :as sqlite]
            [chachaml.test-helpers :as h]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]))

(use-fixtures :each h/with-fresh-store)

(defn- train-and-register!
  "Helper: produce a run with a `model` artifact and register it."
  [model-name {:keys [stage description model-value]
               :or   {model-value {:weights [1.0 2.0]}}}]
  (ml/with-run {:experiment "test"}
    (ml/log-artifact "model" model-value)
    (reg/register-model model-name {:artifact    "model"
                                    :stage       stage
                                    :description description})))

;; --- Basic registration ---------------------------------------------

(deftest register-creates-model-and-version-1
  (let [v (train-and-register! "iris" {:stage :staging})]
    (is (= 1 (:version v)))
    (is (= :staging (:stage v)))
    (is (= "iris" (:model-name v)))
    (is (some? (reg/model "iris")))
    (is (= 1 (count (reg/models))))))

(deftest register-second-version-increments
  (train-and-register! "iris" {:stage :none})
  (let [v2 (train-and-register! "iris" {:stage :none})]
    (is (= 2 (:version v2)))
    (is (= 2 (count (reg/model-versions "iris"))))))

(deftest register-rejects-missing-artifact
  (ml/with-run {}
    (is (thrown? Exception
                 (reg/register-model "iris" {:artifact "missing"})))))

(deftest register-rejects-bad-opts
  (ml/with-run {}
    (ml/log-artifact "model" {:x 1})
    (is (thrown? Exception
                 (reg/register-model "iris" {:artifact "model"
                                             :stage    :unknown})))))

(deftest register-fails-outside-run-without-run-id
  (is (thrown? Exception
               (reg/register-model "iris" {:artifact "model"}))))

;; --- Promotion + stage exclusivity ----------------------------------

(deftest promote-changes-stage
  (let [v1 (train-and-register! "iris" {:stage :none})
        updated (reg/promote! "iris" (:version v1) :staging)]
    (is (= :staging (:stage updated)))))

(deftest promote-to-production-archives-previous
  (train-and-register! "iris" {:stage :production})
  (let [v2 (train-and-register! "iris" {:stage :none})]
    (reg/promote! "iris" (:version v2) :production)
    (let [versions (reg/model-versions "iris")
          by-v    (zipmap (map :version versions)
                          (map :stage versions))]
      (is (= :archived (by-v 1)))
      (is (= :production (by-v 2))))))

(deftest production-stage-exclusivity-property
  (testing "after a sequence of promote! ops, at most one version per
            model is :production"
    (let [r (tc/quick-check
             30
             (prop/for-all
              [ops (gen/vector
                    (gen/tuple (gen/elements [:add :promote])
                               gen/nat)
                    1 8)]
              ;; This property generates many independent stores;
              ;; the outer fixture's binding is shadowed deliberately.
              (binding [ctx/*store* (sqlite/open {:in-memory? true})]
                (let [model "m"]
                  (doseq [[op idx] ops]
                    (case op
                      :add     (train-and-register! model {:stage :none})
                      :promote (let [vs (reg/model-versions model)]
                                 (when (seq vs)
                                   (let [v (:version (nth vs (mod idx (count vs))))]
                                     (try (reg/promote! model v :production)
                                          (catch Exception _)))))))
                  (let [prod (filter #(= :production (:stage %))
                                     (reg/model-versions model))]
                    (<= (count prod) 1))))))]
      (is (:result r) (str r)))))

;; --- Loading ---------------------------------------------------------

(deftest load-model-by-version
  (train-and-register! "iris"
                       {:stage :none :model-value {:w [1.0 2.0]}})
  (train-and-register! "iris"
                       {:stage :none :model-value {:w [3.0 4.0]}})
  (is (= {:w [1.0 2.0]} (reg/load-model "iris" {:version 1})))
  (is (= {:w [3.0 4.0]} (reg/load-model "iris" {:version 2}))))

(deftest load-model-default-is-latest-production
  (train-and-register! "iris"
                       {:stage :production :model-value {:w [1.0]}})
  (train-and-register! "iris"
                       {:stage :production :model-value {:w [2.0]}})
  (is (= {:w [2.0]} (reg/load-model "iris"))))

(deftest load-model-by-stage
  (train-and-register! "iris" {:stage :staging :model-value {:w [9.0]}})
  (is (= {:w [9.0]} (reg/load-model "iris" {:stage :staging})))
  (is (nil? (reg/load-model "iris" {:stage :production}))))

(deftest load-model-missing-returns-nil
  (is (nil? (reg/load-model "nope")))
  (is (nil? (reg/load-model "nope" {:version 1}))))
