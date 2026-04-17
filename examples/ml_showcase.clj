(ns ml-showcase
  "25 common ML use cases demonstrating comprehensive chachaml tracking.

  Each use case is a function returning a run-id. All use synthetic data
  generated in pure Clojure — no external deps beyond chachaml.

  Run all:  clojure -M:examples -m ml-showcase
  Run one:  call individual fns from the REPL after requiring this ns."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.repl :as repl])
  (:import [java.util Random]))

;; =====================================================================
;; Data generation utilities
;; =====================================================================

(def ^:private rng (Random. 42))

(defn- gauss [] (.nextGaussian ^Random rng))
(defn- rand-val [] (.nextDouble ^Random rng))

(defn- gen-blobs
  "Generate `n` points per class in `d` dimensions around random centers.
  Default spread=1.5 produces overlapping clusters for realistic accuracy."
  [n-per-class n-classes d & {:keys [spread] :or {spread 1.5}}]
  (let [centers (vec (repeatedly n-classes
                                 #(vec (repeatedly d (fn [] (* 2.0 (gauss)))))))]
    {:X (vec (mapcat (fn [c]
                       (repeatedly n-per-class
                                   #(mapv (fn [ci] (+ ci (* spread (gauss)))) c)))
                     centers))
     :y (vec (mapcat (fn [i] (repeat n-per-class i)) (range n-classes)))
     :centers centers}))

(defn- gen-regression
  "Generate y = w·x + b + noise."
  [n d & {:keys [noise] :or {noise 0.3}}]
  (let [true-w (vec (repeatedly d #(* 3.0 (gauss))))
        true-b (* 2.0 (gauss))
        X (vec (repeatedly n #(vec (repeatedly d gauss))))
        y (mapv (fn [x] (+ (reduce + (map * true-w x)) true-b (* noise (gauss)))) X)]
    {:X X :y y :true-w true-w :true-b true-b}))

(defn- train-test-split [X y ratio]
  (let [n     (count X)
        split (int (* n ratio))
        idx   (shuffle (range n))
        tr    (take split idx)
        te    (drop split idx)]
    [(mapv X tr) (mapv X te) (mapv y tr) (mapv y te)]))

(defn- accuracy [y-true y-pred]
  (/ (count (filter true? (map = y-true y-pred))) (double (count y-true))))

(defn- mse [y-true y-pred]
  (/ (reduce + (map (fn [a b] (let [e (- a b)] (* e e))) y-true y-pred))
     (double (count y-true))))

(defn- rmse [y-true y-pred] (Math/sqrt (mse y-true y-pred)))

(defn- mae [y-true y-pred]
  (/ (reduce + (map (fn [a b] (Math/abs (- (double a) (double b)))) y-true y-pred))
     (double (count y-true))))

(defn- r-squared [y-true y-pred]
  (let [mean-y (/ (reduce + y-true) (double (count y-true)))
        ss-res (reduce + (map (fn [a b] (let [e (- a b)] (* e e))) y-true y-pred))
        ss-tot (reduce + (map (fn [a] (let [e (- a mean-y)] (* e e))) y-true))]
    (- 1.0 (/ ss-res (max ss-tot 1e-10)))))

(defn- sigmoid [z] (/ 1.0 (+ 1.0 (Math/exp (- z)))))

(defn- dot [a b] (reduce + (map * a b)))

(defn- confusion-matrix [y-true y-pred classes]
  (let [idx (zipmap classes (range))]
    (reduce (fn [m [t p]]
              (update-in m [(idx t) (idx p)] (fnil inc 0)))
            (vec (repeat (count classes) (vec (repeat (count classes) 0))))
            (map vector y-true y-pred))))

(defn- run-id! [] (:id (ctx/current-run)))

;; =====================================================================
;; 1. Binary classification — logistic regression, per-epoch loss curve
;; =====================================================================

(defn uc01-binary-classification []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "01-binary-classification"
                  :tags {:category "classification"}}
      (ml/log-params {:algorithm "logistic-regression" :lr 0.1 :epochs 100
                      :n-train (count Xtr) :n-test (count Xte) :n-features 3})
      (let [w (atom (vec (repeat 3 0.0)))
            b (atom 0.0)]
        (dotimes [epoch 100]
          (doseq [[x yi] (map vector Xtr ytr)]
            (let [p (sigmoid (+ (dot @w x) @b))
                  e (- p yi)]
              (swap! w #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
              (swap! b - (* 0.1 e))))
          (let [n    (count Xtr)
                loss (/ (- (reduce + (map (fn [x yi]
                                            (let [p (sigmoid (+ (dot @w x) @b))]
                                              (+ (* yi (Math/log (max p 1e-10)))
                                                 (* (- 1 yi) (Math/log (max (- 1 p) 1e-10))))))
                                          Xtr ytr)))
                        n)]
            (ml/log-metric :loss loss epoch)))
        (let [preds (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte)
              acc   (accuracy yte preds)]
          (ml/log-metrics {:accuracy acc :n-correct (long (* acc (count yte)))})
          (ml/log-artifact "model" {:w @w :b @b :type :logistic-regression})
          (ml/log-table "confusion-matrix"
                       {:headers ["pred-0" "pred-1"]
                        :rows (confusion-matrix yte preds [0 1])})
          (ml/log-dataset! {:role "train" :n-rows (count Xtr) :n-cols 3})
          (ml/log-dataset! {:role "test"  :n-rows (count Xte) :n-cols 3})
          (reg/register-model "binary-classifier" {:artifact "model" :stage :staging})))
      (run-id!))))

;; =====================================================================
;; 2. Multi-class classification — one-vs-rest
;; =====================================================================

(defn uc02-multiclass-classification []
  (let [{:keys [X y]} (gen-blobs 50 3 4)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "02-multiclass"
                  :tags {:category "classification"}}
      (ml/log-params {:algorithm "one-vs-rest-logistic" :n-classes 3
                      :n-train (count Xtr) :n-features 4 :epochs 80})
      (let [models (into {}
                         (for [c (range 3)]
                           (let [yb (mapv #(if (= % c) 1 0) ytr)
                                 w  (atom (vec (repeat 4 0.0)))
                                 b  (atom 0.0)]
                             (dotimes [_ 80]
                               (doseq [[x yi] (map vector Xtr yb)]
                                 (let [p (sigmoid (+ (dot @w x) @b))
                                       e (- p yi)]
                                   (swap! w #(mapv (fn [wi xi] (- wi (* 0.05 e xi))) % x))
                                   (swap! b - (* 0.05 e)))))
                             [c {:w @w :b @b}])))]
        (let [preds (mapv (fn [x]
                            (apply max-key
                                   (fn [c] (sigmoid (+ (dot (:w (models c)) x)
                                                       (:b (models c)))))
                                   (range 3)))
                          Xte)
              acc (accuracy yte preds)]
          (doseq [c (range 3)]
            (let [c-acc (accuracy (map #(if (= % c) 1 0) yte)
                                  (map #(if (= % c) 1 0) preds))]
              (ml/log-metric (keyword (str "accuracy-class-" c)) c-acc)))
          (ml/log-metrics {:overall-accuracy acc})
          (ml/log-artifact "model" {:type :one-vs-rest :classifiers models})))
      (run-id!))))

;; =====================================================================
;; 3. Decision stump — single best feature split
;; =====================================================================

(defn- gini [ys]
  (let [n (double (count ys))
        freqs (vals (frequencies ys))]
    (- 1.0 (reduce + (map (fn [f] (let [p (/ f n)] (* p p))) freqs)))))

(defn- best-split [X y d]
  (let [n (count X)]
    (apply min-key :impurity
           (for [feat (range d)
                 threshold (distinct (map #(nth % feat) X))]
             (let [left-idx  (filter #(<= (nth (X %) feat) threshold) (range n))
                   right-idx (filter #(>  (nth (X %) feat) threshold) (range n))
                   left-y  (mapv y left-idx)
                   right-y (mapv y right-idx)]
               (if (or (empty? left-y) (empty? right-y))
                 {:impurity 1.0}
                 {:feature feat :threshold threshold
                  :impurity (+ (* (/ (count left-y) (double n)) (gini left-y))
                               (* (/ (count right-y) (double n)) (gini right-y)))}))))))

(defn uc03-decision-stump []
  (let [{:keys [X y]} (gen-blobs 60 2 2)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "03-decision-stump"
                  :tags {:category "classification"}}
      (let [stump (best-split (vec Xtr) (vec ytr) 2)
            left-class (let [idxs (filter #(<= (nth (Xtr %) (:feature stump))
                                               (:threshold stump)) (range (count Xtr)))]
                         (key (apply max-key val (frequencies (mapv ytr idxs)))))
            right-class (let [idxs (filter #(> (nth (Xtr %) (:feature stump))
                                               (:threshold stump)) (range (count Xtr)))]
                          (key (apply max-key val (frequencies (mapv ytr idxs)))))
            model {:feature (:feature stump) :threshold (:threshold stump)
                   :left-class left-class :right-class right-class}
            preds (mapv (fn [x] (if (<= (nth x (:feature model)) (:threshold model))
                                  left-class right-class)) Xte)]
        (ml/log-params {:algorithm "decision-stump" :split-feature (:feature model)
                        :threshold (:threshold model) :gini-impurity (:impurity stump)})
        (ml/log-metrics {:accuracy (accuracy yte preds)})
        (ml/log-artifact "model" model)))
    (run-id!)))

;; =====================================================================
;; 4. Random forest (bag of stumps)
;; =====================================================================

(defn uc04-random-forest []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        n-trees 10]
    (ml/with-run {:experiment "04-random-forest"
                  :tags {:category "classification" :ensemble "true"}}
      (ml/log-params {:algorithm "random-forest" :n-trees n-trees
                      :n-train (count Xtr) :n-features 3})
      (let [trees (vec
                   (for [t (range n-trees)]
                     (let [idx (vec (repeatedly (count Xtr) #(rand-int (count Xtr))))
                           Xb (mapv Xtr idx)
                           yb (mapv ytr idx)
                           stump (best-split Xb yb 3)
                           left-y (mapv yb (filter #(<= (nth (Xb %) (:feature stump))
                                                        (:threshold stump)) (range (count Xb))))
                           right-y (mapv yb (filter #(> (nth (Xb %) (:feature stump))
                                                        (:threshold stump)) (range (count Xb))))]
                       (ml/log-metric :tree-gini (:impurity stump 1.0) t)
                       {:feature (:feature stump) :threshold (:threshold stump)
                        :left (key (apply max-key val (frequencies (if (seq left-y) left-y [0]))))
                        :right (key (apply max-key val (frequencies (if (seq right-y) right-y [0]))))})))
            preds (mapv (fn [x]
                          (let [votes (map (fn [tree]
                                             (if (<= (nth x (:feature tree)) (:threshold tree))
                                               (:left tree) (:right tree)))
                                           trees)]
                            (key (apply max-key val (frequencies votes)))))
                        Xte)]
        (ml/log-metrics {:accuracy (accuracy yte preds) :n-trees n-trees})
        (ml/log-artifact "forest" {:type :random-forest :trees trees})
        (reg/register-model "random-forest" {:artifact "forest" :stage :staging}))
      (run-id!))))

;; =====================================================================
;; 5. K-nearest neighbors — confusion matrix artifact
;; =====================================================================

(defn- euclidean-dist [a b]
  (Math/sqrt (reduce + (map (fn [ai bi] (let [d (- ai bi)] (* d d))) a b))))

(defn uc05-knn []
  (let [{:keys [X y]} (gen-blobs 60 3 2)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        k 5]
    (ml/with-run {:experiment "05-knn"
                  :tags {:category "classification"}}
      (ml/log-params {:algorithm "knn" :k k :n-train (count Xtr) :n-classes 3})
      (let [preds (mapv (fn [x]
                          (let [dists (map-indexed (fn [i xt] [(euclidean-dist x xt) i]) Xtr)
                                top-k (take k (sort-by first dists))
                                votes (map (fn [[_ i]] (ytr i)) top-k)]
                            (key (apply max-key val (frequencies votes)))))
                        Xte)
            acc (accuracy yte preds)
            cm  (confusion-matrix yte preds [0 1 2])]
        (ml/log-metrics {:accuracy acc})
        (ml/log-artifact "confusion-matrix" cm)
        (ml/log-artifact "model" {:type :knn :k k :training-data-size (count Xtr)}))
      (run-id!))))

;; =====================================================================
;; 6. Naive Bayes (Gaussian)
;; =====================================================================

(defn uc06-naive-bayes []
  (let [{:keys [X y]} (gen-blobs 80 3 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        classes (distinct ytr)]
    (ml/with-run {:experiment "06-naive-bayes"
                  :tags {:category "classification"}}
      (let [stats (into {}
                        (for [c classes]
                          (let [cx (filter (fn [[_ yi]] (= yi c)) (map vector Xtr ytr))
                                xs (mapv first cx)
                                n  (count xs)
                                d  (count (first Xtr))]
                            [c {:prior (/ n (double (count Xtr)))
                                :mean  (mapv (fn [j] (/ (reduce + (map #(nth % j) xs)) (double n)))
                                             (range d))
                                :var   (mapv (fn [j]
                                               (let [m (/ (reduce + (map #(nth % j) xs)) (double n))]
                                                 (/ (reduce + (map #(let [e (- (nth % j) m)] (* e e)) xs))
                                                    (double n))))
                                             (range d))}])))
            log-pdf (fn [x c]
                      (let [{:keys [prior mean var]} (stats c)]
                        (+ (Math/log prior)
                           (reduce + (map-indexed
                                      (fn [j xj]
                                        (let [m (nth mean j) v (max (nth var j) 1e-6)]
                                          (- (* -0.5 (Math/log (* 2 Math/PI v)))
                                             (/ (* (- xj m) (- xj m)) (* 2 v)))))
                                      x)))))
            preds (mapv (fn [x] (apply max-key #(log-pdf x %) classes)) Xte)]
        (ml/log-params {:algorithm "gaussian-naive-bayes" :n-classes (count classes)})
        (doseq [[c s] stats]
          (ml/log-param (keyword (str "prior-class-" c)) (:prior s)))
        (ml/log-metrics {:accuracy (accuracy yte preds)})
        (ml/log-artifact "model" {:type :naive-bayes :stats stats}))
      (run-id!))))

;; =====================================================================
;; 7. SVM (perceptron-style linear)
;; =====================================================================

(defn uc07-svm []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        y-svm (mapv #(if (= % 0) -1 1) y)
        [Xtr Xte ytr yte] (train-test-split X y-svm 0.8)]
    (ml/with-run {:experiment "07-svm"
                  :tags {:category "classification"}}
      (ml/log-params {:algorithm "linear-svm-sgd" :lr 0.01 :epochs 50
                      :lambda 0.01 :n-features 3})
      (let [w (atom (vec (repeat 3 0.0)))
            b (atom 0.0)]
        (dotimes [epoch 50]
          (doseq [[x yi] (map vector Xtr ytr)]
            (if (< (* yi (+ (dot @w x) @b)) 1)
              (do (swap! w #(mapv (fn [wi xi] (+ wi (* 0.01 (- (* yi xi) (* 0.01 wi))))) % x))
                  (swap! b + (* 0.01 yi)))
              (swap! w #(mapv (fn [wi] (* wi (- 1 (* 0.01 0.01)))) %))))
          (let [margin (/ 1.0 (max (Math/sqrt (reduce + (map #(* % %) @w))) 1e-10))]
            (ml/log-metric :margin margin epoch)))
        (let [preds (mapv (fn [x] (if (pos? (+ (dot @w x) @b)) 1 -1)) Xte)]
          (ml/log-metrics {:accuracy (accuracy yte preds)})
          (ml/log-artifact "model" {:type :svm :w @w :b @b})))
      (run-id!))))

;; =====================================================================
;; 8. Linear regression — R², MAE, RMSE
;; =====================================================================

(defn uc08-linear-regression []
  (let [{:keys [X y true-w true-b]} (gen-regression 150 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "08-linear-regression"
                  :tags {:category "regression"}}
      (ml/log-params {:algorithm "gradient-descent" :lr 0.01 :epochs 200
                      :n-features 3 :true-w true-w :true-b true-b})
      (let [w (atom (vec (repeat 3 0.0)))
            b (atom 0.0)]
        ;; v0.4: batch metrics to reduce SQL round-trips
        (ml/with-batched-metrics
          (dotimes [epoch 200]
            (doseq [[x yi] (map vector Xtr ytr)]
              (let [pred (+ (dot @w x) @b)
                    e    (- pred yi)]
                (swap! w #(mapv (fn [wi xi] (- wi (* 0.01 (/ (* 2 e xi) (count Xtr))))) % x))
                (swap! b - (* 0.01 (/ (* 2 e) (count Xtr))))))
            (when (zero? (mod epoch 20))
              (let [preds (mapv #(+ (dot @w %) @b) Xtr)]
                (ml/log-metric :train-mse (mse ytr preds) epoch)))))
        (let [preds (mapv #(+ (dot @w %) @b) Xte)]
          (ml/log-metrics {:r2 (r-squared yte preds) :mae (mae yte preds)
                           :rmse (rmse yte preds) :mse (mse yte preds)})
          (ml/log-artifact "model" {:type :linear-regression :w @w :b @b})))
      (run-id!))))

;; =====================================================================
;; 9. Polynomial regression
;; =====================================================================

(defn- poly-features [x degree]
  (vec (for [d (range 1 (inc degree))] (Math/pow x (double d)))))

(defn uc09-polynomial-regression []
  (let [n 100
        degree 3
        xs (vec (repeatedly n #(* 4.0 (- (rand-val) 0.5))))
        ys (mapv (fn [x] (+ (* 0.5 x x x) (- (* 2 x)) 1 (* 0.5 (gauss)))) xs)
        X  (mapv #(poly-features % degree) xs)
        [Xtr Xte ytr yte] (train-test-split X ys 0.8)]
    (ml/with-run {:experiment "09-polynomial-regression"
                  :tags {:category "regression"}}
      (ml/log-params {:algorithm "polynomial-regression" :degree degree :n n})
      (let [w (atom (vec (repeat degree 0.0)))
            b (atom 0.0)]
        (dotimes [_ 300]
          (doseq [[x yi] (map vector Xtr ytr)]
            (let [pred (+ (dot @w x) @b)
                  e    (- pred yi)]
              (swap! w #(mapv (fn [wi xi] (- wi (* 0.001 e xi))) % x))
              (swap! b - (* 0.001 e)))))
        (let [preds (mapv #(+ (dot @w %) @b) Xte)]
          (ml/log-metrics {:r2 (r-squared yte preds) :rmse (rmse yte preds)})
          (ml/log-artifact "model" {:type :polynomial :degree degree :w @w :b @b})))
      (run-id!))))

;; =====================================================================
;; 10. Ridge regression (L2) — lambda sweep
;; =====================================================================

(defn uc10-ridge-regression []
  (let [{:keys [X y]} (gen-regression 120 4)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "10-ridge-regression"
                  :tags {:category "regression"}}
      (let [results
            (vec (for [lambda [0.001 0.01 0.1 1.0 10.0]]
                   (let [w (atom (vec (repeat 4 0.0)))
                         b (atom 0.0)]
                     (dotimes [_ 200]
                       (doseq [[x yi] (map vector Xtr ytr)]
                         (let [pred (+ (dot @w x) @b)
                               e    (- pred yi)]
                           (swap! w #(mapv (fn [wi xi] (- wi (* 0.005 (+ (* 2 e xi) (* 2 lambda wi))))) % x))
                           (swap! b - (* 0.005 (* 2 e))))))
                     (let [preds (mapv #(+ (dot @w %) @b) Xte)]
                       (ml/log-metric :r2-at-lambda (r-squared yte preds)
                                      (long (* 1000 lambda)))
                       {:lambda lambda :r2 (r-squared yte preds) :w @w :b @b}))))
            best (apply max-key :r2 results)]
        (ml/log-params {:algorithm "ridge-regression" :best-lambda (:lambda best)
                        :lambdas-tested (mapv :lambda results)})
        (ml/log-metrics {:best-r2 (:r2 best)})
        (ml/log-artifact "model" {:type :ridge :w (:w best) :b (:b best)
                                  :lambda (:lambda best)}))
      (run-id!))))

;; =====================================================================
;; 11. Lasso (L1 coordinate descent) — sparsity metric
;; =====================================================================

(defn uc11-lasso []
  (let [{:keys [X y]} (gen-regression 100 6 :noise 0.1)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        lambda 0.5]
    (ml/with-run {:experiment "11-lasso"
                  :tags {:category "regression"}}
      (ml/log-params {:algorithm "lasso-coordinate-descent" :lambda lambda
                      :n-features 6 :epochs 100})
      (let [w (atom (vec (repeat 6 0.0)))
            b (atom 0.0)]
        (dotimes [epoch 100]
          (dotimes [j 6]
            (let [residuals (mapv (fn [x yi]
                                    (- yi (+ @b (reduce + (map-indexed
                                                           (fn [k wk] (if (= k j) 0 (* wk (nth x k))))
                                                           @w)))))
                                  Xtr ytr)
                  rho (reduce + (map (fn [x r] (* (nth x j) r)) Xtr residuals))
                  z   (reduce + (map (fn [x] (let [v (nth x j)] (* v v))) Xtr))
                  new-wj (/ (cond
                              (> rho lambda) (- rho lambda)
                              (< rho (- lambda)) (+ rho lambda)
                              :else 0.0)
                            (max z 1e-10))]
              (swap! w assoc j new-wj)))
          (swap! b (fn [_] (/ (reduce + (map (fn [x yi] (- yi (dot @w x))) Xtr ytr))
                              (double (count Xtr)))))
          (when (zero? (mod epoch 10))
            (ml/log-metric :sparsity (count (filter #(< (Math/abs ^double %) 1e-6) @w)) epoch)))
        (let [preds (mapv #(+ (dot @w %) @b) Xte)
              zeros (count (filter #(< (Math/abs ^double %) 1e-6) @w))]
          (ml/log-metrics {:r2 (r-squared yte preds) :rmse (rmse yte preds)
                           :zero-coefficients zeros :sparsity-ratio (/ zeros 6.0)})
          (ml/log-artifact "model" {:type :lasso :w @w :b @b :lambda lambda})))
      (run-id!))))

;; =====================================================================
;; 12. Decision tree regression — leaf count, max depth
;; =====================================================================

(defn uc12-decision-tree-regression []
  (let [{:keys [X y]} (gen-regression 100 2)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "12-decision-tree-regression"
                  :tags {:category "regression"}}
      (ml/log-params {:algorithm "decision-tree-regression" :max-depth 3})
      ;; Build a simple recursive tree (depth-limited)
      (letfn [(build [xs ys depth]
                (if (or (zero? depth) (<= (count xs) 2))
                  {:leaf true :value (/ (reduce + ys) (max (count ys) 1))}
                  (let [best (apply min-key
                                    (fn [{:keys [mse-val]}] (or mse-val 1e20))
                                    (for [f (range 2)
                                          t (distinct (map #(nth % f) xs))]
                                      (let [li (filter #(<= (nth (xs %) f) t) (range (count xs)))
                                            ri (filter #(>  (nth (xs %) f) t) (range (count xs)))]
                                        (if (or (empty? li) (empty? ri))
                                          {:mse-val 1e20}
                                          (let [ly (mapv ys li) ry (mapv ys ri)
                                                lm (/ (reduce + ly) (count ly))
                                                rm (/ (reduce + ry) (count ry))]
                                            {:feature f :threshold t
                                             :mse-val (+ (mse ly (repeat (count ly) lm))
                                                         (mse ry (repeat (count ry) rm)))
                                             :li li :ri ri})))))]
                    (if (:li best)
                      {:feature (:feature best) :threshold (:threshold best)
                       :left  (build (mapv xs (:li best)) (mapv ys (:li best)) (dec depth))
                       :right (build (mapv xs (:ri best)) (mapv ys (:ri best)) (dec depth))}
                      {:leaf true :value (/ (reduce + ys) (count ys))}))))
              (predict-tree [tree x]
                (if (:leaf tree)
                  (:value tree)
                  (if (<= (nth x (:feature tree)) (:threshold tree))
                    (predict-tree (:left tree) x)
                    (predict-tree (:right tree) x))))
              (count-leaves [tree]
                (if (:leaf tree) 1 (+ (count-leaves (:left tree)) (count-leaves (:right tree)))))]
        (let [tree  (build (vec Xtr) (vec ytr) 3)
              preds (mapv #(predict-tree tree %) Xte)]
          (ml/log-metrics {:r2 (r-squared yte preds) :rmse (rmse yte preds)
                           :leaf-count (count-leaves tree) :max-depth 3})
          (ml/log-artifact "model" {:type :decision-tree-regression :tree tree})))
      (run-id!))))

;; =====================================================================
;; 13. K-means clustering — inertia curve, cluster sizes
;; =====================================================================

(defn uc13-kmeans []
  (let [{:keys [X]} (gen-blobs 60 3 2 :spread 0.5)
        k 3]
    (ml/with-run {:experiment "13-kmeans" :tags {:category "clustering"}}
      (ml/log-params {:algorithm "kmeans-lloyd" :k k :max-iter 30})
      (let [centroids (atom (vec (take k (shuffle X))))]
        (dotimes [iter 30]
          (let [assignments (mapv (fn [x] (apply min-key
                                                 #(euclidean-dist x (nth @centroids %))
                                                 (range k)))
                                  X)
                inertia (reduce + (map (fn [x a] (let [d (euclidean-dist x (nth @centroids a))]
                                                   (* d d)))
                                       X assignments))
                new-c (mapv (fn [c]
                              (let [pts (map first (filter #(= c (second %)) (map vector X assignments)))]
                                (if (seq pts)
                                  (mapv (fn [j] (/ (reduce + (map #(nth % j) pts)) (count pts)))
                                        (range 2))
                                  (nth @centroids c))))
                            (range k))]
            (ml/log-metric :inertia inertia iter)
            (when (= new-c @centroids)
              (ml/log-metric :converged-at-iter iter))
            (reset! centroids new-c)))
        (let [final-assign (mapv (fn [x] (apply min-key
                                                #(euclidean-dist x (nth @centroids %))
                                                (range k)))
                                 X)
              sizes (frequencies final-assign)]
          (doseq [[c n] sizes]
            (ml/log-metric (keyword (str "cluster-" c "-size")) (double n)))
          (ml/log-artifact "model" {:type :kmeans :centroids @centroids})))
      (run-id!))))

;; =====================================================================
;; 14. Density-based clustering (simplified DBSCAN)
;; =====================================================================

(defn uc14-dbscan []
  (let [{:keys [X]} (gen-blobs 40 3 2 :spread 0.3)
        X (into X (repeatedly 10 #(vector (* 10 (gauss)) (* 10 (gauss))))) ;; noise
        eps 2.0
        min-pts 3]
    (ml/with-run {:experiment "14-dbscan" :tags {:category "clustering"}}
      (ml/log-params {:algorithm "dbscan" :eps eps :min-pts min-pts
                      :n-points (count X)})
      (let [neighbors (fn [i] (filter (fn [j] (and (not= i j)
                                                   (< (euclidean-dist (X i) (X j)) eps)))
                                      (range (count X))))
            labels (atom (vec (repeat (count X) -1)))
            cluster (atom 0)]
        (doseq [i (range (count X))]
          (when (= -1 (@labels i))
            (let [nbrs (neighbors i)]
              (if (< (count nbrs) min-pts)
                (swap! labels assoc i -1) ;; noise
                (let [c @cluster
                      q (atom (vec nbrs))]
                  (swap! cluster inc)
                  (swap! labels assoc i c)
                  (while (seq @q)
                    (let [j (first @q)]
                      (swap! q subvec 1)
                      (when (= -1 (@labels j))
                        (swap! labels assoc j c)
                        (let [j-nbrs (neighbors j)]
                          (when (>= (count j-nbrs) min-pts)
                            (swap! q into j-nbrs)))))))))))
        (let [n-noise (count (filter #(= -1 %) @labels))
              n-clusters @cluster]
          (ml/log-metrics {:n-clusters (double n-clusters)
                           :noise-points (double n-noise)
                           :noise-ratio (/ n-noise (double (count X)))})
          (ml/log-artifact "labels" @labels)))
      (run-id!))))

;; =====================================================================
;; 15. PCA (power iteration) — explained variance
;; =====================================================================

(defn uc15-pca []
  (let [{:keys [X]} (gen-blobs 100 2 5 :spread 2.0)
        n (count X) d 5
        ;; Center the data
        means (mapv (fn [j] (/ (reduce + (map #(nth % j) X)) n)) (range d))
        Xc (mapv (fn [x] (mapv - x means)) X)]
    (ml/with-run {:experiment "15-pca" :tags {:category "dimensionality-reduction"}}
      (ml/log-params {:algorithm "pca-power-iteration" :n-components 2 :n-features d})
      ;; Power iteration for top eigenvector
      (let [cov (fn [Xc]
                  (let [n (count Xc)]
                    (mapv (fn [i]
                            (mapv (fn [j]
                                    (/ (reduce + (map (fn [x] (* (nth x i) (nth x j))) Xc)) n))
                                  (range d)))
                          (range d))))
            C (cov Xc)
            power-iter (fn [C]
                         (loop [v (vec (repeat d 1.0)) iter 0]
                           (if (= iter 100)
                             v
                             (let [Cv (mapv (fn [row] (dot row v)) C)
                                   norm (Math/sqrt (reduce + (map #(* % %) Cv)))]
                               (recur (mapv #(/ % norm) Cv) (inc iter))))))
            pc1 (power-iter C)
            ;; Project onto PC1 and compute variance explained
            projections (mapv #(dot % pc1) Xc)
            total-var (reduce + (map (fn [x] (reduce + (map #(* % %) x))) Xc))
            pc1-var (* (count Xc) (/ (reduce + (map #(* % %) projections)) (count Xc)))]
        (ml/log-metrics {:explained-variance-ratio (/ pc1-var (max total-var 1e-10))
                         :total-variance total-var
                         :pc1-variance pc1-var})
        (ml/log-artifact "components" {:pc1 pc1 :mean means}))
      (run-id!))))

;; =====================================================================
;; 16. Anomaly detection (z-score)
;; =====================================================================

(defn uc16-anomaly-detection []
  (let [n-normal 100
        n-anomaly 10
        X-normal (vec (repeatedly n-normal #(vector (+ 5 (gauss)) (+ 5 (gauss)))))
        X-anomaly (vec (repeatedly n-anomaly #(vector (* 15 (rand-val)) (* 15 (rand-val)))))
        X (into X-normal X-anomaly)
        y-true (into (vec (repeat n-normal 0)) (vec (repeat n-anomaly 1)))
        threshold 2.5]
    (ml/with-run {:experiment "16-anomaly-detection"
                  :tags {:category "anomaly-detection"}}
      (ml/log-params {:algorithm "z-score" :threshold threshold
                      :n-normal n-normal :n-anomaly n-anomaly})
      (let [means (mapv (fn [j] (/ (reduce + (map #(nth % j) X-normal)) n-normal)) (range 2))
            stds  (mapv (fn [j]
                          (let [m (nth means j)]
                            (Math/sqrt (/ (reduce + (map #(let [e (- (nth % j) m)] (* e e)) X-normal))
                                          n-normal))))
                        (range 2))
            scores (mapv (fn [x]
                           (Math/sqrt (reduce + (map-indexed
                                                 (fn [j xj]
                                                   (let [z (/ (- xj (nth means j)) (max (nth stds j) 1e-10))]
                                                     (* z z)))
                                                 x))))
                         X)
            preds (mapv #(if (> % threshold) 1 0) scores)
            tp (count (filter (fn [[t p]] (and (= 1 t) (= 1 p))) (map vector y-true preds)))
            fp (count (filter (fn [[t p]] (and (= 0 t) (= 1 p))) (map vector y-true preds)))
            fn_ (count (filter (fn [[t p]] (and (= 1 t) (= 0 p))) (map vector y-true preds)))
            precision (/ tp (max (+ tp fp) 1))
            recall (/ tp (max (+ tp fn_) 1))]
        (ml/log-metrics {:precision (double precision) :recall (double recall)
                         :f1 (double (if (pos? (+ precision recall))
                                       (/ (* 2 precision recall) (+ precision recall))
                                       0.0))
                         :anomalies-detected (double (count (filter #(= 1 %) preds)))})
        (ml/log-artifact "model" {:type :z-score-anomaly :means means :stds stds
                                  :threshold threshold}))
      (run-id!))))

;; =====================================================================
;; 17. K-fold cross-validation — child runs per fold
;; =====================================================================

(defn uc17-cross-validation []
  ;; v0.4: create experiment with metadata
  (ml/create-experiment! "17-cross-validation"
                         {:description "K-fold CV with logistic regression"
                          :owner "showcase"})
  (let [{:keys [X y]} (gen-blobs 100 2 3)
        k-folds 5
        fold-size (quot (count X) k-folds)]
    (ml/with-run {:experiment "17-cross-validation"
                  :tags {:category "model-management"}}
      (ml/log-params {:algorithm "logistic-regression" :k-folds k-folds :lr 0.1
                      :epochs 50 :total-samples (count X)})
      (let [accs (vec
                  (for [fold (range k-folds)]
                    (let [test-idx (set (range (* fold fold-size) (* (inc fold) fold-size)))
                          Xte (mapv X (filter test-idx (range (count X))))
                          yte (mapv y (filter test-idx (range (count X))))
                          Xtr (mapv X (remove test-idx (range (count X))))
                          ytr (mapv y (remove test-idx (range (count X))))
                          w (atom (vec (repeat 3 0.0)))
                          b (atom 0.0)]
                      (ml/with-run {:experiment "17-cross-validation"
                                    :name (str "fold-" fold)
                                    :tags {:fold (str fold)}}
                        (dotimes [_ 50]
                          (doseq [[x yi] (map vector Xtr (mapv #(if (zero? %) 0 1) ytr))]
                            (let [p (sigmoid (+ (dot @w x) @b)) e (- p yi)]
                              (swap! w #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
                              (swap! b - (* 0.1 e)))))
                        (let [preds (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte)
                              acc (accuracy (mapv #(if (zero? %) 0 1) yte) preds)]
                          (ml/log-metrics {:fold-accuracy acc :fold fold})
                          acc)))))]
        (ml/log-metrics {:mean-accuracy (/ (reduce + accs) (count accs))
                         :std-accuracy (let [m (/ (reduce + accs) (count accs))]
                                         (Math/sqrt (/ (reduce + (map #(let [e (- % m)] (* e e)) accs))
                                                       (count accs))))}))
      (run-id!))))

;; =====================================================================
;; 18. Hyperparameter grid search — nested runs
;; =====================================================================

(defn uc18-grid-search []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "18-grid-search"
                  :tags {:category "model-management"}}
      (ml/log-params {:algorithm "logistic-regression" :search-type "grid"})
      (let [results
            (vec
             (for [lr [0.01 0.05 0.1 0.5]
                   epochs [30 60 100]]
               (ml/with-run {:experiment "18-grid-search"
                             :name (str "lr=" lr "-ep=" epochs)
                             :tags {:lr (str lr) :epochs (str epochs)}}
                 (let [w (atom (vec (repeat 3 0.0)))
                       b (atom 0.0)]
                   (ml/log-params {:lr lr :epochs epochs})
                   (dotimes [_ epochs]
                     (doseq [[x yi] (map vector Xtr (mapv #(if (zero? %) 0 1) ytr))]
                       (let [p (sigmoid (+ (dot @w x) @b)) e (- p yi)]
                         (swap! w #(mapv (fn [wi xi] (- wi (* lr e xi))) % x))
                         (swap! b - (* lr e)))))
                   (let [preds (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte)
                         acc (accuracy (mapv #(if (zero? %) 0 1) yte) preds)]
                     (ml/log-metric :accuracy acc)
                     {:lr lr :epochs epochs :accuracy acc :run-id (run-id!)})))))
            best (apply max-key :accuracy results)]
        (ml/log-params {:best-lr (:lr best) :best-epochs (:epochs best)
                        :grid-size (count results)})
        (ml/log-metrics {:best-accuracy (:accuracy best)}))
      (run-id!))))

;; =====================================================================
;; 19. Model comparison — same data, 3 algorithms
;; =====================================================================

(defn uc19-model-comparison []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)]
    (ml/with-run {:experiment "19-model-comparison"
                  :tags {:category "model-management"}}
      (ml/log-params {:n-train (count Xtr) :n-test (count Xte) :n-classes 2})

      ;; Logistic regression
      (let [w (atom (vec (repeat 3 0.0))) b (atom 0.0)]
        (dotimes [_ 100]
          (doseq [[x yi] (map vector Xtr (mapv #(if (zero? %) 0 1) ytr))]
            (let [p (sigmoid (+ (dot @w x) @b)) e (- p yi)]
              (swap! w #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
              (swap! b - (* 0.1 e)))))
        (let [preds (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte)]
          (ml/log-metric :acc-logistic (accuracy (mapv #(if (zero? %) 0 1) yte) preds))))

      ;; KNN
      (let [preds (mapv (fn [x]
                          (let [dists (map-indexed (fn [i xt] [(euclidean-dist x xt) i]) Xtr)
                                top-k (take 5 (sort-by first dists))]
                            (key (apply max-key val
                                        (frequencies (map (fn [[_ i]] (ytr i)) top-k))))))
                        Xte)]
        (ml/log-metric :acc-knn (accuracy yte preds)))

      ;; Naive Bayes (simplified)
      (let [classes [0 1]
            stats (into {} (for [c classes]
                             (let [xs (mapv first (filter #(= c (second %)) (map vector Xtr ytr)))]
                               [c {:mean (mapv (fn [j] (/ (reduce + (map #(nth % j) xs)) (max (count xs) 1)))
                                               (range 3))
                                   :n (count xs)}])))
            preds (mapv (fn [x]
                          (apply max-key
                                 (fn [c] (- (reduce + (map (fn [j] (- (Math/abs (- (nth x j)
                                                                                   (nth (:mean (stats c)) j)))))
                                                           (range 3)))))
                                 classes))
                        Xte)]
        (ml/log-metric :acc-naive-bayes (accuracy yte preds)))

      (ml/log-params {:algorithms ["logistic-regression" "knn" "naive-bayes"]})
      (run-id!)))
  ;; v0.4: tag the most recent run with a note after completion
  (let [rid (:id (ml/last-run))]
    (ml/add-tag! rid :winner "check-metrics")
    rid))

;; =====================================================================
;; 20. Ensemble voting — component model refs
;; =====================================================================

(defn uc20-ensemble-voting []
  (let [{:keys [X y]} (gen-blobs 80 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        ytr-bin (mapv #(if (zero? %) 0 1) ytr)
        yte-bin (mapv #(if (zero? %) 0 1) yte)]
    (ml/with-run {:experiment "20-ensemble-voting"
                  :tags {:category "model-management" :ensemble "voting"}}
      (ml/log-params {:algorithm "majority-voting" :n-models 3})
      ;; 3 logistic models with different init
      (let [models (vec (for [i (range 3)]
                          (let [w (atom (mapv (fn [_] (* 0.1 (gauss))) (range 3)))
                                b (atom (* 0.1 (gauss)))]
                            (dotimes [_ 80]
                              (doseq [[x yi] (map vector Xtr ytr-bin)]
                                (let [p (sigmoid (+ (dot @w x) @b)) e (- p yi)]
                                  (swap! w #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
                                  (swap! b - (* 0.1 e)))))
                            (let [preds (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte)
                                  acc (accuracy yte-bin preds)]
                              (ml/log-metric (keyword (str "model-" i "-accuracy")) acc)
                              {:w @w :b @b :accuracy acc}))))
            ensemble-preds (mapv (fn [x]
                                   (let [votes (mapv (fn [m]
                                                       (if (> (sigmoid (+ (dot (:w m) x) (:b m))) 0.5)
                                                         1 0))
                                                     models)]
                                     (if (> (reduce + votes) 1) 1 0)))
                                 Xte)]
        (ml/log-metrics {:ensemble-accuracy (accuracy yte-bin ensemble-preds)})
        (ml/log-artifact "ensemble" {:type :voting-ensemble :models models}))
      (run-id!))))

;; =====================================================================
;; 21. Feature importance (permutation)
;; =====================================================================

(defn uc21-feature-importance []
  (let [{:keys [X y]} (gen-blobs 80 2 4)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        ytr-bin (mapv #(if (zero? %) 0 1) ytr)
        yte-bin (mapv #(if (zero? %) 0 1) yte)
        w (atom (vec (repeat 4 0.0)))
        b (atom 0.0)]
    ;; Train
    (dotimes [_ 100]
      (doseq [[x yi] (map vector Xtr ytr-bin)]
        (let [p (sigmoid (+ (dot @w x) @b)) e (- p yi)]
          (swap! w #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
          (swap! b - (* 0.1 e)))))
    (ml/with-run {:experiment "21-feature-importance"
                  :tags {:category "model-management"}}
      (ml/log-params {:algorithm "permutation-importance" :n-features 4})
      (let [base-acc (accuracy yte-bin
                               (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0)) Xte))
            importances
            (mapv (fn [feat]
                    (let [Xte-perm (mapv (fn [x]
                                           (let [perm-val (nth (Xte (rand-int (count Xte))) feat)]
                                             (assoc x feat perm-val)))
                                         Xte)
                          perm-acc (accuracy yte-bin
                                             (mapv (fn [x] (if (> (sigmoid (+ (dot @w x) @b)) 0.5) 1 0))
                                                   Xte-perm))]
                      {:feature feat :importance (- base-acc perm-acc)}))
                  (range 4))]
        (ml/log-metrics {:baseline-accuracy base-acc})
        (doseq [{:keys [feature importance]} importances]
          (ml/log-metric (keyword (str "importance-feat-" feature)) importance))
        (ml/log-artifact "feature-importances"
                         (sort-by :importance > importances))))
    (run-id!)))

;; =====================================================================
;; 22. Time series — exponential smoothing
;; =====================================================================

(defn uc22-time-series []
  (let [n 100
        ;; Generate seasonal + trend data
        data (mapv (fn [t]
                     (+ (* 0.1 t)
                        (* 5 (Math/sin (/ (* 2 Math/PI t) 12)))
                        (* 2 (gauss))))
                   (range n))
        train (subvec data 0 80)
        test  (subvec data 80)]
    (ml/with-run {:experiment "22-time-series"
                  :tags {:category "time-series"}}
      (ml/log-params {:algorithm "exponential-smoothing" :alpha 0.3 :n-train 80
                      :n-test 20 :series-length n})
      ;; Exponential smoothing
      (let [alpha 0.3
            smoothed (reduce (fn [acc y]
                               (conj acc (+ (* alpha y) (* (- 1 alpha) (peek acc)))))
                             [(first train)]
                             (rest train))
            forecast-val (peek smoothed)
            forecast (vec (repeat (count test) forecast-val))]
        ;; Log per-step forecast vs actual
        (doseq [i (range (count test))]
          (ml/log-metric :actual (nth test i) (+ 80 i))
          (ml/log-metric :forecast forecast-val (+ 80 i)))
        (ml/log-metrics {:mae (mae test forecast) :rmse (rmse test forecast)
                         :mse (mse test forecast)})
        (ml/log-artifact "model" {:type :exponential-smoothing :alpha alpha
                                  :last-smoothed forecast-val}))
      (run-id!))))

;; =====================================================================
;; 23. Text classification — bag-of-words + naive bayes
;; =====================================================================

(defn uc23-text-classification []
  (let [docs [["great movie loved it"          :positive]
              ["wonderful film excellent"      :positive]
              ["amazing performance brilliant" :positive]
              ["best film ever seen"           :positive]
              ["really enjoyed the show"       :positive]
              ["fantastic acting superb"       :positive]
              ["terrible movie awful"          :negative]
              ["worst film boring"             :negative]
              ["horrible waste of time"        :negative]
              ["bad acting disappointing"      :negative]
              ["dull and uninteresting"        :negative]
              ["poor script terrible"          :negative]]
        tokenize #(clojure.string/split % #"\s+")
        vocab (vec (distinct (mapcat (comp tokenize first) docs)))
        bow (fn [text] (let [tokens (set (tokenize text))]
                         (mapv #(if (tokens %) 1 0) vocab)))
        X (mapv (comp bow first) docs)
        y (mapv second docs)
        [Xtr Xte ytr yte] (train-test-split X y 0.7)]
    (ml/with-run {:experiment "23-text-classification"
                  :tags {:category "nlp"}}
      (ml/log-params {:algorithm "bag-of-words-naive-bayes"
                      :vocab-size (count vocab)
                      :n-docs (count docs)
                      :n-train (count Xtr) :n-test (count Xte)})
      (let [classes [:positive :negative]
            class-data (fn [c] (mapv first (filter #(= c (second %)) (map vector Xtr ytr))))
            stats (into {} (for [c classes]
                             (let [xs (class-data c)
                                   n (count xs)]
                               [c {:prior (/ n (double (count Xtr)))
                                   :word-probs (mapv (fn [j]
                                                       (/ (+ 1.0 (reduce + (map #(nth % j) xs)))
                                                          (+ 2.0 n)))
                                                     (range (count vocab)))}])))
            predict (fn [x]
                      (apply max-key
                             (fn [c]
                               (+ (Math/log (:prior (stats c)))
                                  (reduce + (map-indexed
                                             (fn [j xj]
                                               (* xj (Math/log (nth (:word-probs (stats c)) j))))
                                             x))))
                             classes))
            preds (mapv predict Xte)
            acc (accuracy yte preds)]
        (ml/log-metrics {:accuracy acc})
        (ml/log-artifact "model" {:type :text-naive-bayes :stats stats :vocab vocab}))
      (run-id!))))

;; =====================================================================
;; 24. Recommendation — item similarity (cosine)
;; =====================================================================

(defn- cosine-sim [a b]
  (let [d (dot a b)
        na (Math/sqrt (reduce + (map #(* % %) a)))
        nb (Math/sqrt (reduce + (map #(* % %) b)))]
    (if (or (zero? na) (zero? nb)) 0.0 (/ d (* na nb)))))

(defn uc24-recommendation []
  (let [n-users 20
        n-items 10
        ;; Sparse user-item rating matrix (0 = unrated)
        ratings (vec (repeatedly n-users
                                 #(vec (repeatedly n-items
                                                   (fn [] (if (> (rand-val) 0.6)
                                                            (inc (rand-int 5)) 0))))))
        ;; Item similarity matrix
        item-vecs (mapv (fn [j] (mapv #(nth % j) ratings)) (range n-items))
        sim-matrix (mapv (fn [i]
                           (mapv (fn [j] (cosine-sim (item-vecs i) (item-vecs j)))
                                 (range n-items)))
                         (range n-items))]
    (ml/with-run {:experiment "24-recommendation"
                  :tags {:category "recommendation"}}
      (ml/log-params {:algorithm "item-based-collaborative-filtering"
                      :n-users n-users :n-items n-items
                      :sparsity (double (/ (count (filter zero? (flatten ratings)))
                                           (* n-users n-items)))})
      ;; Predict missing ratings for user 0
      (let [user (first ratings)
            predictions (mapv (fn [j]
                                (if (pos? (nth user j))
                                  (nth user j) ;; already rated
                                  (let [rated (filter #(pos? (nth user %)) (range n-items))
                                        num (reduce + (map #(* (nth (sim-matrix j) %) (nth user %)) rated))
                                        den (reduce + (map #(Math/abs (nth (sim-matrix j) %)) rated))]
                                    (if (zero? den) 0.0 (/ num den)))))
                              (range n-items))
            top-n (take 3 (sort-by (fn [j] (- (nth predictions j)))
                                   (filter #(zero? (nth user %)) (range n-items))))]
        (ml/log-metrics {:top-1-predicted-rating (nth predictions (first top-n))
                         :avg-similarity (/ (reduce + (flatten sim-matrix))
                                            (* n-items n-items))})
        (ml/log-artifact "model" {:type :item-cf :sim-matrix sim-matrix
                                  :top-recommendations top-n}))
      (run-id!))))

;; =====================================================================
;; 25. A/B model evaluation — paired t-test, p-value
;; =====================================================================

(defn uc25-ab-evaluation []
  (let [{:keys [X y]} (gen-blobs 100 2 3)
        [Xtr Xte ytr yte] (train-test-split X y 0.8)
        ytr-bin (mapv #(if (zero? %) 0 1) ytr)
        yte-bin (mapv #(if (zero? %) 0 1) yte)
        ;; Model A: logistic, lr=0.1, 100 epochs (strong)
        wa (atom (vec (repeat 3 0.0))) ba (atom 0.0)
        ;; Model B: logistic, lr=0.01, 5 epochs (weak — undertrained)
        wb (atom (vec (repeat 3 0.0))) bb (atom 0.0)]
    ;; Train model A (100 epochs)
    (dotimes [_ 100]
      (doseq [[x yi] (map vector Xtr ytr-bin)]
        (let [p (sigmoid (+ (dot @wa x) @ba))
              e (- p yi)]
          (swap! wa #(mapv (fn [wi xi] (- wi (* 0.1 e xi))) % x))
          (swap! ba - (* 0.1 e)))))
    ;; Train model B (only 5 epochs — intentionally undertrained)
    (dotimes [_ 5]
      (doseq [[x yi] (map vector Xtr ytr-bin)]
        (let [p (sigmoid (+ (dot @wb x) @bb))
              e (- p yi)]
          (swap! wb #(mapv (fn [wi xi] (- wi (* 0.01 e xi))) % x))
          (swap! bb - (* 0.01 e)))))
    (ml/with-run {:experiment "25-ab-evaluation"
                  :tags {:category "evaluation"}}
      (let [preds-a (mapv (fn [x] (if (> (sigmoid (+ (dot @wa x) @ba)) 0.5) 1 0)) Xte)
            preds-b (mapv (fn [x] (if (> (sigmoid (+ (dot @wb x) @bb)) 0.5) 1 0)) Xte)
            ;; Per-sample correctness
            correct-a (mapv #(if (= %1 %2) 1 0) yte-bin preds-a)
            correct-b (mapv #(if (= %1 %2) 1 0) yte-bin preds-b)
            diffs (mapv - correct-a correct-b)
            n (count diffs)
            mean-d (/ (reduce + diffs) (double n))
            std-d (Math/sqrt (/ (reduce + (map #(let [e (- % mean-d)] (* e e)) diffs))
                                (max (dec n) 1)))
            t-stat (if (> std-d 1e-10) (/ mean-d (/ std-d (Math/sqrt n))) 0.0)
            ;; Approximate p-value (two-tailed, df > 30 → normal approx)
            p-value (* 2 (- 1 (sigmoid (* 1.7 (Math/abs t-stat)))))
            winner (cond (and (< p-value 0.05) (pos? mean-d)) "model-a"
                         (and (< p-value 0.05) (neg? mean-d)) "model-b"
                         :else "no-significant-difference")]
        (ml/log-params {:model-a-lr 0.1 :model-b-lr 0.01 :n-test n
                        :significance-level 0.05})
        (ml/log-metrics {:accuracy-a (accuracy yte-bin preds-a)
                         :accuracy-b (accuracy yte-bin preds-b)
                         :mean-diff mean-d
                         :t-statistic t-stat
                         :p-value p-value})
        (ml/log-param :winner winner)
        (ml/log-artifact "ab-results" {:preds-a preds-a :preds-b preds-b
                                       :t-stat t-stat :p-value p-value
                                       :winner winner})
        ;; v0.4: markdown note with math
        (ml/set-note! (run-id!)
                      (str "## A/B Evaluation\n\n"
                           "**Winner**: " winner "\n\n"
                           "Paired t-test: $t = " (format "%.3f" t-stat)
                           "$, $p = " (format "%.4f" p-value) "$\n\n"
                           (if (< p-value 0.05)
                             "Result is **statistically significant** at $\\alpha = 0.05$."
                             "Result is **not significant** at $\\alpha = 0.05$."))))
      (run-id!))))

;; =====================================================================
;; Main — run all 25 and print summary
;; =====================================================================

(def ^:private use-cases
  [["01 Binary classification"       uc01-binary-classification]
   ["02 Multi-class classification"  uc02-multiclass-classification]
   ["03 Decision stump"              uc03-decision-stump]
   ["04 Random forest"               uc04-random-forest]
   ["05 K-nearest neighbors"         uc05-knn]
   ["06 Naive Bayes"                 uc06-naive-bayes]
   ["07 SVM (linear)"                uc07-svm]
   ["08 Linear regression"           uc08-linear-regression]
   ["09 Polynomial regression"       uc09-polynomial-regression]
   ["10 Ridge regression (L2)"       uc10-ridge-regression]
   ["11 Lasso (L1)"                  uc11-lasso]
   ["12 Decision tree regression"    uc12-decision-tree-regression]
   ["13 K-means clustering"          uc13-kmeans]
   ["14 DBSCAN clustering"           uc14-dbscan]
   ["15 PCA"                         uc15-pca]
   ["16 Anomaly detection"           uc16-anomaly-detection]
   ["17 Cross-validation"            uc17-cross-validation]
   ["18 Hyperparameter grid search"  uc18-grid-search]
   ["19 Model comparison"            uc19-model-comparison]
   ["20 Ensemble voting"             uc20-ensemble-voting]
   ["21 Feature importance"          uc21-feature-importance]
   ["22 Time series"                 uc22-time-series]
   ["23 Text classification"         uc23-text-classification]
   ["24 Recommendation"              uc24-recommendation]
   ["25 A/B evaluation"              uc25-ab-evaluation]])

(defn run-all!
  "Run all 25 use cases. Returns a vector of `[name run-id]` pairs."
  []
  (vec (for [[uc-name uc-fn] use-cases]
         (do (print (str "  " uc-name "... "))
             (flush)
             (let [rid (uc-fn)]
               (println "done")
               [uc-name rid])))))

(defn -main [& _args]
  (println "Running 25 ML use cases with chachaml tracking:\n")
  (let [results (run-all!)]
    (println (str "\nAll 25 done. " (count results) " runs created.\n"))
    (repl/runs-table {:limit 30})
    (println "\nRegistered models:")
    (doseq [m (reg/models)]
      (println (str "  " (:name m) " — "
                    (count (reg/model-versions (:name m))) " version(s)")))
    ;; v0.4: best-run + export
    (println "\nBest run by accuracy (experiment 01):")
    (when-let [b (ml/best-run {:experiment "01-binary-classification"
                               :metric :accuracy})]
      (println (str "  " (:id b) " accuracy metric logged")))
    (println (str "\nExported " (count (ml/export-runs {:limit 5}))
                  " runs as flat records (first 5)"))))

