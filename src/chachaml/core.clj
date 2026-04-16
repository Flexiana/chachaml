(ns chachaml.core
  "Public API for chachaml.

  Re-exports the most common operations across run lifecycle, logging,
  artifacts, querying, and registry. The intent is that REPL users can
  `(require '[chachaml.core :as ml])` and have everything they need.

  See `doc/SPEC.md` for the full API surface and `doc/PLAN.md` for the
  layering. Implementations land across M2-M6.")
