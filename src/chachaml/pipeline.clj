(ns chachaml.pipeline
  "Pipeline execution and tracking.

  A pipeline is a named sequence of steps. Each step executes a
  function inside a tracked `with-run`, automatically chaining
  inputs/outputs. The pipeline itself is tracked as a parent record.

  Usage:

      (require '[chachaml.pipeline :as pipe])

      (pipe/run-pipeline!
        \"train-and-deploy\"
        [{:name \"preprocess\" :fn preprocess-fn}
         {:name \"train\"      :fn train-fn}
         {:name \"evaluate\"   :fn evaluate-fn}
         {:name \"register\"   :fn register-fn}])

  Each step fn receives `{:prev-result ... :pipeline-id ... :step-name ...}`
  and returns an arbitrary result passed to the next step.

  Or use `defpipeline` for a reusable definition:

      (pipe/defpipeline my-pipeline
        [{:name \"prep\"  :fn prep-fn}
         {:name \"train\" :fn train-fn}])"
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [clojure.string :as str]
            [next.jdbc :as jdbc]))

;; --- Store helpers (pipeline-specific SQL) ---------------------------

(defn- create-pipeline! [store pipeline]
  (jdbc/execute-one!
   (:datasource store)
   ["INSERT INTO pipelines (id, name, description, status, parent_run_id, created_at)
     VALUES (?, ?, ?, ?, ?, ?)"
    (:id pipeline) (:name pipeline) (:description pipeline)
    "running" (:parent-run-id pipeline) (System/currentTimeMillis)])
  pipeline)

(defn- update-pipeline-status! [store pipeline-id status]
  (jdbc/execute-one!
   (:datasource store)
   ["UPDATE pipelines SET status = ?, finished_at = ? WHERE id = ?"
    status (System/currentTimeMillis) pipeline-id]))

(defn- create-step! [store step]
  (jdbc/execute-one!
   (:datasource store)
   ["INSERT INTO pipeline_steps (id, pipeline_id, step_name, step_order, status)
     VALUES (?, ?, ?, ?, 'pending')"
    (:id step) (:pipeline-id step) (:step-name step) (:step-order step)]))

(defn- update-step! [store step-id updates]
  (let [sets (cond-> []
               (:status updates)      (conj "status = ?")
               (:run-id updates)      (conj "run_id = ?")
               (:started-at updates)  (conj "started_at = ?")
               (:finished-at updates) (conj "finished_at = ?"))
        vs   (cond-> []
               (:status updates)      (conj (:status updates))
               (:run-id updates)      (conj (:run-id updates))
               (:started-at updates)  (conj (:started-at updates))
               (:finished-at updates) (conj (:finished-at updates)))]
    (when (seq sets)
      (jdbc/execute-one!
       (:datasource store)
       (into [(str "UPDATE pipeline_steps SET "
                   (str/join ", " sets)
                   " WHERE id = ?")]
             (conj vs step-id))))))

(defn- get-pipeline [store pipeline-id]
  (when-let [row (jdbc/execute-one!
                  (:datasource store)
                  ["SELECT * FROM pipelines WHERE id = ?" pipeline-id])]
    {:id          (:pipelines/id row)
     :name        (:pipelines/name row)
     :description (:pipelines/description row)
     :status      (:pipelines/status row)
     :created-at  (:pipelines/created_at row)
     :finished-at (:pipelines/finished_at row)}))

(defn- get-pipeline-steps [store pipeline-id]
  (->> (jdbc/execute!
        (:datasource store)
        ["SELECT * FROM pipeline_steps WHERE pipeline_id = ? ORDER BY step_order"
         pipeline-id])
       (mapv (fn [row]
               {:id          (:pipeline_steps/id row)
                :pipeline-id (:pipeline_steps/pipeline_id row)
                :step-name   (:pipeline_steps/step_name row)
                :step-order  (:pipeline_steps/step_order row)
                :run-id      (:pipeline_steps/run_id row)
                :status      (:pipeline_steps/status row)
                :started-at  (:pipeline_steps/started_at row)
                :finished-at (:pipeline_steps/finished_at row)}))))

;; --- Public API ------------------------------------------------------

(defn run-pipeline!
  "Execute a pipeline: a named sequence of step definitions.

  Each step is `{:name \"step-name\" :fn (fn [ctx] result)}`.
  The context `ctx` contains:
  - `:prev-result`  Return value of the previous step (nil for first)
  - `:pipeline-id`  The pipeline's UUID
  - `:step-name`    This step's name
  - `:step-order`   This step's index (0-based)

  Each step's fn runs inside a `with-run` block, so all chachaml
  tracking (params, metrics, artifacts) works inside the step fn.

  Returns `{:pipeline-id ... :status ... :steps [...] :result ...}`."
  [pipeline-name steps & {:keys [description]}]
  (let [store       (ctx/current-store)
        pipeline-id (str (random-uuid))
        pipeline    {:id pipeline-id :name pipeline-name
                     :description description}]
    (create-pipeline! store pipeline)
    ;; Create all step records upfront
    (doseq [[i step] (map-indexed vector steps)]
      (create-step! store {:id          (str (random-uuid))
                           :pipeline-id pipeline-id
                           :step-name   (:name step)
                           :step-order  i}))
    (let [step-records (get-pipeline-steps store pipeline-id)]
      (try
        (let [final-result
              (reduce
               (fn [prev-result [i step]]
                 (let [step-rec (nth step-records i)
                       now      (System/currentTimeMillis)]
                   (update-step! store (:id step-rec)
                                 {:status "running" :started-at now})
                   (let [result
                         (ml/with-run {:experiment pipeline-name
                                       :name       (:name step)
                                       :tags       {:pipeline-id pipeline-id
                                                    :step-order  (str i)}}
                           (let [ctx {:prev-result prev-result
                                      :pipeline-id pipeline-id
                                      :step-name   (:name step)
                                      :step-order  i}
                                 r   ((:fn step) ctx)]
                             (update-step! store (:id step-rec)
                                           {:status      "completed"
                                            :run-id      (:id (ctx/current-run))
                                            :finished-at (System/currentTimeMillis)})
                             r))]
                     result)))
               nil
               (map-indexed vector steps))]
          (update-pipeline-status! store pipeline-id "completed")
          {:pipeline-id pipeline-id :status "completed"
           :steps (get-pipeline-steps store pipeline-id)
           :result final-result})
        (catch Throwable t
          (update-pipeline-status! store pipeline-id "failed")
          (throw t))))))

(defn pipelines
  "List all pipelines, most recent first."
  ([] (pipelines {}))
  ([{:keys [limit] :or {limit 50}}]
   (let [store (ctx/current-store)]
     (->> (jdbc/execute!
           (:datasource store)
           ["SELECT * FROM pipelines ORDER BY created_at DESC LIMIT ?" limit])
          (mapv (fn [row]
                  {:id          (:pipelines/id row)
                   :name        (:pipelines/name row)
                   :description (:pipelines/description row)
                   :status      (:pipelines/status row)
                   :created-at  (:pipelines/created_at row)
                   :finished-at (:pipelines/finished_at row)}))))))

(defn pipeline
  "Get a pipeline by id, including its steps."
  [pipeline-id]
  (let [store (ctx/current-store)]
    (when-let [p (get-pipeline store pipeline-id)]
      (assoc p :steps (get-pipeline-steps store pipeline-id)))))

(defmacro defpipeline
  "Define a reusable pipeline as a var. Call it like a function.

      (defpipeline my-pipeline
        [{:name \"prep\"  :fn prep-fn}
         {:name \"train\" :fn train-fn}])

      (my-pipeline)              ; run with defaults
      (my-pipeline {:description \"v2\"})  ; run with opts"
  [pipeline-name steps]
  (let [pname (name pipeline-name)]
    `(defn ~pipeline-name
       ~(str "Run the " pname " pipeline.")
       ([] (~pipeline-name {}))
       ([opts#]
        (run-pipeline! ~pname ~steps
                       :description (:description opts#))))))
