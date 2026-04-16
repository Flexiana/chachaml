(ns chachaml.test-helpers
  "Shared test fixtures for chachaml tests.

  `with-fresh-store` is the standard `clojure.test` `:each` fixture
  that binds `chachaml.context/*store*` to a fresh in-memory SQLite
  store for the duration of one test. Use it as:

      (use-fixtures :each h/with-fresh-store)

  Tests inside the namespace can then call `chachaml.core` /
  `chachaml.registry` ops without setup; the store is GC'd at the end."
  (:require [chachaml.context :as ctx]
            [chachaml.store.sqlite :as sqlite]))

(defn with-fresh-store
  "clojure.test :each fixture: binds *store* to a fresh in-memory store."
  [f]
  (binding [ctx/*store* (sqlite/open {:in-memory? true})]
    (f)))
