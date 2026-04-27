# How-to guides

Task-oriented recipes. Each guide starts with a concrete goal, lists
prerequisites, and walks through copy-paste steps with a verification
section at the end.

For a learning-oriented introduction, see the
[Tutorial](../TUTORIAL.md). For the *why* behind decisions, see
[Design & rationale](../DESIGN.md).

## Available now

- [Migrate SQLite → Postgres](migrate-sqlite-to-postgres.md) —
  swap the backend without changing app code.
- [Team deployment with Docker](team-deployment-docker.md) —
  Postgres + UI in one `docker compose up`.
- [Configure MCP with Claude Code](configure-mcp-with-claude-code.md)
  — let an agent query your runs.
- [Wire up Slack alerts](wire-up-slack-alerts.md) — webhook on
  metric breach.
- [Track sklearn models](track-sklearn-models.md) — libpython-clj2
  + `train-and-evaluate!`.
- [Log from Python or curl](log-from-python-or-curl.md) — HTTP
  write API end-to-end.

## Planned

- Use S3 for artifacts (MinIO local, AWS S3 prod)
- Clean up old runs in CI / cron
- Build a multi-step pipeline
- Run a hyperparameter grid search
- Share runs across a team (user attribution)
- Back up the database
- Compare runs and model versions

If a recipe you need isn't here, file an issue with the use-case.
The library has all the primitives; the gaps are in the writing.
