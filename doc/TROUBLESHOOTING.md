# Troubleshooting

Observed failure modes and how to fix them. If your problem isn't
here, open an issue or check the [FAQ](FAQ.md) first.

## "database is locked" / `SQLITE_BUSY` errors

**Symptom:** writes fail with `SQLITE_BUSY` or "database is locked",
usually under concurrent access.

**Cause:** chachaml enables WAL mode on open, which allows one writer
+ many readers. If you have multiple processes writing simultaneously
(e.g. parallel hyperparameter sweeps across separate JVMs), bursts
can still collide.

**Fix:**

1. Single JVM with multiple threads → use one store, threads are
   serialised by the SQLite layer; you should not see this.
2. Multiple JVMs → switch to Postgres. SQLite is not designed for
   multi-process concurrency at scale. See
   [migrate-sqlite-to-postgres](howto/migrate-sqlite-to-postgres.md).

## Artifact loads return nil after a restart

**Symptom:** `(ml/load-artifact run-id "model")` returns `nil` or
throws "file not found" after restarting your app.

**Cause:** the artifact directory is paired with the DB file by name.
If you moved or renamed `chachaml.db` without also moving the
`chachaml-artifacts/` directory, the metadata in the DB still points
at the old location.

**Fix:** keep the `.db` file and the `*-artifacts/` directory together
when you move them. Or pass `:artifact-dir` explicitly to
`(chachaml.store/open ...)` if you have an unconventional layout.

## MCP tool not appearing in Claude Code

**Symptom:** you added chachaml to `.claude/mcp.json` but the tools
don't show up when you ask Claude.

**Diagnostic checklist:**

1. **Is the JSON valid?** Trailing commas and missing brackets are
   the most common cause.
2. **Did you restart the editor?** MCP config is read once at
   startup.
3. **Does `clojure -M:mcp` work standalone?**
   ```bash
   echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | clojure -M:mcp
   ```
   You should see a JSON response listing tools. If this hangs or
   errors, the MCP server itself is broken — fix that first.
4. **Is the working directory correct?** The `cwd` field in
   `mcp.json` must point at the project (so the MCP server can find
   `chachaml.db`).
5. **Check the Claude Code logs.** Failed MCP launches are visible
   in the MCP panel.

## libpython-clj2 native library mismatch

**Symptom:** Python interop crashes with errors mentioning
`libpython` or version mismatches.

**Cause:** libpython-clj2 binds to the Python in your `PATH` at JVM
start. If your `python3` is, say, 3.13 but you have packages installed
into 3.11, you'll see binding errors.

**Fix:** set `PYTHON_LIBRARY_PATH` (or `LD_LIBRARY_PATH` on Linux,
`DYLD_LIBRARY_PATH` on macOS) to the right `libpython` before starting
your JVM. A virtualenv aligned with the system `python3` is the
simplest path. Detailed setup is in
[track-sklearn-models](howto/track-sklearn-models.md).

## Postgres connection refused inside Docker

**Symptom:** `connection refused` when chachaml tries to reach
Postgres from a Docker container, even though `psql -h localhost`
works on the host.

**Cause:** inside a container, `localhost` is the container itself,
not the host machine.

**Fix:** in your `jdbc-url`, use the Docker service name (e.g.
`postgres`) when running via `docker-compose`, or
`host.docker.internal` for a host-mode containerised app. The example
in [team-deployment-docker](howto/team-deployment-docker.md) shows the
service-name variant.

## Slack webhook returns 404

**Symptom:** `chachaml.alerts/check-alerts!` reports "webhook returned
404 Not Found" when an alert fires.

**Cause:** the webhook URL is wrong or has been revoked. Slack
webhooks expire when the channel/app is deleted.

**Fix:** regenerate the webhook in Slack (Apps → Incoming Webhooks),
update the alert with `(alerts/set-alert! ...)` (which upserts).
Verify with `curl`:

```bash
curl -X POST -H 'Content-type: application/json' \
  --data '{"text":"chachaml test"}' \
  $WEBHOOK_URL
# expected: ok
```

## "No run is currently active" thrown from a logging call

**Symptom:** `(ml/log-metric :acc 0.9)` throws "No run is currently
active" outside of a `with-run`.

**Cause:** logging functions read `*run*`; if you forgot the
`with-run` wrapper, there's nothing to log to.

**Fix:** wrap the call. If you need to log to a specific run after
the fact, use `(binding [chachaml.context/*run* the-run-map] ...)` —
but this is a rare escape hatch; the normal path is `with-run`.

## Web UI charts are empty

**Symptom:** the `/runs/:id` page renders but no metric chart
appears.

**Cause:** Vega-Lite charts need at least two data points per metric
key (one for x-axis, one for the curve). If you only logged a single
final-value metric (no `step`), the chart degenerates to a single dot
and may render as empty depending on the metric's domain.

**Fix:** log time-series metrics with explicit `step`:

```clojure
(doseq [epoch (range n)]
  (ml/log-metric :loss (loss-at epoch) epoch))
```

Final-only metrics like `:accuracy` are still visible in the metrics
table; they just don't make for an interesting chart.

## `(ml/use-store! ...)` was called but writes still go to old store

**Symptom:** you switched to Postgres with `use-store!` mid-session,
but new runs still appear in the SQLite UI.

**Cause:** code running inside a `with-store` or `with-run` block
that started before the `use-store!` call has the old store bound
lexically. `use-store!` only changes the *default* — lexically bound
stores win.

**Fix:** finish the in-flight `with-run`, then start a new one. Or
restart the REPL if the boundary is unclear.

## "Could not locate next.jdbc" when running `bb docs`

**Symptom:** `bb docs` fails with `next.jdbc__init.class` not found.

**Cause:** alias composition with `:mcp` (which has `:main-opts`)
breaks dependency resolution under `clojure -X`. The `bb docs` task
already excludes `:mcp` for this reason; if you customised it, drop
`:mcp` from the chain.

**Fix:** the canonical chain is `:ui:postgres:s3:codox` (no `:mcp`).
The MCP namespace's only extra dep (`data.json`) is already pulled in
by `:ui`, so excluding `:mcp` is safe.

## Lein build can't find a namespace that `clj` can

**Symptom:** `lein test` fails to resolve a namespace that `clojure
-M:test` resolves fine.

**Cause:** `deps.edn` and `project.clj` are maintained side-by-side.
A new dependency added to `deps.edn` must be mirrored in
`project.clj`.

**Fix:** add the same coordinate to `project.clj`'s `:dependencies`
or `:profiles`. Run `bb ci` to verify both builds pass.
