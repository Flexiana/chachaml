# Contributing to chachaml

Thanks for your interest in contributing. chachaml is built for the long
haul — please read this guide before opening a PR.

## Setup

You need:

- JDK 17 or 21 (we test both in CI)
- [Clojure CLI](https://clojure.org/guides/install_clojure) ≥ 1.12
- [Leiningen](https://leiningen.org/) (we maintain dual build support)
- [Babashka](https://babashka.org/) (optional but recommended for task
  shortcuts)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) on your PATH for
  the editor experience

## Common tasks

```
bb test          # kaocha
bb test-watch    # kaocha watch mode
bb lint          # clj-kondo, fails on warnings
bb fmt           # apply cljfmt
bb fmt-check     # check only
bb coverage      # cloverage with 85% line gate
bb lein-test     # run tests under Leiningen
bb ci            # everything CI runs, locally
```

If you don't use Babashka, the equivalent `clojure -M:…` aliases are in
`deps.edn`.

## Running a single test

Kaocha supports focus by namespace, var, or `:focus` metadata:

```
clojure -M:test --focus chachaml.store.sqlite-test
clojure -M:test --focus chachaml.store.sqlite-test/round-trip
```

## Quality bar

A PR is mergeable when **all** of these are true:

1. Public vars have docstrings; public API additions have a Malli schema
   in `chachaml.schema`.
2. New behavior has tests. Bug fixes include a regression test.
3. `bb lint` reports zero issues.
4. `bb fmt-check` reports no drift.
5. `bb coverage` passes (≥ 85% line; form/branch tracked in `target/coverage/index.html`).
6. Both `clojure -M:test` and `lein test` pass.
7. Public API changes have a `CHANGELOG.md` entry under `## [Unreleased]`.
8. Architectural decisions of consequence have a new ADR in `doc/adr/`.

CI enforces 1–7 (docstrings via clj-kondo `:missing-docstring`, the rest
via the listed tools). The reviewer enforces ADRs and judgment calls.

## Conventions

- Side-effecting fns end with `!`.
- Private impl fns are marked `^:private` or live in a `*.impl`
  namespace.
- No `clojure.core/read-string` on untrusted input — only
  `clojure.edn/read-string`.
- No global mutable state except documented exceptions
  (`chachaml.context/*default-store*` is the only one).
- Namespace dependencies flow downward only; see the layering diagram
  in `CLAUDE.md`.
- Avoid `def` at the top level for mutable state — use `defonce` or
  `delay`.
- Multimethods over conditional dispatch when extension is intended.
- Records only where protocol dispatch demands; otherwise plain maps.
- Prefer destructuring in fn args over `(:key m)` chains.

## ADRs

Architectural decisions live in `doc/adr/NNNN-short-title.md` using the
[Nygard format](https://cognitect.com/blog/2011/11/15/documenting-architecture-decisions).
ADRs are append-only: a superseded ADR remains, with a reference to the
ADR that superseded it.

Add an ADR when you:

- Pick a new core dependency.
- Change a protocol shape.
- Make a decision that future contributors might be tempted to reverse
  without your context.

## Commit and PR

- Keep commits focused. Prefer small PRs.
- PR title is short; PR body uses the template (`.github/PULL_REQUEST_TEMPLATE.md`).
- Squash-merge is the default.

## Reporting bugs / requesting features

Use the issue templates under `.github/ISSUE_TEMPLATE/`. For bugs,
include a minimal repro. For features, link to a use case.
