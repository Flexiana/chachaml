# MCP server reference

`chachaml.mcp` exposes 16 tools to MCP-compatible LLM clients via
JSON-RPC over stdio. Once wired up, an agent like Claude Code,
Cursor, or VS Code + Continue can read your runs, models, and
artifacts the same way the REPL and web UI do.

This page is the *reference* for those tools — input schemas, sample
calls, and the kinds of questions each tool answers. For a setup
walkthrough, see
[configure-mcp-with-claude-code](howto/configure-mcp-with-claude-code.md).

## Running the server

```bash
clojure -M:mcp                    # default ./chachaml.db
clojure -M:mcp /path/to/other.db  # custom DB path
```

The process speaks MCP JSON-RPC on stdin/stdout — don't expect
console output beyond startup logs on stderr.

## Tool catalogue

### `list_runs`

List recent runs, optionally filtered.

**Input:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `experiment` | string | no | Filter by experiment name |
| `status` | enum | no | `running`, `completed`, `failed` |
| `limit` | integer | no | Default 20 |

**Sample question:** *"Show me the last 10 runs in the iris experiment."*

### `get_run`

Full details of one run: params, metrics, artifacts, environment.

**Input:** `run_id` (string, required).

**Sample question:** *"What params did run abc-123 use?"*

### `compare_runs`

Diff params and final metrics across two or more runs.

**Input:** `run_ids` (array of strings, required, ≥2).

**Sample question:** *"Compare runs abc and def — which params changed?"*

### `search_runs`

Find runs by metric threshold.

**Input:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `experiment` | string | no | |
| `metric_key` | string | no | Metric to filter on |
| `op` | enum | no | `>` `>=` `<` `<=` `=` |
| `metric_value` | number | no | Threshold |
| `limit` | integer | no | |

**Sample question:** *"Find iris runs with accuracy above 0.95."*

### `best_run`

The run with the best value for a metric.

**Input:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `experiment` | string | no | |
| `metric` | string | yes | The metric key |
| `direction` | enum | no | `max` (default) or `min` |

**Sample question:** *"Which kmeans run has the lowest final-inertia?"*

### `list_models`

All registered models in the registry.

**Input:** none.

**Sample question:** *"What models are in production?"*

### `get_model`

A model's details and version history.

**Input:** `model_name` (string, required).

**Sample question:** *"Show me the version history of iris-classifier."*

### `get_model_version`

Metadata for a specific version, or the latest version at a given
stage.

**Input:**

| Field | Type | Required | Notes |
|---|---|---|---|
| `model_name` | string | yes | |
| `version` | integer | no | Specific version number |
| `stage` | enum | no | `none` `staging` `production` `archived` (default `production`) |

**Sample question:** *"What's currently in production for kmeans-prod?"*

### `diff_model_versions`

Compare the source runs behind two versions of a model.

**Input:** `model_name` (string), `v1` (integer), `v2` (integer) — all required.

**Sample question:** *"What changed between v3 and v4 of iris-classifier?"*

### `add_tag`

Add or update a mutable tag on a run. Works after the run completed.

**Input:** `run_id`, `key`, `value` — all required strings.

**Sample question:** *"Tag run abc with reviewed=yes."*

### `set_note`

Set a markdown note on a run. Supports LaTeX math.

**Input:** `run_id`, `note` — both required strings.

**Sample question:** *"Add a note to run abc explaining why we chose
this model."*

### `get_tags`

All mutable tags for a run.

**Input:** `run_id` (string, required).

**Sample question:** *"What tags are on run abc?"*

### `get_datasets`

Dataset metadata logged for a run via `(ml/log-dataset! ...)`.

**Input:** `run_id` (string, required).

**Sample question:** *"Which dataset did run abc train on?"*

### `list_experiments`

All experiments with metadata (name, description, owner).

**Input:** none.

**Sample question:** *"What experiments do we have?"*

### `create_experiment`

Create or update experiment metadata.

**Input:** `name` (required), `description`, `owner` — all strings.

**Sample question:** *"Create an experiment called fraud-detection
owned by maria."*

### `export_runs`

Export flat records (params + final metrics per run). Useful when the
agent wants to compute aggregate stats locally.

**Input:** `experiment` (string), `limit` (integer) — both optional.

**Sample question:** *"Give me a CSV-ready dump of every iris run."*

## Tool design notes

- Every read tool returns a *formatted text* result (a table, an
  `inspect` dump). The agent sees the same view a REPL user would.
- Write-capable tools (`add_tag`, `set_note`, `create_experiment`)
  intentionally avoid touching params, metrics, or artifacts — those
  are append-only after a run ends. Agents can annotate; they can't
  rewrite history.
- The MCP server runs against the same store as your REPL. There's no
  parallel data path; the agent reads the truth.

## Securing the agent path

The MCP process has full read access to your store and limited write
access (tags, notes, experiments). If you ran your agent in an
untrusted context, that surface is what they'd see. For team setups,
point the agent at a Postgres instance with appropriate DB-level grants
rather than the production SQLite file.
