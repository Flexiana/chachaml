# 5. Artifact bytes live on the filesystem, not in the database

Date: 2026-04-16

## Status

Accepted.

## Context

chachaml needs to persist arbitrary binary blobs (trained models,
plots, datasets, files) attached to a run. Two natural options exist
for the default SQLite backend:

- **Store bytes inline in the database** as a `BLOB` column.
- **Store bytes on the filesystem**, with the database holding only
  metadata and a relative path.

Trade-offs:

| Concern              | DB BLOB                          | Filesystem                                                              |
| -------------------- | -------------------------------- | ----------------------------------------------------------------------- |
| Single-file backup   | Yes                              | No — DB and artifact dir must travel together                           |
| Streaming large files | Awkward; SQLite has 1 GiB limit  | Trivial; OS handles it                                                  |
| External tooling     | None — bytes locked in DB        | `ls`, `cp`, `tar`, `aws s3 cp` all work directly                        |
| Concurrent writes    | SQLite serialises everything     | Filesystem handles concurrent file writes natively                      |
| Hash/dedup later     | Easy via SQL                     | Easy via separate dedup index                                           |
| Atomicity with row   | Same transaction                 | Two-step: write file, then insert row (potential leak on crash)         |

The expected workload is ML models and plots — typically MB-scale,
occasionally tens of MB. Inline BLOBs would push SQLite past its
sweet spot, and the inability to reach artifacts with standard CLI
tools rules out a lot of pragmatic workflows (sharing a model with a
colleague, uploading to S3, inspecting a saved plot).

## Decision

Artifact bytes live on the filesystem, in a directory paired with
the database file. The database holds only metadata: id, run id,
artifact name, relative path, content type, byte size, SHA-256 hex
digest, and creation timestamp.

Default layout:

```
./chachaml.db                # SQLite metadata
./chachaml-artifacts/        # paired artifact directory
  └── <run-id>/
      ├── <artifact-name>
      └── …
```

Custom DB paths derive a sibling artifact directory:
`/path/to/foo.db` ⇒ `/path/to/foo-artifacts/` (the `.db` suffix is
stripped). Users may override with `(open {:artifact-dir "…"})`.

In-memory mode (`:in-memory? true`) creates a temporary directory for
the duration of the store, removed on `close!`.

The write order is deliberately: bytes first, then row insert. A
crash between the two leaves orphan files but never a metadata row
pointing at missing bytes. Orphan cleanup is not implemented in
v0.1; it is a known follow-up if it becomes a real problem.

## Consequences

- A backup that captures only the `.db` file will silently lose every
  artifact. Users must back up the artifact directory too.
  `CONTRIBUTING.md` will mention this when the surface area grows.
- The two-step write means a crash can leak orphan files. Acceptable
  for v0.1 given the workload; revisit if needed.
- Future backends (Postgres + S3, e.g.) implement `ArtifactStore`
  separately from `RunStore`, so they can use object storage natively
  without changing the public API.
- The unique `(run_id, name)` index in the DB rejects duplicate
  artifact names per run at insert time; the filesystem layer
  doesn't need its own collision logic.
