(ns chachaml.store
  "Store constructor dispatcher.

  `(open opts)` creates the right backend based on `:type`:

      (open {:type :sqlite})                    ; default
      (open {:type :sqlite :path \"my.db\"})
      (open {:type :postgres :jdbc-url \"jdbc:postgresql://...\"})

  The rest of chachaml doesn't care which backend is active — it
  talks through the protocols in `chachaml.store.protocol`."
  (:require [chachaml.store.sqlite :as sqlite]))

(defn open
  "Open a store. Dispatches on `:type` (default `:sqlite`).

  SQLite options: `:path`, `:in-memory?`, `:artifact-dir`
  Postgres options: `:jdbc-url`, `:username`, `:password`,
                    `:artifact-dir`, `:pool-size`"
  ([] (open {:type :sqlite}))
  ([{store-type :type :or {store-type :sqlite} :as opts}]
   (case (keyword store-type)
     :sqlite   (sqlite/open (dissoc opts :type))
     :postgres (let [pg-open (requiring-resolve 'chachaml.store.postgres/open)]
                 (pg-open (dissoc opts :type)))
     (throw (ex-info (str "Unknown store type: " store-type)
                     {:type store-type :opts opts})))))
