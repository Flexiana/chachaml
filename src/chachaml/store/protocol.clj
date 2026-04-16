(ns chachaml.store.protocol
  "Storage protocols for chachaml.

  Three orthogonal protocols a backend may implement:

  - `RunStore`       — runs, params, metrics, tags lifecycle.
  - `ArtifactStore`  — binary artifact persistence.
  - `ModelRegistry`  — named models, versions, stages.

  Plus `Lifecycle` for resource cleanup. A concrete backend (e.g.
  `chachaml.store.sqlite`) will typically implement all three on a single
  record, but each protocol is independently usable.

  All methods are intended for internal use by the public API in
  `chachaml.core`. End-user code should not call protocol methods
  directly.")

(defprotocol RunStore
  "Run lifecycle and time-series telemetry."
  (-create-run! [this run] "Insert a new run; returns the run map.")
  (-update-run! [this run-id updates] "Apply a partial update to a run.")
  (-get-run [this run-id] "Fetch a run by id, or nil.")
  (-query-runs [this filters] "Return a seq of runs matching `filters`.")
  (-log-params! [this run-id params] "Insert immutable params; ignores duplicates.")
  (-get-params [this run-id] "Return params map for run.")
  (-log-metrics! [this run-id metrics] "Append metric rows: `[{:key :loss :value 0.3 :step 1}…]`.")
  (-get-metrics [this run-id] "Return all metric rows for a run."))

(defprotocol ArtifactStore
  "Binary artifact persistence keyed by run + artifact name."
  (-put-artifact! [this run-id art-name bytes content-type] "Persist bytes; returns artifact map with id, hash, size.")
  (-get-artifact [this artifact-id] "Return artifact metadata, or nil.")
  (-get-artifact-bytes [this artifact-id] "Return raw bytes for an artifact.")
  (-find-artifact [this run-id art-name] "Find a named artifact for a run, or nil.")
  (-list-artifacts [this run-id] "Return seq of artifacts for a run."))

(defprotocol ModelRegistry
  "Named, versioned, staged model registry."
  (-register-model! [this model] "Insert model row if absent; returns model map.")
  (-create-version! [this version] "Insert a new version; returns version map.")
  (-get-model [this model-name] "Fetch model by name, or nil.")
  (-get-version [this model-name version] "Fetch a specific version, or nil.")
  (-list-models [this] "Return all models.")
  (-list-versions [this model-name] "Return all versions for a model.")
  (-set-stage! [this model-name version stage] "Transition a version's stage.")
  (-find-version [this model-name selector] "Resolve `{:version n}` or `{:stage :production}` to a version map."))

(defprotocol Lifecycle
  "Backend resource management."
  (close! [this] "Release any resources held by this store."))
