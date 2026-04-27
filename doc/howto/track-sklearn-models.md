# Track sklearn models

Use libpython-clj2 to call sklearn from Clojure, with chachaml
auto-tracking the params, metrics, and trained model.

## Goal

Train a sklearn model, evaluate it, save the trained estimator as a
chachaml artifact, register it in the model registry — all from a
Clojure REPL.

## Prerequisites

- Python 3 (3.10+ recommended). `python3 --version` should work.
- scikit-learn: `pip install scikit-learn` (in a virtualenv if you
  prefer).
- libpython-clj2 will pick up whatever `python3` is on `PATH` at JVM
  start. If you have multiple Pythons, set
  `PYTHON_LIBRARY_PATH` (or `LD_LIBRARY_PATH` / `DYLD_LIBRARY_PATH`)
  to disambiguate.
- The `:python` alias in `deps.edn` (already there).

## Steps

### 1. Confirm libpython-clj2 can reach sklearn

```bash
clojure -A:python -e \
  '(require (quote [libpython-clj2.python :as py])
            (quote [libpython-clj2.require :refer [require-python]]))
   (require-python (quote [sklearn :as sk]))
   (println "sklearn version:" (py/get-attr sk "__version__"))'
```

You should see something like `sklearn version: 1.4.2`. If not, see
[Troubleshooting](../TROUBLESHOOTING.md#libpython-clj2-native-library-mismatch).

### 2. Train and track

```clojure
(require '[chachaml.core :as ml]
         '[chachaml.interop.sklearn :as sk]
         '[libpython-clj2.python :refer [call-attr]]
         '[libpython-clj2.require :refer [require-python]])

(require-python '[sklearn.datasets :as datasets])
(require-python '[sklearn.linear_model :as lm])
(require-python '[sklearn.model_selection :as ms])

(let [iris  (datasets/load_iris)
      X     (call-attr iris "data")
      y     (call-attr iris "target")
      [X-train X-test y-train y-test]
        (call-attr ms/train_test_split X y :test_size 0.2 :random_state 42)]
  (ml/with-run {:experiment "sklearn-iris"
                :name       "logistic-regression"
                :tags       {:framework "sklearn"}}
    (ml/log-params {:model "LogisticRegression" :max-iter 200})
    (sk/train-and-evaluate!
     (lm/LogisticRegression :max_iter 200)
     X-train y-train X-test y-test
     :register-as "iris-classifier"
     :stage       :staging)))
```

`train-and-evaluate!`:

- Calls the sklearn estimator's `.fit(X, y)`, timing it.
- Calls `.predict(X-test)`.
- Computes accuracy / R² depending on the task type.
- Logs `:training-time-s`, `:accuracy`, all sklearn params via
  `extract-params`, and an `:model` artifact containing the fitted
  estimator (pickled via nippy + pickle).
- (When `:register-as` is set) registers it as a new version of that
  model name at `:staging`.

### 3. Verify

```clojure
(ml/last-run)
;; => params include lr's settings; metrics include :accuracy

(require '[chachaml.registry :as reg])
(reg/model-versions "iris-classifier")
;; => [{:version 1 :stage :staging ...}]

(reg/load-model "iris-classifier" :staging)
;; => the pickled sklearn estimator, ready to .predict on new data
```

### 4. Promote to production

```clojure
(reg/promote! "iris-classifier" 1 :production)
;; demotes any previous prod version to :archived
```

## Lower-level helpers

If `train-and-evaluate!` does too much, drop down a level:

- `(sk/tracked-fit! model X y)` — just `.fit`, with timing logged.
- `(sk/tracked-predict model X)` — just `.predict`.
- `(sk/evaluate! model X-test y-test :task :classification)` — just
  the metric pass.

All assume an ambient `with-run`.

## Troubleshooting

- **Native library mismatch** — see
  [Troubleshooting](../TROUBLESHOOTING.md#libpython-clj2-native-library-mismatch).
  The fastest path is a virtualenv that matches the system Python.
- **`requiring-resolve` errors at compile time** — the interop
  namespace uses `requiring-resolve` so it loads without Python on
  the classpath. If you see resolution errors at *call time*, your
  Python environment isn't set up; verify step 1 first.
- **Pickled artifact won't reload** — make sure the consuming JVM
  has the same sklearn version as the producer. nippy + pickle
  serialises the estimator's class name and version; mismatches
  refuse to deserialise.

## Where to go next

- The full runnable version is at `examples/sklearn_iris.clj`:
  `clojure -M:python:examples -m sklearn-iris`.
- Other framework integrations (PyTorch, JAX) are not built in but
  follow the same pattern: `tracked-fit!`-style wrappers around the
  framework's train loop. Contributions welcome.
