# 3. SQLite as the default storage backend

Date: 2026-04-16

## Status

Accepted.

## Context

chachaml targets a REPL-first, single-process / small-team workflow
(see `doc/SPEC.md`). The default backend has to satisfy:

- Zero setup ("just start tracking").
- File-based, so a project's runs commit alongside the project (or are
  ignored — user's choice).
- Concurrent writes from a single JVM process must be safe.
- Cross-platform without a separate server.

Candidates considered:

- **In-memory only**: trivially satisfies setup but loses everything on
  REPL exit. Unacceptable as a default.
- **Files-only (EDN-per-run)**: simple, but querying across runs
  becomes O(N) reads, and concurrent writes need our own locking.
- **PostgreSQL / MySQL**: requires a server. Over the line for "first
  five minutes."
- **Datomic / XTDB**: powerful, but adds heavy deps and a learning
  curve at the wrong layer.
- **SQLite**: single-file, JDBC driver is small, file locking is
  battle-tested, ACID, and fast enough for telemetry workloads at
  this scale.

## Decision

The default backend is SQLite, via `next.jdbc` and
`org.xerial/sqlite-jdbc`. The DB file lives at `./chachaml.db` by
default (project-local; gitignored). Artifact bytes live on the
filesystem under `./chachaml-artifacts/<run-id>/<name>`; the database
holds only metadata and a relative path.

Other backends (Postgres, S3-backed artifacts, …) are explicitly
deferred post-v0.1 and will arrive as separate implementations of the
protocols defined in ADR-0002.

## Consequences

- Single-process is the supported concurrency model. Cross-process
  access is possible (SQLite handles file-level locking) but not a
  primary use case for v0.1.
- Filesystem-backed artifacts mean DB backup ≠ full backup; the
  artifact directory must travel with the DB file. Documented in
  `CONTRIBUTING.md` once relevant.
- Schema migrations are our problem to manage explicitly (M1.2).
