(ns chachaml.registry
  "Model registry public API.

  A model is a named entry; a model version is an immutable
  (model-name, version) pointing to a run + named artifact, with a
  stage. Stages: `:none`, `:staging`, `:production`, `:archived`.

  Promoting a version to `:production` automatically archives any
  previously-`:production` version of the same model (see ADR-0005
  to be added in M5).

  Implementation lands in M5.")
