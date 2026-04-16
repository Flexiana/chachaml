(ns chachaml.schema
  "Malli schemas for the chachaml public API.

  Schemas live here, separate from the API namespaces, so that:

  - The API surface stays uncluttered.
  - Schemas can be required from tests and user code without pulling in
    full API namespaces.
  - We can later opt into runtime instrumentation in dev/test.

  Schemas are added incrementally as the API lands across M2-M6."
  (:require [malli.core :as m]))

(def Status
  "Run status. `:running` until ended; then `:completed` or `:failed`."
  [:enum :running :completed :failed])

(def MetricKey
  "A metric or param key — keyword or non-blank string."
  [:or :keyword [:and :string [:fn #(seq %)]]])

(def Run
  "Public run map shape."
  [:map
   [:id            :string]
   [:experiment    {:optional true} :string]
   [:name          {:optional true} [:maybe :string]]
   [:status        Status]
   [:start-time    :int]
   [:end-time      {:optional true} [:maybe :int]]
   [:error         {:optional true} [:maybe :string]]
   [:tags          {:optional true} [:maybe [:map-of :any :any]]]
   [:env           {:optional true} [:maybe :map]]
   [:parent-run-id {:optional true} [:maybe :string]]])

(def StartRunOpts
  "Options accepted by `start-run!` and `with-run`."
  [:map
   [:experiment {:optional true} :string]
   [:name       {:optional true} [:maybe :string]]
   [:tags       {:optional true} [:maybe :map]]
   [:parent-run-id {:optional true} [:maybe :string]]])

(def Params
  "Params map: any keyword/string key, any EDN-serializable value."
  [:map-of MetricKey :any])

(def Metrics
  "Metrics map: any keyword/string key, numeric value."
  [:map-of MetricKey number?])

(def MetricRow
  "A single metric measurement as returned from the store."
  [:map
   [:key       :keyword]
   [:value     number?]
   [:step      :int]
   [:timestamp :int]])

(def QueryFilters
  "Filters accepted by `(runs filters)`. Unknown keys are ignored."
  [:map
   [:experiment    {:optional true} :string]
   [:status        {:optional true} Status]
   [:name          {:optional true} :string]
   [:parent-run-id {:optional true} :string]
   [:limit         {:optional true} pos-int?]])

(def ArtifactFormat
  "Built-in artifact formats accepted by `log-artifact`."
  [:enum :nippy :edn :bytes :file])

(def Artifact
  "Public artifact metadata as returned from the store."
  [:map
   [:id           :string]
   [:run-id       :string]
   [:name         :string]
   [:path         :string]
   [:content-type {:optional true} :string]
   [:size         {:optional true} :int]
   [:hash         {:optional true} :string]
   [:created-at   :int]])

(defn validate
  "Throw if `value` does not conform to `schema`. Returns `value` on
  success so it can be used inline."
  [schema value]
  (if (m/validate schema value)
    value
    (throw (ex-info "chachaml schema violation"
                    {:schema   schema
                     :value    value
                     :explain  (m/explain schema value)}))))
