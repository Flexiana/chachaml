# 4. Dynamic vars carry store and run context

Date: 2026-04-16

## Status

Accepted.

## Context

Most chachaml operations need two pieces of implicit context:

- The active **store** to read/write against.
- The active **run** to attach params/metrics/artifacts to.

We need a way for code inside a `with-run` block — including code many
calls deep — to find that context without threading a run-id and a
store handle through every signature.

Options considered:

- **Thread arguments explicitly**: most "honest", worst ergonomics.
  REPL one-liners become unusable.
- **Single global atom**: works for one run at a time, but breaks the
  moment you want concurrent runs (parallel hyperparameter search) or
  swap stores in tests.
- **Component / Integrant**: idiomatic for systems, overkill for a
  REPL library, and pushes setup work onto the user.
- **Dynamic vars** (`^:dynamic`): exactly the Clojure-shaped answer.
  Lexically scoped via `binding`, automatically propagate to the same
  thread, threadable to other threads via `bound-fn`.

## Decision

`chachaml.context` defines two dynamic vars:

```clojure
(def ^:dynamic *store* nil)
(def ^:dynamic *run*   nil)
```

`chachaml.core/use-store!` sets a global default by `alter-var-root`-ing
`*store*`. `with-store` and `with-run` rebind them lexically. Code in
`chachaml.core` reads `*run*` to determine where to attach logged data.

For multi-threaded use (parallel runs), users wrap their work with
`bound-fn` or pass the run map explicitly — a documented escape hatch.

## Consequences

- REPL ergonomics: zero context threading in user code.
- Tests can rebind both vars to a tempfile-backed store with `binding`.
- Parallel runs require slightly more care (`bound-fn`); we document
  this in the API for `with-run`.
- A single per-thread "current run" simplification: nested `with-run`
  in the same thread becomes a child run, which matches user
  expectations and is also the MLflow convention.
