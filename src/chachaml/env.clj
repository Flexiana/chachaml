(ns chachaml.env
  "Reproducibility metadata capture.

  Snapshots the runtime environment for inclusion in a run record:
  git revision/branch/dirty state, JVM info, OS info, Clojure version,
  current user. All capture is best-effort: missing tools (e.g. no git
  on PATH) silently produce nil rather than throwing.

  Implementation lands in M2.")
