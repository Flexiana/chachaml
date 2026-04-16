# chachaml — Architectural Plan

## Layering

```
┌─────────────────────────────────────────┐
│ chachaml.core      (public API)         │
│ chachaml.tracking  (deftracked macro)   │
│ chachaml.registry  (model registry API) │
│ chachaml.repl      (REPL helpers)       │
├─────────────────────────────────────────┤
│ chachaml.context   (dynamic vars)       │
│ chachaml.serialize (artifact codec)     │
│ chachaml.env       (metadata capture)   │
├─────────────────────────────────────────┤
│ chachaml.store.protocol                 │
│ chachaml.store.sqlite (default impl)    │
└─────────────────────────────────────────┘
```

## Key technical decisions

1. **SQLite default** — single file, zero setup
2. **Filesystem artifacts** under `<root>/artifacts/<run-id>/<name>` (DB
   stores metadata only)
3. **nippy** for Clojure value serialization
4. **EDN** for params/tags/env (human-readable in DB)
5. **Dynamic vars** (`*store*`, `*run*`) for context — idiomatic, REPL-friendly
6. **Protocol-first**: `RunStore`, `ArtifactStore`, `ModelRegistry`,
   `Lifecycle` separate
7. **No global mutable state** except a single delay-initialized default
   store atom
8. **Errors** in `with-run` mark run `:failed` and re-throw

## Open questions (proposed answers)

| # | Question                                  | Proposed                                                  |
| - | ----------------------------------------- | --------------------------------------------------------- |
| 1 | Stage exclusivity for `:production`?      | Yes — auto-archive previous on promote                    |
| 2 | Metric `step` semantics?                  | Explicit, default 0; provide `(next-step :loss)` helper   |
| 3 | Soft delete in v0.1?                      | No — hard delete only                                     |
| 4 | Artifact dedup by hash?                   | Defer post-v0.1                                           |
| 5 | Default store location?                   | `./chachaml.db` (project-local, gitignored)               |
| 6 | Auto-flush metrics?                       | Immediate; add `(with-batched-metrics …)` later           |

## Dependencies

Core (required):

- `org.clojure/clojure` 1.12.x
- `com.github.seancorfield/next.jdbc` — JDBC wrapper
- `org.xerial/sqlite-jdbc` — SQLite driver
- `com.taoensso/nippy` — value serialization
- `metosin/malli` — schemas for the public API

Test:

- `lambdaisland/kaocha` — test runner
- `org.clojure/test.check` — property-based testing

Build / dev:

- `io.github.clojure/tools.build` — jar/release build
- `clj-kondo` — linter (CI gate)
- `dev.weavejester/cljfmt` — formatter
- `cloverage` — coverage gate (≥ 85% line, ≥ 80% branch)
- Babashka — local task runner (`bb.edn`)

Deferred (added per-feature later):

- `ring`, `hiccup` — UI server
- `clj-python/libpython-clj` — Python interop layer

## Quality stack

The full quality engineering plan lives at
`/Users/jiriknesl/.claude/plans/proud-forging-sutherland.md`. Summary:

- Tooling: `clj-kondo` (warning-level fail), `cljfmt`, `kaocha`,
  `cloverage`, `tools.build`, `bb.edn`.
- CI: 4-job matrix `{deps, lein} × JDK {17, 21}` plus lint, format,
  coverage gates.
- Schemas (Malli) for the public API in `chachaml.schema`.
- ADRs for architectural decisions in `doc/adr/`.
- Per-milestone quality bar enumerated in `doc/BACKLOG.md` and
  `CONTRIBUTING.md`.
