(ns chachaml.store.sqlite
  "SQLite-backed implementation of the chachaml storage protocols.

  The default backend. A single SQLite file holds runs, params, metrics,
  artifacts metadata, and the model registry. Artifact bytes live on the
  filesystem under `<root>/artifacts/<run-id>/<name>`; the database stores
  only the metadata and relative path.

  Implementation lands in M1. This namespace is currently a placeholder.")
