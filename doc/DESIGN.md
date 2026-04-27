# Design & rationale

This document explains *why* chachaml is shaped the way it is. If
you're trying to learn the library, start with the
[Tutorial](TUTORIAL.md). If you're picking it apart to decide whether
it fits your team, this is the right read.

The library has six load-bearing decisions. Everything else falls out
of them.

## 1. The REPL is the primary interface

chachaml is **REPL-first**. The web UI, the MCP server, and the HTTP
write API are layers on top of the same data; the REPL is not a
debugging shim onto a "real" service.

This shapes the whole API:

- A run is opened with a single form: `(with-run {:experiment "x"} ...)`.
- The default store is opened lazily on first write — there is no
  setup step.
- Every public function returns a plain map you can `keys`, `get`,
  `pp/pprint`, or `def` for later.
- Side-effecting functions end with `!`; everything else is safe to
  call repeatedly during exploration.

The trade-off: if you prefer a service-oriented design where every
dependency is wired through `Component` or `Integrant`, chachaml feels
*too* magical. We accept that. The audience is data practitioners
typing into a REPL, and the cost of explicit wiring there is too high.

## 2. Dynamic vars carry the active run and store

Two `^:dynamic` vars in `chachaml.context` thread the current store
and run through arbitrarily deep call stacks:

```clojure
(def ^:dynamic *store* nil)
(def ^:dynamic *run*   nil)
```

`with-store` and `with-run` rebind them lexically. Any function in the
library that needs to know "what's the current run" reads `*run*`
rather than taking it as an argument. Users get to write training
loops without threading state.

Why not a single global atom, an explicit argument, or an injected
component? The full discussion is in
[ADR-0004](adr/0004-dynamic-vars-for-context.md), but in short:

- A single atom breaks the moment you want concurrent runs (parallel
  hyperparameter search).
- Explicit args make REPL one-liners unusable.
- Component-style injection pushes setup work onto the user before
  they can even log a metric.

The escape hatch for multi-threaded work is `bound-fn` (or pass the
run map explicitly) — documented on `with-run`.

## 3. Two backends, one protocol

`chachaml.store.protocol` defines four protocols: `RunStore`,
`ArtifactStore`, `ModelRegistry`, `Lifecycle`. Two backends implement
them:

- **SQLite** — the default. Single file, zero setup, perfect for
  solo work and quick experiments.
- **Postgres** — for teams. Same schema, same map shapes, swap
  backends without changing application code.

`(chachaml.store/open {:type :sqlite})` and
`(chachaml.store/open {:type :postgres ...})` are the only points
where a user touches the backend type. Everything else is the same.

Why this seam exists ([ADR-0002](adr/0002-store-protocol-shape.md)):
backends differ in how they handle concurrency, transactions, and
artifact storage, but the *shape* of the data ML practitioners care
about — runs, params, metrics, artifacts, models — is identical. A
narrow protocol surface keeps the library code single-shaped while
letting backends specialise.

The default is SQLite ([ADR-0003](adr/0003-sqlite-default-backend.md))
because most chachaml work is either solo or batch; nothing about a
single-user training loop needs a network round-trip per metric write.
If your team grows, you migrate (see
[migrate-sqlite-to-postgres](howto/migrate-sqlite-to-postgres.md)).

## 4. Artifacts live on the filesystem, not in the database

Trained models, plots, datasets, and other binary blobs are stored as
files next to the database, not as `BLOB` columns inside it.

```
./chachaml.db                # metadata: run id, name, path, sha256, size
./chachaml-artifacts/        # the bytes
  └── <run-id>/
      ├── model
      └── ...
```

The decision is documented in
[ADR-0005](adr/0005-filesystem-backed-artifacts.md). Three reasons
matter:

1. **Standard CLI tools work.** You can `tar` your artifacts, `cp`
   them to a colleague, or `aws s3 cp` them to a bucket without
   special tooling.
2. **Streaming large files is trivial.** SQLite tops out around
   1 GiB per BLOB. ML practitioners hit that quickly.
