# 2. Three orthogonal storage protocols

Date: 2026-04-16

## Status

Accepted.

## Context

chachaml needs to persist three distinct kinds of data:

- **Run telemetry**: runs, params, metrics, tags. Many small writes.
- **Artifacts**: arbitrary bytes (models, files, plots). Few large
  writes; bytes typically belong on a filesystem or object store, with
  metadata in a database.
- **Model registry**: named, versioned, staged pointers to artifacts.
  Few writes, frequent reads, transactional.

A single monolithic `Store` protocol mixing all three would force every
backend to implement everything, and would couple unrelated concerns
(e.g. an in-memory test store would have to fake artifact byte storage).

## Decision

We split storage into three protocols in `chachaml.store.protocol`:

- `RunStore`       — run/param/metric/tag CRUD.
- `ArtifactStore`  — artifact bytes and metadata.
- `ModelRegistry`  — models, versions, stages.

Plus a `Lifecycle` protocol with `close!` for resource cleanup.

A concrete backend (e.g. `chachaml.store.sqlite`) typically implements
all four on a single record, but each is independently usable. This
keeps tests, alternative backends, and future composition (e.g. SQLite
metadata + S3 artifact bytes) clean.

All protocol fns are prefixed with `-` and treated as internal:
end-user code goes through `chachaml.core` and `chachaml.registry`.

## Consequences

- A backend can be partial (e.g. read-only registry view).
- Composition is possible: a hybrid backend can mix protocol impls
  from different sources without inheriting the wrong methods.
- The public API can evolve independently of the storage protocols.
- Slightly more boilerplate per backend, but each protocol is small.
