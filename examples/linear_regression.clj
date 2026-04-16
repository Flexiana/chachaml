(ns linear-regression
  "Univariate linear regression by gradient descent.

  Generates synthetic data y = w*x + b + ε, then fits (w, b) by mean
  squared error gradient descent. Each epoch's loss is logged as a
  chachaml metric; the final weights and their absolute errors are
  logged as terminal metrics.

  Run from CLI:

      clojure -M:examples -m linear-regression

  …which writes to the default `./chachaml.db`. To inspect after:

      clojure -M:examples -e '
        (require (quote [chachaml.core :as ml]))
        (clojure.pprint/pprint (ml/run (:id (ml/last-run))))'"
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [clojure.pprint :as pp])
  (:import [java.util Random]))

(def default-opts
  "Defaults used by `-main`. Tests typically override `:epochs`."
  {:n        200
   :true-w   3.0
   :true-b   2.0
   :noise    0.5
   :lr       0.05
   :epochs   400
   :seed     42})

(defn- gaussians
  "Return `n` independent N(0,1) doubles using `rng`."
  [^Random rng n]
  (vec (repeatedly n #(.nextGaussian rng))))

(defn synthesize
  "Build a synthetic dataset of `n` points around y = w*x + b with
  Gaussian noise of stddev `noise`. Returns `[xs ys]`."
  [{:keys [n true-w true-b noise seed]}]
  (let [rng (Random. seed)
        xs  (gaussians rng n)
        ys  (mapv (fn [x] (+ (* true-w x) true-b (* noise (.nextGaussian rng))))
                  xs)]
    [xs ys]))

(defn- mse [xs ys w b]
  (let [n (count xs)
        sq (reduce + (map (fn [x y]
                            (let [e (- (+ (* w x) b) y)]
                              (* e e)))
                          xs ys))]
    (/ sq n)))

(defn- gradient-step
  "One gradient-descent step on (w, b). Returns the new pair."
  [xs ys w b lr]
  (let [n  (count xs)
        es (mapv (fn [x y] (- (+ (* w x) b) y)) xs ys)
        dw (/ (* 2.0 (reduce + (map * es xs))) n)
        db (/ (* 2.0 (reduce + es)) n)]
    [(- w (* lr dw)) (- b (* lr db))]))

(defn fit
  "Train weights for `epochs` iterations, logging loss per epoch.

  Returns `{:w :b :final-loss}`. Must be called inside `with-run`."
  [xs ys {:keys [lr epochs]}]
  (loop [w 0.0 b 0.0 epoch 0]
    (let [loss (mse xs ys w b)]
      (ml/log-metric :loss loss epoch)
      (if (>= epoch epochs)
        {:w w :b b :final-loss loss}
        (let [[w' b'] (gradient-step xs ys w b lr)]
          (recur w' b' (inc epoch)))))))

(defn run-experiment!
  "Run the full experiment under the ambient chachaml store. Returns
  the completed run id. Tests override `opts` for speed."
  ([] (run-experiment! {}))
  ([opts]
   (let [opts (merge default-opts opts)
         [xs ys] (synthesize opts)]
     (ml/with-run {:experiment "linear-regression"
                   :name       "synthetic"
                   :tags       {:algorithm "gradient-descent"}}
       (ml/log-params (select-keys opts [:n :true-w :true-b :noise
                                         :lr :epochs :seed]))
       (let [{:keys [w b final-loss]} (fit xs ys opts)]
         (ml/log-metrics {:final-w     w
                          :final-b     b
                          :final-loss  final-loss
                          :w-abs-error (Math/abs ^double (- w (:true-w opts)))
                          :b-abs-error (Math/abs ^double (- b (:true-b opts)))})
         (ml/log-artifact "model" {:type   :linear-regression
                                   :w      w
                                   :b      b
                                   :loss   final-loss
                                   :opts   opts})
         (:id (ctx/current-run)))))))

(defn -main
  "CLI entry. Runs `run-experiment!` with defaults and pretty-prints
  the resulting run for quick inspection."
  [& _args]
  (let [run-id (run-experiment!)
        run    (ml/run run-id)]
    (println "\nCompleted run" run-id)
    (println "Params:") (pp/pprint (:params run))
    (println "Final metrics:")
    (pp/pprint (->> (:metrics run)
                    (remove #(= :loss (:key %)))
                    (mapv (juxt :key :value))
                    (into {})))
    (println (str "(Loss curve has " (count (filter #(= :loss (:key %))
                                                    (:metrics run)))
                  " points)"))))
