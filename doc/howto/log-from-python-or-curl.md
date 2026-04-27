# Log from Python (or curl, Go, anywhere)

Track runs from outside Clojure using chachaml's HTTP write API.

## Goal

A non-Clojure team member should be able to log a run, log
params/metrics, and end the run via REST. Useful for: Python training
scripts, Go services, GitHub Actions, anywhere a quick `curl` is
easier than a Clojure REPL.

## Prerequisites

- A running chachaml UI server (`clojure -M:ui` or the Docker
  setup). The write API lives under `/api/w/`.
- Network access to the UI server from the client.

## Steps

### 1. Bare curl, end-to-end

```bash
HOST=http://localhost:8080

# Start a run — returns the run id
RUN_ID=$(curl -s -X POST $HOST/api/w/runs \
  -H 'Content-Type: application/json' \
  -d '{"experiment":"iris","name":"from-python","tags":{"author":"maria"}}' \
  | jq -r .id)

echo "Run id: $RUN_ID"

# Log params
curl -X POST $HOST/api/w/runs/$RUN_ID/params \
  -H 'Content-Type: application/json' \
  -d '{"lr":0.01,"epochs":100}'

# Log a metric (single)
curl -X POST $HOST/api/w/runs/$RUN_ID/metrics \
  -H 'Content-Type: application/json' \
  -d '{"accuracy":0.94}'

# Log time-series metrics
for i in 0 1 2 3 4; do
  curl -X POST $HOST/api/w/runs/$RUN_ID/metrics \
    -H 'Content-Type: application/json' \
    -d "{\"loss\":$(echo "scale=3; 1.0 / ($i + 1)" | bc),\"step\":$i}"
done

# Optionally upload an artifact (raw bytes)
curl -X POST "$HOST/api/w/runs/$RUN_ID/artifacts?name=model.pkl" \
  -H 'Content-Type: application/octet-stream' \
  --data-binary @model.pkl

# End the run
curl -X POST $HOST/api/w/runs/$RUN_ID/end \
  -H 'Content-Type: application/json' \
  -d '{"status":"completed"}'
```

Verify in the UI: the run should appear in `/runs` with the params,
metrics, and artifact.

### 2. From Python

```python
import requests

HOST = "http://localhost:8080"
session = requests.Session()

# Start
r = session.post(f"{HOST}/api/w/runs",
                 json={"experiment": "iris",
                       "name": "from-python",
                       "tags": {"author": "maria"}})
r.raise_for_status()
run_id = r.json()["id"]

# Params
session.post(f"{HOST}/api/w/runs/{run_id}/params",
             json={"lr": 0.01, "epochs": 100})

# Metric per epoch
for epoch in range(100):
    session.post(f"{HOST}/api/w/runs/{run_id}/metrics",
                 json={"loss": 1.0 / (epoch + 1), "step": epoch})

# Final accuracy
session.post(f"{HOST}/api/w/runs/{run_id}/metrics",
             json={"accuracy": 0.94})

# End
session.post(f"{HOST}/api/w/runs/{run_id}/end",
             json={"status": "completed"})
```

### 3. (Optional) Register a model

```bash
curl -X POST $HOST/api/w/models \
  -H 'Content-Type: application/json' \
  -d "{\"name\":\"iris-classifier\",
       \"run_id\":\"$RUN_ID\",
       \"artifact\":\"model.pkl\",
       \"stage\":\"staging\",
       \"description\":\"first cut\"}"
```

The same exclusivity rule applies: promoting another version to
`:production` demotes the previous one.

## API surface

All endpoints accept and return JSON.

| Verb + path | Body | Returns |
|---|---|---|
| `POST /api/w/runs` | `{experiment, name?, tags?, parent_run_id?}` | `{id, ...}` |
| `POST /api/w/runs/:id/params` | `{key: value, ...}` | `{ok: true}` |
| `POST /api/w/runs/:id/metrics` | `{key: value, step?: n}` or `{key1: v1, key2: v2}` | `{ok: true}` |
| `POST /api/w/runs/:id/artifacts?name=...` | raw bytes | `{ok, sha256, size}` |
| `POST /api/w/runs/:id/end` | `{status: "completed"\|"failed", error?: "..."}` | `{ok: true}` |
| `POST /api/w/models` | `{name, run_id, artifact, stage?, description?}` | `{name, version, ...}` |

Read endpoints are at `/api/...` (no `w/` prefix); see
[Web UI tour: JSON API](../WEB_UI.md#the-json-api).

## Troubleshooting

- **`{ "error": "no such run" }` from `/params` or `/metrics`** —
  you're posting to a run id that doesn't exist or was already ended.
  Track the run id from the `/runs` response and stop logging after
  `/end`.
- **`Content-Type` mismatches** — params/metrics endpoints expect
  JSON; artifact uploads expect `application/octet-stream`. Mixing
  them returns a 400.
- **Performance** — each metric POST is one HTTP round-trip. For
  high-frequency time-series (e.g. logging per training step in a
  tight loop), batch metrics into one call: `{"loss": 0.4,
  "accuracy": 0.9, "step": 12}` logs both at the same step.

## Where to go next

- For higher-throughput pipelines, prefer the Clojure API directly —
  it skips the HTTP layer entirely.
- For language bindings, the API is small enough that any HTTP
  client works. There's no official Python package, by design — the
  REST surface is the contract.
