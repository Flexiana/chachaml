# Configure MCP with Claude Code

Wire chachaml's MCP server into Claude Code so the agent can read
your runs, models, and tags directly.

## Goal

After ~5 minutes of config, you can ask Claude things like:

> "Which run in the iris experiment has the best F1?"
> "Compare runs abc and def — which params changed?"
> "Add a `reviewed=yes` tag to the production version of kmeans-prod."

…and it answers from your actual chachaml store.

## Prerequisites

- Claude Code installed (`claude` on your `PATH`).
- A chachaml project that already contains some runs. If you don't
  have any yet, follow the [Tutorial](../TUTORIAL.md) up to step 5.
- The `clojure` CLI on your `PATH` (Claude Code will exec it).

## Steps

### 1. Smoke-test the MCP server standalone

Before involving Claude, verify the server starts and answers a
`tools/list` request:

```bash
cd /path/to/your/project
echo '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | clojure -M:mcp
```

You should see a JSON response listing 16 tools (`list_runs`,
`get_run`, `compare_runs`, ...). If this errors or hangs, fix that
before continuing.

### 2. Add chachaml to `.claude/mcp.json`

Inside your project (or `~/.claude/` for a user-wide config), create
or edit `.claude/mcp.json`:

```json
{
  "mcpServers": {
    "chachaml": {
      "command": "clojure",
      "args": ["-M:mcp"],
      "cwd": "/absolute/path/to/your/project"
    }
  }
}
```

Notes:

- `cwd` must be **absolute**. Relative paths are resolved relative to
  Claude Code's working directory, not yours.
- If your `chachaml.db` lives somewhere unusual, pass the path as an
  arg: `"args": ["-M:mcp", "/path/to/store.db"]`.
- For a Postgres-backed team setup, set the `DB_TYPE`, `JDBC_URL`,
  `DB_USER`, `DB_PASSWORD` env vars in an `"env"` map — same vars
  the UI Docker setup uses.

### 3. Restart Claude Code

MCP config is read at startup. Quit and reopen Claude Code (or run
`/mcp` to inspect connected servers).

### 4. Try a question

```
> Show me the most recent runs in the kmeans experiment.
```

Claude should respond with a table of runs, having called
`list_runs` under the hood. You can verify the tool call in the MCP
panel.

### 5. (Optional) Use the agent for tags / notes

The MCP server has limited write capability — `add_tag`, `set_note`,
`create_experiment` — but never touches params, metrics, or
artifacts (those are append-only). Try:

```
> Find the run with the lowest final-inertia and tag it "champion".
```

Claude calls `best_run` then `add_tag`.

## Troubleshooting

See the [Troubleshooting page](../TROUBLESHOOTING.md#mcp-tool-not-appearing-in-claude-code).
The most common gotchas:

1. Invalid JSON in `mcp.json`.
2. `cwd` is relative or wrong.
3. Forgot to restart Claude Code.
4. The standalone smoke test fails — fix MCP itself first.

## Other clients

The same JSON-RPC server works with any MCP-compatible client:

- **Cursor**: `Settings → MCP → Add Server`, point at the same
  command.
- **VS Code + Continue**: add to `~/.continue/config.json` under
  `mcpServers`.

The contract is JSON-RPC over stdio; whatever MCP-aware client you
use should "just work" if it can spawn a subprocess.
