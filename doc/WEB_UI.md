# Web UI tour

chachaml ships a Ring/Jetty web UI that reads from the same store
your REPL writes to. It's not a separate service; it's a different
view onto your `chachaml.db` (or shared Postgres).

## Starting the UI

```bash
clojure -M:ui                          # default localhost:8080
clojure -M:ui --port 9090              # custom port
PORT=9090 clojure -M:ui                # via env var
```

Or from the REPL:

```clojure
(require '[chachaml.ui.server :as ui])
(ui/start! {:port 8080})
```

The server reads from whatever store your REPL is using
(`*store*` / `chachaml.db` by default). Switch to Postgres before
starting `start!` to point the UI at a shared backend.

## The pages

### `/` → redirects to `/runs`

There is no landing page. The first thing you want is the runs list.

### `/runs` — Runs dashboard

Every run, newest first. Columns: id, experiment, name, status,
created-by, started, duration, the headline metric (the most recently
logged value of any metric).

Filter by experiment via the dropdown. Click a column header to sort.
Each row links to `/runs/:id`.

**Tip:** if you log a metric called `:accuracy` or `:f1`, the runs
list shows it as the headline. Use a stable name across runs in an
experiment so the table is comparable.

### `/runs/:id` — Run detail

The full picture of one run:

- Header with status, experiment, name, tags, created-by, parent
  run, duration.
- **Params** table.
- **Metrics** — both the latest scalar values and a Vega-Lite chart
  per metric key (e.g. loss vs. step).
- **Artifacts** — name, size, content type, sha256. One-click
  download.
- **Notes** — markdown box; edit and save.
- **Tags** — add/remove inline.
- **Datasets** — anything logged via `(ml/log-dataset! ...)`.
- **Environment** — git sha, git branch, JVM, OS, captured at
  `start-run!`.

**Tip:** if a metric was logged with `step`, the chart x-axis is
step. If only one point exists, you'll see a single dot — fine for
final-only metrics.

### `/compare?ids=a,b,c` — Compare runs

Pick 2–N runs from the runs list (checkboxes + "Compare" button), or
craft the URL by hand. Side-by-side: params, scalar metrics, plus
overlaid metric curves on the same chart.

**Tip:** great for confirming a hyperparameter change moved a metric
in the right direction.

### `/experiments` — Experiments overview

Rows of experiments with their run counts, last-updated timestamps,
and the metadata you set with
`(ml/create-experiment! "name" {:description "..."})`.

### `/search` — Metric search

Free-form: pick a metric key, an operator, a threshold. Returns the
runs whose latest value of that metric satisfies the predicate.

Equivalent of `(ml/search-runs ...)` from the REPL.

### `/models` — Model registry

All registered models with their current production version,
description, and the version count.

### `/models/:name` — One model's history

Versions table with stages, descriptions, source run links, and a
one-click promote button. Promoting to `:production` instantly
demotes the previous production version (see
[ADR-0006](adr/0006-production-stage-exclusivity.md)).

The diff button between any two versions opens a side-by-side of the
artifact metadata, params from the source runs, and metrics.

### `/chat` — Chat with your data

Natural-language Q&A. Pick a provider (Anthropic or OpenAI), paste an
API key (stored in your browser's `localStorage`, never sent to the
chachaml server), ask a question. Behind the scenes the page POSTs to
`/api/chat`, which calls `chachaml.chat/ask`.

Example questions:

- "Which run in 'iris' has the best accuracy?"
- "Compare runs abc and def."
- "List the production models."

The model uses the MCP-equivalent tool set to query your store; it
never sees the data unless it asks for it.

## The JSON API

Every UI page has an underlying JSON endpoint, useful for scripting
and debugging:

| Read | Endpoint |
|---|---|
| List runs | `GET /api/runs?experiment=...&status=...` |
| Get run | `GET /api/runs/:id` |
| Compare | `GET /api/compare?ids=a,b` |
| Search | `GET /api/search?metric_key=accuracy&op=>&metric_value=0.9` |
| Export CSV | `GET /api/export?experiment=...` |
| List models | `GET /api/models` |
| Get model | `GET /api/models/:name` |
| Diff versions | `GET /api/diff/:name/:v1/:v2` |
| Experiments | `GET /api/experiments` |
| Artifact download | `GET /api/artifacts/:id/download` |
| Tags | `GET /api/tags/:id` · `POST /api/tags/:id` |
| Note | `POST /api/note/:id` |
| Datasets | `GET /api/datasets/:id` |
| Chat | `POST /api/chat` |

There's also a write API (`/api/w/...`) for non-Clojure clients:
`start-run`, `log-params`, `log-metrics`, `end-run`, `log-artifact`,
`register-model`. See
[log-from-python-or-curl](howto/log-from-python-or-curl.md) for an
end-to-end example.

## Production deployment

The UI is meant for trusted networks. There is no auth.

For team use, run the UI behind a corporate VPN, an SSO proxy
(oauth2-proxy, Cloudflare Access), or in a private subnet. The
[team-deployment-docker](howto/team-deployment-docker.md) how-to has
a `docker-compose.yml` that puts the UI on a private port.
