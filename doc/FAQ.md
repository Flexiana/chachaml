# FAQ

Short answers to common questions. For setup walkthroughs see the
[how-to guides](howto/); for failure modes see
[Troubleshooting](TROUBLESHOOTING.md).

## Is chachaml an MLflow drop-in?

No. The shapes are deliberately Clojure-shaped (plain maps, dynamic
vars, REPL-friendly). There's no wire-compatibility with MLflow's
REST or proto APIs. Most concepts translate (run, experiment, model,
stage), but you can't point an MLflow client at a chachaml store.

## Is it production-ready?

For solo and small-team use, yes. v0.6 has 202 tests and 471
assertions, lint and format gates, ≥85% coverage, dual `deps.edn` +
`project.clj` builds, and is in active use. For multi-tenant SaaS or
hostile multi-user environments, no — there's no auth or row-level
security. Run it on a trusted network or behind an SSO proxy.

## Why dynamic vars instead of explicit state?

Because the primary interface is the REPL. Threading a store handle
and run id through every helper makes one-liners unusable. Dynamic
vars give the same scoping guarantees as `with-open` for the cost of
remembering `bound-fn` when you fork to a new thread. The full
discussion is in
[ADR-0004](adr/0004-dynamic-vars-for-context.md).

## Can I use Datomic / XTDB / SQLite-WASM?

Not as a built-in backend. The store protocol is small enough that
you could implement one yourself — see
[`chachaml.store.protocol`](https://cljdoc.org/d/com.flexiana/chachaml/CURRENT/api/chachaml.store.protocol)
and the SQLite/Postgres implementations. We won't ship more backends
ourselves until there's clear demand; SQLite + Postgres covers solo
and team use, and most "I want a different DB" requests turn out to
be "I want shared state", which Postgres already provides.

## How big can the SQLite file get?

Comfortably tens of GB. SQLite has been verified on much larger
workloads, but ML-tracking writes are small and frequent, which is
SQLite's sweet spot. The constraint isn't usually DB size — it's
artifact disk usage. Use
[`archive-runs!` / `delete-archived!`](howto/clean-up-old-runs.md) to
prune.

## Can multiple processes write to the same SQLite file?

Yes — SQLite supports it via WAL mode, which chachaml enables on
open. Concurrent writers serialise at the SQLite level. For more
than ~3 active writers (or any cross-machine setup), switch to
Postgres.

## Do I need Python for any of this?

Only for the sklearn interop layer (`chachaml.interop.sklearn`),
which uses libpython-clj2. Everything else — tracking, registry,
pipelines, alerts, web UI, MCP, chat — is pure Clojure. The interop
layer uses `requiring-resolve`, so it compiles fine without Python on
the classpath; it just throws if you call it.

## Where do artifacts actually live?

On disk, in a directory paired with the SQLite file:

```
./chachaml.db
./chachaml-artifacts/
  └── <run-id>/
      └── <artifact-name>
```

The DB stores metadata only. This is deliberate
([ADR-0005](adr/0005-filesystem-backed-artifacts.md)) — it lets you
tar them, copy them, or upload them with standard tooling. Backups
must capture both the `.db` *and* the artifact directory.

## Can two versions of a model be in production at once?

No. Production is exclusive
([ADR-0006](adr/0006-production-stage-exclusivity.md)). Promoting a
new version atomically demotes the previous one to `:archived`.
History is preserved; the previous artifact is still loadable by
explicit version number.

## How is `:tags` different from `:notes`?

Tags are key/value metadata, structured, queryable
(`(ml/runs {:tag :reviewed})`). Notes are free-form markdown
attached to one run, intended for "explanation in prose, including
LaTeX math if you want." Tags are typed and filterable; notes are
human-readable.

## What's the difference between `with-run` and `deftracked`?

`with-run` is a macro you wrap any block of code in; the run starts
when the macro fires and ends on body completion. `deftracked` is a
`defn`-shaped wrapper — every call to the function opens its own run.
Use `with-run` for ad-hoc REPL work, `deftracked` for production code
where the function should always be tracked.

## Can I run chachaml in a serverless / FaaS environment?

It works, but it's not the design centre. SQLite needs a writable
filesystem, which most serverless runtimes don't durably provide. For
Lambda-style usage, point chachaml at a managed Postgres + use S3 for
artifacts (set `:artifact-store` in the store config).

## How do I attribute runs to a user without per-user accounts?

Set `CHACHA_USER` in the user's shell profile (or pass `:created-by`
explicitly to `start-run!`). The library captures it at run start and
exposes it on every run map. `(ml/my-runs)` and `(ml/my-last-run)`
filter by the current value.

## Does chachaml send any telemetry?

No. The library makes zero outbound network calls of its own. The
chat feature and Slack alerts are user-initiated and the destination
is configured by you.
