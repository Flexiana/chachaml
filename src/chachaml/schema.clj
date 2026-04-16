(ns chachaml.schema
  "Malli schemas for the chachaml public API.

  Schemas live here, separate from the API namespaces, so that:

  - The API surface stays uncluttered.
  - Schemas can be required from tests and user code without pulling in
    full API namespaces.
  - We can later opt into runtime instrumentation in dev/test.

  Each public fn in `chachaml.core`, `chachaml.registry`, and
  `chachaml.tracking` is expected to register its input/output schemas
  here as registry entries.

  Schemas are added incrementally as the API lands across M2-M6.")
