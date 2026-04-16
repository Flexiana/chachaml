(ns chachaml.tracking
  "Function-level tracking macros.

  Provides `deftracked`, a `defn`-shaped macro that wraps a function so
  that each call automatically:

  - Opens a run (or nests under the current one).
  - Captures arguments as params.
  - Marks the run failed and re-throws on exception.
  - Closes the run with status `:completed` on normal return.

  Implementation lands in M4.")
