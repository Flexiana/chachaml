(ns chachaml.loads-test
  "Baseline test: every public namespace loads.

  Until features land, this is the only signal that the project compiles
  and that kaocha can discover and run a test. As real tests accumulate
  this stays useful as a fast smoke check."
  (:require [chachaml.context]
            [chachaml.core]
            [chachaml.env]
            [chachaml.registry]
            [chachaml.repl]
            [chachaml.schema]
            [chachaml.serialize]
            [chachaml.store.protocol]
            [chachaml.store.sqlite]
            [chachaml.tracking]
            [clojure.test :refer [deftest is]]))

(def ^:private namespaces
  '[chachaml.context
    chachaml.core
    chachaml.env
    chachaml.registry
    chachaml.repl
    chachaml.schema
    chachaml.serialize
    chachaml.store.protocol
    chachaml.store.sqlite
    chachaml.tracking])

(deftest every-namespace-loads
  (doseq [ns-sym namespaces]
    (is (some? (find-ns ns-sym))
        (str "Namespace " ns-sym " did not load"))))
