(ns chachaml.repl
  "REPL-oriented helpers for chachaml.

  Conveniences for ad-hoc exploration: pretty printing for runs and
  models (`inspect`, `runs-table`), and a small `compare-runs` diff.

  These are not required for programmatic use; they print to `*out*`
  and return nil (or, for `compare-runs`, a plain map you can also
  print or feed to `tap>`)."
  (:require [chachaml.core :as ml]
            [chachaml.format :as fmt]
            [chachaml.registry :as reg]
            [clojure.set :as set]
            [clojure.string :as str]))

(defn runs-table
  "Print a one-row-per-run summary of recent runs. Accepts the same
  filters as `chachaml.core/runs`. Returns nil."
  ([] (runs-table {}))
  ([filters]
   (let [rs (ml/runs filters)]
     (if (empty? rs)
       (println "(no runs)")
       (do
         (println (str/join "  "
                            [(fmt/pad "ID"         10)
                             (fmt/pad "EXPERIMENT" 22)
                             (fmt/pad "NAME"       16)
                             (fmt/pad "STATUS"     10)
                             (fmt/pad "STARTED"    20)
                             "DURATION"]))
         (doseq [{:keys [id experiment status start-time end-time]
                  run-name :name} rs]
           (println (str/join "  "
                              [(fmt/pad (or (fmt/short-id id) "")    10)
                               (fmt/pad (or experiment "")       22)
                               (fmt/pad (or run-name "")         16)
                               (fmt/pad (str status)             10)
                               (fmt/pad (fmt/fmt-instant start-time) 20)
                               (if end-time
                                 (fmt/fmt-duration start-time end-time)
                                 "—")])))))
     nil)))

;; --- Run inspection --------------------------------------------------

(defn- summarize-metrics [metrics]
  (->> metrics
       (group-by :key)
       (sort-by key)
       (mapv (fn [[k vs]]
               (let [sorted (sort-by :step vs)]
                 {:key   k
                  :count (count vs)
                  :first (:value (first sorted))
                  :last  (:value (last sorted))})))))

(defn- print-field
  "Print a labelled field if `value` is non-nil."
  [label value]
  (when value
    (println (format "%-13s %s" label value))))

(defn- inspect-run* [run]
  (println (str "== Run " (:id run) " =="))
  (print-field "Experiment:" (:experiment run))
  (print-field "Name:" (:name run))
  (print-field "Status:" (:status run))
  (print-field "Started:" (fmt/fmt-instant (:start-time run)))
  (when (:end-time run)
    (print-field "Duration:" (fmt/fmt-duration (:start-time run) (:end-time run))))
  (print-field "Error:" (:error run))
  (when (seq (:tags run))
    (print-field "Tags:" (pr-str (:tags run))))
  (when-let [git (get-in run [:env :git])]
    (let [sha    (or (:sha git) "—")
          dirty  (if (:dirty? git) " (dirty)" "")
          branch (or (:branch git) "?")]
      (print-field "Git:" (str (subs sha 0 (min 7 (count sha))) dirty " on " branch))))
  (let [params (or (:params run) {})]
    (println)
    (println (format "Params (%d):" (count params)))
    (doseq [[k v] (sort-by (comp str key) params)]
      (println (format "  %s  %s" (fmt/pad (str k) 16) (pr-str v)))))
  (let [summary (summarize-metrics (:metrics run))]
    (println)
    (println (format "Metrics (%d keys, %d total points):"
                     (count summary) (reduce + (map :count summary))))
    (doseq [{k :key n :count v0 :first vN :last} summary]
      (if (= 1 n)
        (println (format "  %s  %s" (fmt/pad (str k) 16) vN))
        (println (format "  %s  %d points, %s → %s"
                         (fmt/pad (str k) 16) n v0 vN)))))
  (let [arts (or (:artifacts run) [])]
    (when (seq arts)
      (println)
      (println (format "Artifacts (%d):" (count arts)))
      (doseq [{art-name :name :keys [size content-type]
               digest :hash} arts]
        (let [sha (fmt/short-id (or digest ""))]
          (println (format "  %s  %s  %s  sha=%s"
                           (fmt/pad art-name 16)
                           (fmt/pad (fmt/size-str (or size 0)) 10)
                           (fmt/pad (or content-type "") 26)
                           sha))))))
  nil)