3. **Concurrent writes don't serialise.** SQLite serialises every
   transaction; the filesystem handles concurrent artifact writes
   natively.

The cost is a paired-directory backup discipline: backing up only the
`.db` file silently loses every artifact. Postgres + S3 deployments
sidestep that — `ArtifactStore` is a separate protocol from
`RunStore`, so the S3-backed implementation uses object storage
natively without changing the public API.

The default serialisation format for an artifact value is
[nippy](https://github.com/taoensso/nippy). Pass `:format :edn` if you
want a human-readable file. `:bytes` and `:file` formats let you log
binary blobs and existing files directly.

## 5. Production stage is exclusive

Each model in the registry has versions, each version has a stage:
`:none`, `:staging`, `:production`, or `:archived`. The rule for
`:production` is **exclusive**: at most one version per model is in
production at any time. Promoting a new version atomically demotes
the previous production version to `:archived`.

This deliberately diverges from MLflow's convention (where
`:production` is a free-form label that any number of versions can
carry). The reasoning is in
[ADR-0006](adr/0006-production-stage-exclusivity.md): in real
deployments there is exactly one model serving traffic, and a registry
where "production" is a multi-set creates ambiguity at exactly the
moment it matters most.

Concretely:

- `(reg/load-model "name")` returns the unique production version, or
  `nil`.
- `(reg/promote! name v :production)` swaps within a single
  transaction. No window where zero or two versions are production.
- A property test asserts the invariant under arbitrary
  add-and-promote sequences.

History is preserved — archived versions are still in the database,
queryable via `(reg/model-versions name)`.

## 6. Three ways to track a function

The library exposes three different shapes for "track this work as a
run":

| Mode | Form | When to use |
|---|---|---|
| `with-run` | A macro you wrap explicit forms in | One-off REPL exploration; you want to see exactly what's tracked |
| `deftracked` | A `defn`-shaped wrapper (`chachaml.tracking`) | Production code; the function *is* always tracked |
| `pipeline` | A vector of `[name fn]` steps (`chachaml.pipeline`) | Multi-step orchestration where each step is its own tracked run, results pass between steps |

They compose. A `deftracked` function called inside a `with-run` opens
a child run automatically (parent–child relationship is wired up via
the dynamic vars). A `pipeline` step can call a `deftracked` function;
that function gets its own grand-child run.

The reason for three modes rather than one is ergonomic: a one-line
REPL experiment doesn't deserve a `defn`, and a deeply pipelined batch
job shouldn't be expressed as a giant `with-run` body.

## The MCP angle

`chachaml.mcp` exposes 16 read-and-tag tools (`list_runs`, `get_run`,
`search_runs`, `best_run`, `add_tag`, `set_note`, ...) via the Model
Context Protocol. An LLM client (Claude Code, Continue, Cursor) can
call these tools to answer questions about your experiments.

This isn't a separate API — it's the *same* data model the REPL and
UI see, exposed over a different transport. An MCP-equipped agent
queries the same SQLite/Postgres tables your training code writes to.
That's the payoff of having one storage protocol: the agent doesn't
need a parallel "experiment graph" to query; it queries the truth.

The MCP tool list is in [MCP.md](MCP.md); the setup walkthrough is in
[configure-mcp-with-claude-code](howto/configure-mcp-with-claude-code.md).

## What chachaml is not

A few things the design intentionally doesn't try to do, so you can
decide quickly whether it fits:

- **Distributed training orchestration.** This is a tracking library,
  not Airflow or Ray. If your pipeline needs DAG scheduling and
  resource allocation, run that with the right tool and have it call
  chachaml for tracking.
- **Multi-tenant SaaS.** No row-level security, no auth. The
  user-attribution feature (`CHACHA_USER`, `my-runs`) is for cooperative
  team use, not for hostile multi-tenancy.
- **MLflow drop-in.** API shapes are similar but deliberately
  Clojure-shaped. No attempt to be wire-compatible.

For the running list of decisions made during development, see the
[ADRs](adr/).
