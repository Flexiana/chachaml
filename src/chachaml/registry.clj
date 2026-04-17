(ns chachaml.registry
  "Model registry public API.

  A **model** is a named entry; a **model version** is an immutable
  `(model-name, version)` pair pointing to a run + named artifact,
  carrying a **stage**. Stages: `:none`, `:staging`, `:production`,
  `:archived`.

  Promoting a version to `:production` automatically archives any
  previously-`:production` version of the same model. See ADR-0006
  for the rationale behind production-stage exclusivity.

  Typical flow:

      (ml/with-run {:experiment \"iris\"}
        (ml/log-artifact \"model\" trained-model)
        (registry/register-model \"iris-classifier\"
                                 {:artifact \"model\"
                                  :stage    :staging}))

      (registry/promote! \"iris-classifier\" 1 :production)
      (registry/load-model \"iris-classifier\")    ;; latest production

  All ops use the ambient `chachaml.context/*store*` (see
  `chachaml.core/use-store!`)."
  (:require [chachaml.context :as ctx]
            [chachaml.schema :as schema]
            [chachaml.serialize :as serialize]
            [chachaml.store.protocol :as p]))

(defn register-model
  "Register a model and create a new version pointing to a named
  artifact on the current run.

  Required:
  - `model-name`        string identifier for the model.

  `opts` may include:
  - `:artifact`         name of the artifact (on the current run) to
                        register. Required unless `:artifact-id` given.
  - `:artifact-id`      direct artifact id (skips the by-name lookup).
  - `:run-id`           run id (defaults to the current run).
  - `:stage`            initial stage (default `:none`). Promoting
                        directly to `:production` here will archive any
                        previously-production version atomically.
  - `:description`      string description for the new version.
  - `:model-description` string set on first registration (creating
                        the model row); ignored on subsequent calls.

  Returns the new version map."
  [model-name {:keys [artifact artifact-id run-id stage description
                      model-description]
               :or   {stage :none}
               :as   opts}]
  (schema/validate schema/RegisterOpts (dissoc opts :model-description))
  (let [store    (ctx/current-store)
        run-id   (or run-id (:id (ctx/require-run!)))
        art-id   (or artifact-id
                     (some-> (p/-find-artifact store run-id artifact) :id)
                     (throw (ex-info
                             "Artifact not found on run"
                             {:type ::missing-artifact
                              :run-id run-id :artifact artifact})))]
    (p/-register-model! store {:name        model-name
                               :description model-description})
    (p/-create-version! store {:model-name  model-name
                               :run-id      run-id
                               :artifact-id art-id
                               :stage       stage
                               :description description})))

(defn models
  "List all registered models."
  []
  (p/-list-models (ctx/current-store)))

(defn model
  "Fetch a single model by name, or nil."
  [model-name]
  (p/-get-model (ctx/current-store) model-name))

(defn model-versions
  "List all versions of a model, oldest first."
  [model-name]
  (p/-list-versions (ctx/current-store) model-name))

(defn promote!
  "Set the stage of `(model-name, version)`. Promoting to
  `:production` atomically archives any other production version of
  the same model. Returns the updated version map."
  [model-name version stage]
  (schema/validate schema/Stage stage)
  (p/-set-stage! (ctx/current-store) model-name version stage))

(defn load-model
  "Load and deserialize the artifact backing a model version.

  Selectors (passed in `opts`):
  - `{:version n}`     load a specific version.
  - `{:stage k}`       load the latest version with stage `k`.
  - `{}` (default)     load the latest `:production` version.

  Returns the deserialized value, or nil if no matching version (or
  artifact bytes) exist."
  ([model-name] (load-model model-name {}))
  ([model-name selector]
   (let [store   (ctx/current-store)
         version (p/-find-version store model-name selector)]
     (when version
       (when-let [art (p/-get-artifact store (:artifact-id version))]
         (let [data (p/-get-artifact-bytes store (:id art))
               fmt  (serialize/format-from-content-type
                     (:content-type art))]
           (serialize/decode {:format fmt :bytes data})))))))

(defn set-model-note!
  "Set or update the description/note on a model. Supports markdown."
  [model-name note]
  (p/-upsert-experiment! (ctx/current-store)
                         {:name model-name :description note}))

(defn diff-versions
  "Compare the runs behind two versions of the same model. Returns
  the same shape as `chachaml.repl/compare-runs` — params/metrics
  diff between the runs that produced version `v1` and `v2`."
  [model-name v1 v2]
  (let [store (ctx/current-store)
        ver1  (p/-get-version store model-name v1)
        ver2  (p/-get-version store model-name v2)]
    (when (and ver1 ver2)
      (let [compare-runs (requiring-resolve 'chachaml.repl/compare-runs)]
        (compare-runs [(:run-id ver1) (:run-id ver2)])))))