(defn- inspect-model* [model]
  (let [versions (reg/model-versions (:name model))]
    (println (str "== Model: " (:name model) " =="))
    (when (:description model)
      (println (format "Description: %s" (:description model))))
    (println (format "Created:     %s" (fmt/fmt-instant (:created-at model))))
    (println (format "Versions (%d):" (count versions)))
    (doseq [{:keys [version stage run-id artifact-id description]} versions]
      (println (format "  v%d  %s  run=%s  artifact=%s%s"
                       version
                       (fmt/pad (str stage) 12)
                       (fmt/short-id run-id)
                       (fmt/short-id artifact-id)
                       (if description (str "  // " description) ""))))
    nil))

(defn- inspect-version* [version]
  (println (format "== %s v%d =="
                   (:model-name version) (:version version)))
  (println (format "Stage:       %s" (:stage version)))
  (println (format "Run id:      %s" (:run-id version)))
  (println (format "Artifact id: %s" (:artifact-id version)))
  (println (format "Created:     %s" (fmt/fmt-instant (:created-at version))))
  (when (:description version)
    (println (format "Description: %s" (:description version))))
  nil)

(defn inspect
  "Pretty-print a chachaml entity to `*out*` and return nil.

  Dispatches on input shape:
  - a string is treated as a run id (or, on no match, a model name)
  - a map with `:status` is rendered as a run
  - a map with `:version` is rendered as a model version
  - a map with `:name` (no `:status`) is rendered as a model"
  [x]
  (cond
    (nil? x)
    (println "(nil)")

    (string? x)
    (or (some-> x ml/run inspect-run*)
        (some-> x reg/model inspect-model*)
        (println (format "(no run or model with id/name %s)" x)))

    (and (map? x) (:status x))
    (inspect-run* x)

    (and (map? x) (:version x))
    (inspect-version* x)

    (and (map? x) (:name x))
    (inspect-model* x)

    :else
    (println "(unknown entity)" (pr-str x))))

;; --- Comparing runs --------------------------------------------------

(defn- classify-key
  "Classify a single key across `maps` into same/differ/partial."
  [maps acc k]
  (let [values (mapv #(get % k) maps)]
    (cond
      (apply = values)   (assoc-in acc [:same k] (first values))
      (some nil? values) (assoc-in acc [:partial k] values)
      :else              (assoc-in acc [:differ k] values))))

(defn- diff-maps
  "Categorise keys across `maps` into `:same`, `:differ`, `:partial`."
  [maps]
  (let [all-keys (apply set/union (map (comp set keys) maps))]
    (reduce (partial classify-key maps)
            {:same {} :differ {} :partial {}}
            all-keys)))

(defn compare-runs
  "Compare params and the latest value per metric across `run-ids`.

  Returns a plain map:

      {:runs    [<id> …]
       :params  {:same {…} :differ {…} :partial {…}}
       :metrics {:same {…} :differ {…} :partial {…}}}

  `:differ` and `:partial` map keys to a vector of values aligned to
  `:runs`. `:partial` is for keys missing in at least one run."
  [run-ids]
  (let [run-ids (vec run-ids)
        runs    (mapv ml/run run-ids)
        params  (mapv :params runs)
        metrics (mapv (comp fmt/metric-summary :metrics) runs)]
    {:runs    run-ids
     :params  (diff-maps params)
     :metrics (diff-maps metrics)}))

(defn print-comparison
  "Pretty-print a comparison produced by `compare-runs`."
  [{:keys [runs params metrics]}]
  (println "Comparing" (count runs) "runs:")
  (doseq [[i id] (map-indexed vector runs)]
    (println (format "  [%d] %s" i id)))
  (doseq [[label section] [["Params" params] ["Metrics" metrics]]]
    (println)
    (println (str label ":"))
    (when (seq (:differ section))
      (println "  differ:")
      (doseq [[k vs] (sort-by (comp str key) (:differ section))]
        (println (format "    %s  %s" (fmt/pad (str k) 18) (pr-str vs)))))
    (when (seq (:partial section))
      (println "  partial (missing in some runs):")
      (doseq [[k vs] (sort-by (comp str key) (:partial section))]
        (println (format "    %s  %s" (fmt/pad (str k) 18) (pr-str vs)))))
    (when (seq (:same section))
      (println (format "  identical: %s"
                       (str/join ", " (sort (map (comp str key) (:same section))))))))
  nil)
