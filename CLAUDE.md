# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

This repository is in the **design phase**. No code, build files, or tests
exist yet. The current artifacts are three planning documents under `doc/`:

- `doc/SPEC.md` — vision, concepts, data model, target API surface
- `doc/PLAN.md` — layering, technical decisions, open questions
- `doc/BACKLOG.md` — milestones M0–M6 with acceptance criteria

When asked to implement anything, read all three before starting and follow
the milestone order in `BACKLOG.md`. Open questions in `PLAN.md` may still be
unresolved — confirm with the user before assuming the proposed answer.

## What we're building

`chachaml` is a practical, REPL-first MLOps library for Clojure. Scope is
single-process / small-team. Core features: run tracking (params, metrics,
tags, env), artifact storage, and a model registry. UI, MCP server,
libpython-clj2 wrappers, and remote backends are explicitly deferred
post-v0.1.

## Architectural shape (target)

Layered, bottom-up, protocol-first:

```
core / tracking / registry / repl     ← public API
context / serialize / env             ← shared infra
store.protocol → store.sqlite         ← pluggable backend
```

Key constraints that drive design choices:

- **REPL-first.** Auto-init store on first use. No setup ceremony. Returns
  plain Clojure maps. Re-evaluating `(with-run …)` in the REPL must not
  leak open runs. `tap>` integration on completion.
- **Dynamic vars** (`*store*`, `*run*`) carry context — not threading IDs
  through every call.
- **Protocol-first storage.** `RunStore`, `ArtifactStore`, `ModelRegistry`,
  `Lifecycle` are separate protocols so backends (SQLite default; Postgres/
  S3 later) can be swapped without touching the API layer.
- **Filesystem artifacts.** SQLite stores metadata only; bytes live under
  `<root>/artifacts/<run-id>/<name>`. nippy is the default Clojure-value
  codec.
- **No global mutable state** except a single delay-initialized default
  store atom.
- **Errors in `with-run`** mark the run `:failed` and re-throw — never
  swallow.

## Build / dev commands

Both `deps.edn` and `project.clj` must be maintained — supporting both build
tools is a hard requirement (see `SPEC.md` non-functional requirements).
The Babashka task runner (`bb.edn`) is the convenient front door:

- `bb test`       — kaocha (deps-based)
- `bb test-watch` — watch mode
- `bb lein-test`  — proves the dual build still works
- `bb lint`       — clj-kondo, fails on warnings
- `bb fmt-check`  — cljfmt (no auto-fix)
- `bb fmt`        — cljfmt fix
- `bb coverage`   — cloverage gate (≥ 85% line / ≥ 80% branch)
- `bb ci`         — everything CI runs, locally

Direct equivalents live in `deps.edn` aliases for users without Babashka.

CI is GitHub Actions (`.github/workflows/ci.yml`) with a 4-job matrix
`{tool: deps|lein, jdk: 17|21}` plus lint/format and coverage gates.

## Quality bar

A change is mergeable when it clears the bar in `CONTRIBUTING.md`. CI
enforces lint, format, tests on both build tools, and coverage; reviewers
enforce ADR discipline, docstring/schema discipline on public API, and the
code conventions below.

The full quality engineering plan lives at
`/Users/jiriknesl/.claude/plans/proud-forging-sutherland.md`.

## Conventions for this codebase

- Prefer plain maps over records for domain data (runs, models, versions).
  Records only where protocol dispatch demands them (store impls).
- EDN is the on-disk format for human-readable fields (params, tags, env).
  Use `pr-str` / `clojure.edn/read-string`. Never `read-string` on
  untrusted input.
- Public API lives in `chachaml.core`; `chachaml.registry` and
  `chachaml.tracking` are separate namespaces but re-exported where it
  helps REPL discoverability.
- Namespaces under `chachaml.store.*` must not depend on anything above
  the store layer.

## Working with the planning docs

If you change the design, update `SPEC.md` / `PLAN.md` / `BACKLOG.md` in
the same change. The docs are the source of truth until code catches up.
Don't let them drift.
