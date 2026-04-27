# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project status

chachaml is at **v0.5.0** — a feature-complete MLOps library with 202
tests / 471 assertions. All milestones M0–M15 are shipped.

## What this is

A practical, REPL-first MLOps library for Clojure. Track experiments,
store artifacts, manage models, run pipelines, set alerts, and chat
with your data. Supports both SQLite (default, single-machine) and
Postgres (team use).

## Architecture

```
chachaml.core + registry + tracking + pipeline + alerts   ← public API
chachaml.chat                                             ← LLM analysis
chachaml.mcp                                              ← MCP server (16 tools)
chachaml.ui.{server,api,views,charts,layout}              ← web UI (8 pages)
chachaml.interop.sklearn                                  ← Python bridge (optional)
chachaml.format + repl                                    ← shared formatting + REPL
chachaml.context + serialize + env + schema               ← infra
chachaml.store.{protocol,sqlite,postgres}                 ← storage backends
chachaml.store                                            ← backend dispatcher
```

**Namespace dependency rule**: lower layers must not import higher
layers. Store → context → core → {registry, repl, tracking, mcp, ui}.

## Build / dev commands

```
bb test          # kaocha
bb lint          # clj-kondo (fails on warnings)
bb fmt-check     # cljfmt
bb coverage      # cloverage ≥ 85% line gate
bb lein-test     # proves dual build
bb ci            # all of the above
```

Both `deps.edn` and `project.clj` are maintained.

## Code style (user preference)

- Code reads **left to right** — use `->`, `->>` threading macros
- **Not too nested** — max 2-3 levels of indentation inside a fn body
- Use **`let` for intermediate values** — name things instead of nesting
- **Functional, not OOP** — plain fns + maps, not gratuitous protocols
- Side-effecting fns end with `!`
- Public vars must have docstrings
- Run `clj-kondo` after every file change

## Key files

| Purpose | File |
|---|---|
| Public API | `src/chachaml/core.clj` |
| Model registry | `src/chachaml/registry.clj` |
| SQLite backend | `src/chachaml/store/sqlite.clj` |
| Postgres backend | `src/chachaml/store/postgres.clj` |
| Store dispatcher | `src/chachaml/store.clj` |
| Protocols | `src/chachaml/store/protocol.clj` |
| MCP server | `src/chachaml/mcp.clj` |
| Web UI server | `src/chachaml/ui/server.clj` |
| Pipelines | `src/chachaml/pipeline.clj` |
| Alerts | `src/chachaml/alerts.clj` |
| Chat with data | `src/chachaml/chat.clj` |
| Shared formatters | `src/chachaml/format.clj` |
| 25 ML examples | `examples/ml_showcase.clj` |
| Test fixture | `test/chachaml/test_helpers.clj` |
| User-facing tutorial | `doc/TUTORIAL.md` |
| Design rationale | `doc/DESIGN.md` |
| Web UI tour | `doc/WEB_UI.md` |
| MCP tools reference | `doc/MCP.md` |
| FAQ + troubleshooting | `doc/FAQ.md`, `doc/TROUBLESHOOTING.md` |
| How-to guides | `doc/howto/` |
| cljdoc bundle config | `doc/cljdoc.edn` |

## Quality bar

See `CONTRIBUTING.md`. Summary: lint clean, format clean, both test
runners green, coverage ≥ 85% line, docstrings on public vars, Malli
schemas for API inputs.

## Working with docs

Keep `CHANGELOG.md`, `doc/BACKLOG.md`, and `README.md` in sync with
code changes. ADRs live in `doc/adr/`.

User-facing docs follow the [Diátaxis](https://diataxis.fr) split:
`TUTORIAL.md` (learning), `DESIGN.md` (explanation), `doc/howto/`
(task recipes), and `doc/MCP.md` / `doc/WEB_UI.md` (reference). Refer
users to `doc/TUTORIAL.md` for end-to-end onboarding rather than the
README's inline snippets. The cljdoc bundle is configured at
`doc/cljdoc.edn`; codox runs via `bb docs`.
