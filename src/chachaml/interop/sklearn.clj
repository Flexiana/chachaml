(ns chachaml.interop.sklearn
  "Tracked wrappers for scikit-learn models via libpython-clj2.

  These functions add chachaml tracking (params, metrics, artifacts)
  around standard sklearn operations. They must be called inside a
  `chachaml.core/with-run` block.

  **libpython-clj2 is optional**: it is resolved at runtime via
  `requiring-resolve`. The namespace compiles and loads without Python
  on the classpath; calls to Python-facing functions will throw a
  clear error if `libpython-clj2` is not available.

  Works with any sklearn-compatible model (LogisticRegression, SVM,
  RandomForest, XGBClassifier, etc.) that supports `.fit()`,
  `.predict()`, and `.get_params()`.

  Usage:

      (require '[chachaml.core :as ml]
               '[chachaml.interop.sklearn :as sk])
      (require-python '[sklearn.linear_model :as lm])

      (ml/with-run {:experiment \"iris\"}
        (let [model (sk/tracked-fit! (lm/LogisticRegression) X-train y-train)
              metrics (sk/evaluate! model X-test y-test)]
          (ml/log-artifact \"model\" model)
          metrics))")

;; --- Python bridge (resolved at runtime) ----------------------------

(defn- resolve-py!
  "Resolve a libpython-clj2 function by qualified symbol. Throws a
  helpful error if the library is not on the classpath."
  [sym]
  (or (requiring-resolve sym)
      (throw (ex-info (str "libpython-clj2 not on classpath; add the "
                           ":python alias to use sklearn interop")
                      {:type ::missing-libpython :symbol sym}))))

(defn- core-fn
  "Resolve a chachaml.core function by keyword name at runtime.
  Avoids compile-time dependency on chachaml.core from this ns."
  [k]
  (requiring-resolve (symbol "chachaml.core" (name k))))

(defn- py-call-attr
  "Call a method on a Python object: `(py-call-attr model \"fit\" X y)`."
  [obj method & args]
  (apply (resolve-py! 'libpython-clj2.python/call-attr) obj method args))

(defn- py->clj
  "Convert a Python object to a Clojure data structure."
  [obj]
  ((resolve-py! 'libpython-clj2.python/->jvm) obj))

;; --- Overridable bridge for testing ---------------------------------

(def ^:dynamic *bridge*
  "The Python bridge used by all interop functions. Override in tests
  with a mock bridge that doesn't require Python.

  Keys:
  - `:fit`        `(fn [model X y] → model)`
  - `:predict`    `(fn [model X] → predictions)`
  - `:get-params` `(fn [model] → clj-map)`
  - `:score`      `(fn [model X y] → float)`"
  nil)

(defn- bridge-fn
  "Return the bridge function for `k`, falling back to the real
  libpython-clj2 implementation."
  [k]
  (or (get *bridge* k)
      (case k
        :fit        (fn [model X y] (py-call-attr model "fit" X y) model)
        :predict    (fn [model X] (py-call-attr model "predict" X))
        :get-params (fn [model] (into {} (py->clj (py-call-attr model "get_params"))))
        :score      (fn [model X y] (py->clj (py-call-attr model "score" X y))))))

;; --- Public API ------------------------------------------------------

(defn extract-params
  "Extract a sklearn model's hyperparameters as a Clojure map.
  Calls `.get_params()` on the model."
  [model]
  ((bridge-fn :get-params) model))

(defn tracked-fit!
  "Fit a sklearn-compatible model on `X`/`y` and log training metadata.

  Logs:
  - `:fit-time-ms` metric (training wall-clock time)
  - Model hyperparameters as params (unless `:log-params? false`)

  Returns the fitted model. Must be called inside `with-run`."
  [model X y & {:keys [log-params?] :or {log-params? true}}]
  (let [start   (System/currentTimeMillis)
        fitted  ((bridge-fn :fit) model X y)
        elapsed (- (System/currentTimeMillis) start)]
    ((core-fn :log-metric) :fit-time-ms elapsed)
    (when log-params?
      ((core-fn :log-params) (extract-params fitted)))
    fitted))

(defn tracked-predict
  "Predict with a sklearn-compatible model, logging prediction time.

  Returns the predictions. Must be called inside `with-run`."
  [model X]
  (let [start  (System/currentTimeMillis)
        preds  ((bridge-fn :predict) model X)
        elapsed (- (System/currentTimeMillis) start)]
    ((core-fn :log-metric) :predict-time-ms elapsed)
    preds))

(defn evaluate!
  "Compute standard metrics and log them on the current run.

  For `:classification` (default): accuracy, and the model's `.score()`.
  For `:regression`: the model's `.score()` (R²).

  `y-pred` may be omitted — the model will predict from `X-test`.

  Returns the metrics map."
  [model X-test y-test & {:keys [task y-pred]
                          :or   {task :classification}}]
  (let [_      (or y-pred (tracked-predict model X-test))
        score  ((bridge-fn :score) model X-test y-test)
        metrics (case task
                  :classification {:score    score
                                   :accuracy score}
                  :regression     {:score score
                                   :r2    score})]
    ((core-fn :log-metrics) metrics)
    metrics))

(defn train-and-evaluate!
  "Full pipeline: fit, predict, evaluate, log artifact, register model.

  Combines `tracked-fit!`, `tracked-predict`, and `evaluate!` into a
  single call. Optionally registers the model in the registry.

  Options:
  - `:task`         `:classification` (default) or `:regression`
  - `:log-params?`  Log model hyperparameters (default `true`)
  - `:artifact-name` Name for the model artifact (default `\"model\"`)
  - `:register-as`  If provided, registers the model with this name
  - `:stage`        Initial stage for the registered model (default `:staging`)

  Returns `{:model :metrics :predictions}`."
  [model X-train y-train X-test y-test
   & {:keys [task log-params? artifact-name register-as stage]
      :or   {task :classification log-params? true
             artifact-name "model" stage :staging}}]
  (let [fitted  (tracked-fit! model X-train y-train
                              :log-params? log-params?)
        preds   (tracked-predict fitted X-test)
        metrics (evaluate! fitted X-test y-test
                           :task task :y-pred preds)]
    ((core-fn :log-artifact) artifact-name fitted)
    (when register-as
      ((requiring-resolve 'chachaml.registry/register-model)
       register-as {:artifact artifact-name :stage stage}))
    {:model       fitted
     :metrics     metrics
     :predictions preds}))
