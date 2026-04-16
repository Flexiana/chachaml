(ns chachaml.serialize
  "Artifact serialization codec.

  Multimethod-dispatched encoding of Clojure values to bytes for storage,
  and decoding back. Built-in formats:

  - `:bytes` — raw `byte[]` passthrough.
  - `:file`  — a `java.io.File` or path string; bytes are the file contents.
  - `:edn`   — pretty-printed EDN (human-readable, slow for large data).
  - `:nippy` — Taoensso nippy freeze/thaw (default, supports most types).

  Format is auto-detected from the value type unless overridden via
  `(log-artifact name v {:format :edn})`.

  Implementation lands in M3.")
