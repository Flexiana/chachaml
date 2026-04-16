(ns chachaml.context
  "Dynamic context for chachaml.

  Two dynamic vars carry implicit context through the call stack so that
  user code does not need to thread store/run identifiers explicitly:

  - `*store*` — the active `RunStore`/`ArtifactStore`/`ModelRegistry`
    backend. Bound by `chachaml.core/use-store!` or `with-store`.
  - `*run*`   — the active run map inside a `with-run` block.

  Implementation lands in M2.")
