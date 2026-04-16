(ns kmeans
  "Lloyd's k-means clustering on synthetic 2D Gaussian blobs.

  Generates `:k` clusters of 2D points around random centers, then
  re-clusters them with k-means. Per-iteration inertia is logged as a
  metric; final cluster sizes and the recovered centers are logged as
  params/metrics on completion.

  Run from CLI:

      clojure -M:examples -m kmeans"
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [clojure.pprint :as pp])
  (:import [java.util ArrayList Collections Random]))

(defn- shuffle-with
  "Deterministic shuffle of `coll` using `rng`."
  [^Random rng coll]
  (let [lst (ArrayList. ^java.util.Collection coll)]
    (Collections/shuffle lst rng)
    (vec lst)))

(def default-opts
  "Defaults used by `-main`. Tests typically shrink `:n-per-cluster`."
  {:k             3
   :n-per-cluster 80
   :spread        0.6
   :max-iter      30
   :seed          7})

(defn- gaussian-pair [^Random rng]
  [(.nextGaussian rng) (.nextGaussian rng)])

(defn- distance-sq [[x1 y1] [x2 y2]]
  (let [dx (- x1 x2) dy (- y1 y2)]
    (+ (* dx dx) (* dy dy))))

(defn synthesize
  "Build `k` Gaussian blobs of `n-per-cluster` points each. Returns
  `{:points […] :true-centers […]}`."
  [{:keys [k n-per-cluster spread seed]}]
  (let [rng     (Random. seed)
        centers (vec (repeatedly k #(mapv (fn [v] (* 5.0 v))
                                          (gaussian-pair rng))))
        points  (vec
                 (mapcat (fn [c]
                           (repeatedly n-per-cluster
                                       #(mapv + c
                                              (mapv (fn [v] (* spread v))
                                                    (gaussian-pair rng)))))
                         centers))]
    {:points points :true-centers centers}))

(defn- closest [point centroids]
  (apply min-key #(distance-sq point (nth centroids %))
         (range (count centroids))))

(defn- mean-of [pts]
  (let [n (count pts)
        [sx sy] (reduce (fn [[a b] [x y]] [(+ a x) (+ b y)]) [0.0 0.0] pts)]
    [(/ sx n) (/ sy n)]))

(defn- recompute-centroids [points assignments k current]
  (mapv (fn [c]
          (let [grp (->> (map vector points assignments)
                         (filter #(= c (second %)))
                         (mapv first))]
            (if (seq grp) (mean-of grp) (nth current c))))
        (range k)))

(defn- inertia [points centroids assignments]
  (reduce + (map (fn [p a] (distance-sq p (nth centroids a)))
                 points assignments)))

(defn fit
  "Run Lloyd's algorithm. Logs `:inertia` per iteration. Stops on
  convergence or `:max-iter`. Must be called inside `with-run`.

  Returns `{:centroids :assignments :inertia :iterations :converged?}`."
  [points {:keys [k max-iter seed]}]
  (let [rng  (Random. (long seed))
        idx  (vec (take k (shuffle-with rng (range (count points)))))
        init (mapv #(nth points %) idx)]
    (loop [centroids init iter 0]
      (let [assignments (mapv #(closest % centroids) points)
            j           (inertia points centroids assignments)]
        (ml/log-metric :inertia j iter)
        (let [next (recompute-centroids points assignments k centroids)
              done? (or (= next centroids) (>= iter max-iter))]
          (if done?
            {:centroids   centroids
             :assignments assignments
             :inertia     j
             :iterations  iter
             :converged?  (= next centroids)}
            (recur next (inc iter))))))))

(defn run-experiment!
  "Run the full experiment under the ambient chachaml store. Returns
  the completed run id."
  ([] (run-experiment! {}))
  ([opts]
   (let [opts (merge default-opts opts)
         {:keys [points true-centers]} (synthesize opts)]
     (ml/with-run {:experiment "kmeans"
                   :name       "synthetic-blobs"
                   :tags       {:algorithm "lloyd"}}
       (ml/log-params (assoc (select-keys opts [:k :n-per-cluster :spread
                                                :max-iter :seed])
                             :true-centers true-centers
                             :n-points (count points)))
       (let [{:keys [centroids assignments inertia iterations converged?]}
             (fit points opts)
             sizes (frequencies assignments)]
         (ml/log-metrics
          (into {:final-inertia    inertia
                 :iterations-used  iterations
                 :converged        (if converged? 1.0 0.0)}
                (map (fn [[c n]] [(keyword (str "size-c" c)) (double n)]))
                sizes))
         (ml/log-artifact "model" {:type        :kmeans
                                   :centroids   centroids
                                   :assignments assignments
                                   :inertia     inertia
                                   :iterations  iterations
                                   :converged?  converged?
                                   :opts        opts})
         (:id (ctx/current-run)))))))

(defn -main
  "CLI entry. Runs `run-experiment!` with defaults and pretty-prints
  the result."
  [& _args]
  (let [run-id (run-experiment!)
        run    (ml/run run-id)]
    (println "\nCompleted run" run-id)
    (println "Params:") (pp/pprint (:params run))
    (println "Terminal metrics:")
    (pp/pprint (->> (:metrics run)
                    (remove #(= :inertia (:key %)))
                    (mapv (juxt :key :value))
                    (into {})))
    (println (str "(Inertia curve has " (count (filter #(= :inertia (:key %))
                                                       (:metrics run)))
                  " points)"))))
