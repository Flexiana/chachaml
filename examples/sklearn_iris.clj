(ns sklearn-iris
  "Tracked sklearn LogisticRegression on the Iris dataset.

  Demonstrates the full chachaml + libpython-clj2 interop loop:
  train, evaluate, save artifact, register model.

  Requires Python 3 with scikit-learn installed, plus the `:python`
  alias:

      clojure -M:python:examples -m sklearn-iris

  If Python is not available, use the pure-Clojure `linear-regression`
  or `kmeans` examples instead."
  (:require [chachaml.core :as ml]
            [chachaml.interop.sklearn :as sk]))

(defn run-experiment!
  "Train LogisticRegression on Iris. Returns the run id.

  Requires libpython-clj2 + sklearn at runtime."
  ([] (run-experiment! {}))
  ([{:keys [max-iter test-size]
     :or   {max-iter 200 test-size 0.2}}]
   (let [require-python (requiring-resolve
                         'libpython-clj2.require/require-python)]
     ;; Import sklearn modules
     (require-python '[sklearn.datasets :as datasets])
     (require-python '[sklearn.linear_model :as lm])
     (require-python '[sklearn.model_selection :as ms])

     (let [py-call (requiring-resolve 'libpython-clj2.python/call-attr)
           py-get  (requiring-resolve 'libpython-clj2.python/get-attr)
           iris    ((resolve 'datasets/load_iris))
           X       (py-get iris "data")
           y       (py-get iris "target")
           split   (py-call (resolve 'ms/train_test_split)
                            X y :test_size test-size :random_state 42)
           [X-train X-test y-train y-test] split]
       (ml/with-run {:experiment "sklearn-iris"
                     :name       "logistic-regression"
                     :tags       {:framework "sklearn"
                                  :dataset   "iris"}}
         (ml/log-params {:model     "LogisticRegression"
                         :max-iter  max-iter
                         :test-size test-size})
         (let [{:keys [metrics]}
               (sk/train-and-evaluate!
                ((resolve 'lm/LogisticRegression) :max_iter max-iter)
                X-train y-train X-test y-test
                :register-as "iris-classifier"
                :stage       :staging)]
           (println "Accuracy:" (:accuracy metrics))
           (:id (chachaml.context/current-run))))))))

(defn -main [& _args]
  (let [run-id (run-experiment!)]
    (println "Run:" run-id)
    (println "Inspect with: (chachaml.repl/inspect" (pr-str run-id) ")")))
