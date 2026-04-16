(ns chachaml.repl
  "REPL-oriented helpers for chachaml.

  Conveniences for ad-hoc exploration: pretty printing for runs and
  models (`inspect`, `runs-table`), and a small `compare-runs` diff.

  These are not required for programmatic use; they print to `*out*`
  and return nil (or, for `compare-runs`, a plain map you can also
  print or feed to `tap>`)."
  (:require [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [clojure.set :as set]
            [clojure.string :as str])
  (:import [java.time Duration Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;; --- Time formatting -------------------------------------------------

(def ^:private ts-formatter
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             (ZoneId/systemDefault)))

(defn- fmt-instant [^long ms]
  (.format ts-formatter (Instant/ofEpochMilli ms)))

(defn- fmt-duration [^long start ^long end]
  (let [d  (Duration/ofMillis (max 0 (- end start)))
        ms (.toMillis d)
        s  (/ ms 1000.0)]
    (cond
      (< ms 1000)    (format "%dms" ms)
      (< s 60)       (format "%.2fs" s)
      :else          (format "%dm %.1fs" (long (/ s 60)) (rem s 60)))))

(defn- short-id [id]
  (when id (subs (str id) 0 (min 8 (count (str id))))))

;; --- Tabular runs listing -------------------------------------------

(defn- pad
  "Right-pad `s` to width `n` with spaces. Does not truncate."
  [s n]
  (format (str "%-" n "s") (str s)))

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
                            [(pad "ID"         10)
                             (pad "EXPERIMENT" 22)
                             (pad "NAME"       16)
                             (pad "STATUS"     10)
                             (pad "STARTED"    20)
                             "DURATION"]))
         (doseq [{:keys [id experiment status start-time end-time]
                  run-name :name} rs]
           (println (str/join "  "
                              [(pad (or (short-id id) "")    10)
                               (pad (or experiment "")       22)
                               (pad (or run-name "")         16)
                               (pad (str status)             10)
                               (pad (fmt-instant start-time) 20)
                               (if end-time
                                 (fmt-duration start-time end-time)
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

(defn- size-str [^long n]
  (cond
    (< n 1024)             (str n " B")
    (< n (* 1024 1024))    (format "%.1f KiB" (/ n 1024.0))
    (< n (* 1024 1024 1024)) (format "%.2f MiB" (/ n 1024.0 1024.0))
    :else                    (format "%.2f GiB" (/ n 1024.0 1024.0 1024.0))))

(defn- inspect-run* [run]
  (println (str "== Run " (:id run) " =="))
  (println (format "Experiment:  %s" (:experiment run)))
  (when (:name run)          (println (format "Name:        %s" (:name run))))
  (println (format "Status:      %s" (:status run)))
  (println (format "Started:     %s" (fmt-instant (:start-time run))))
  (when (:end-time run)
    (println (format "Duration:    %s"
                     (fmt-duration (:start-time run) (:end-time run)))))
  (when (:error run)         (println (format "Error:       %s" (:error run))))
  (when (seq (:tags run))
    (println (format "Tags:        %s" (pr-str (:tags run)))))
  (when-let [git (:git (:env run))]
    (println (format "Git:         %s%s on %s"
                     (subs (or (:sha git) "—") 0 (min 7 (count (or (:sha git) ""))))
                     (if (:dirty? git) " (dirty)" "")
                     (or (:branch git) "?"))))
  (let [params (or (:params run) {})]
    (println)
    (println (format "Params (%d):" (count params)))
    (doseq [[k v] (sort-by (comp str key) params)]
      (println (format "  %s  %s" (pad (str k) 16) (pr-str v)))))
  (let [summary (summarize-metrics (:metrics run))]
    (println)
    (println (format "Metrics (%d keys, %d total points):"
                     (count summary) (reduce + (map :count summary))))
    (doseq [{k :key n :count v0 :first vN :last} summary]
      (if (= 1 n)
        (println (format "  %s  %s" (pad (str k) 16) vN))
        (println (format "  %s  %d points, %s → %s"
                         (pad (str k) 16) n v0 vN)))))
  (let [arts (or (:artifacts run) [])]
    (when (seq arts)
      (println)
      (println (format "Artifacts (%d):" (count arts)))
      (doseq [{art-name :name :keys [size content-type]
               digest :hash} arts]
        (println (format "  %s  %s  %s  sha=%s"
                         (pad art-name 16)
                         (pad (size-str (or size 0)) 10)
                         (pad (or content-type "") 26)
                         (subs (or digest "")
                               0 (min 8 (count (or digest "")))))))))
  nil)

(defn- inspect-model* [model]
  (let [versions (reg/model-versions (:name model))]
    (println (str "== Model: " (:name model) " =="))
    (when (:description model)
      (println (format "Description: %s" (:description model))))
    (println (format "Created:     %s" (fmt-instant (:created-at model))))
    (println (format "Versions (%d):" (count versions)))
    (doseq [{:keys [version stage run-id artifact-id description]} versions]
      (println (format "  v%d  %s  run=%s  artifact=%s%s"
                       version
                       (pad (str stage) 12)
                       (short-id run-id)
                       (short-id artifact-id)
                       (if description (str "  // " description) ""))))
    nil))

(defn- inspect-version* [version]
  (println (format "== %s v%d =="
                   (:model-name version) (:version version)))
  (println (format "Stage:       %s" (:stage version)))
  (println (format "Run id:      %s" (:run-id version)))
  (println (format "Artifact id: %s" (:artifact-id version)))
  (println (format "Created:     %s" (fmt-instant (:created-at version))))
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
    (if-let [r (ml/run x)]
      (inspect-run* r)
      (if-let [m (reg/model x)]
        (inspect-model* m)
        (println (format "(no run or model with id/name %s)" x))))

    (and (map? x) (:status x))
    (inspect-run* x)

    (and (map? x) (:version x))
    (inspect-version* x)

    (and (map? x) (:name x))
    (inspect-model* x)

    :else
    (println "(unknown entity)" (pr-str x))))

;; --- Comparing runs --------------------------------------------------

(defn- last-metric-by-key [metrics]
  (->> metrics
       (group-by :key)
       (reduce-kv (fn [acc k vs]
                    (assoc acc k (:value (last (sort-by :step vs)))))
                  {})))

(defn- diff-maps
  "Categorise keys across `maps` into `:same`, `:differ`, `:partial`."
  [maps]
  (let [all-keys (apply set/union (map (comp set keys) maps))]
    (reduce
     (fn [acc k]
       (let [vs (mapv #(get % k) maps)]
         (cond
           (apply = vs)   (assoc-in acc [:same k] (first vs))
           (some nil? vs) (assoc-in acc [:partial k] vs)
           :else          (assoc-in acc [:differ k] vs))))
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
        metrics (mapv (comp last-metric-by-key :metrics) runs)]
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
        (println (format "    %s  %s" (pad (str k) 18) (pr-str vs)))))
    (when (seq (:partial section))
      (println "  partial (missing in some runs):")
      (doseq [[k vs] (sort-by (comp str key) (:partial section))]
        (println (format "    %s  %s" (pad (str k) 18) (pr-str vs)))))
    (when (seq (:same section))
      (println (format "  identical: %s"
                       (str/join ", " (sort (map (comp str key) (:same section))))))))
  nil)
