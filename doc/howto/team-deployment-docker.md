# Team deployment with Docker

Stand up Postgres + the chachaml UI on a single host so a small team
can share runs.

## Goal

A `docker compose up` that gives everyone on the team a running
chachaml UI, persistent shared storage, and a Postgres they can
point their REPLs at.

## Prerequisites

- Docker + docker-compose (or `docker compose` plugin).
- A trusted network. The included compose file does not add auth;
  put it behind a VPN or SSO proxy if it's reachable from outside.

## Steps

### 1. The compose file

The repo ships a working `docker-compose.yml`:

```yaml
services:
  postgres:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: chachaml
      POSTGRES_USER: chachaml
      POSTGRES_PASSWORD: chachaml
    volumes:
      - pg-data:/var/lib/postgresql/data
    ports:
      - "5432:5432"
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U chachaml"]
      interval: 5s
      timeout: 5s
      retries: 5

  chachaml:
    build: .
    environment:
      DB_TYPE: postgres
      JDBC_URL: jdbc:postgresql://postgres:5432/chachaml
      DB_USER: chachaml
      DB_PASSWORD: chachaml
      ARTIFACT_DIR: /data/artifacts
      PORT: "8080"
    ports:
      - "8080:8080"
    volumes:
      - artifact-data:/data/artifacts
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  pg-data:
  artifact-data:
```

### 2. Start it

```bash
docker compose up -d
```

The `chachaml` image builds from the `Dockerfile` in the repo. First
run pulls Postgres and compiles the chachaml image (~2 minutes).

### 3. Verify

- UI: `http://<host>:8080/runs`
- Postgres: `psql -h <host> -U chachaml -d chachaml` (password
  `chachaml`).

From a teammate's REPL:

```clojure
(ml/use-store!
 (store/open {:type     :postgres
              :jdbc-url "jdbc:postgresql://<host>:5432/chachaml"
              :username "chachaml"
              :password "chachaml"}))

(ml/with-run {:experiment "team-smoke"}
  (ml/log-metric :hello 1.0))
```

Browse to `http://<host>:8080/runs` — the teammate's run is visible.

### 4. Production hardening

The defaults are *demo* defaults. Before letting humans rely on this:

- **Change the Postgres password.** Set it from a `.env` file or your
  secrets manager rather than in the compose file.
- **Don't expose the Postgres port publicly.** Drop the `5432:5432`
  port mapping if everyone connects via the UI's network.
- **Put auth in front of the UI.** oauth2-proxy, Cloudflare Access,
  Tailscale, or your existing SSO. The UI itself has no auth layer
  by design.
- **Back up the Postgres volume *and* the artifact volume.** They're
  separate; backing up only the DB silently loses every artifact
  ([ADR-0005](../adr/0005-filesystem-backed-artifacts.md)).
- **Pin image tags.** Replace `postgres:16-alpine` with a specific
  digest if you want reproducible builds.

### 5. (Optional) S3 for artifacts

For larger teams or cross-region access, point the artifact store at
S3 instead of the local volume:

```yaml
chachaml:
  environment:
    ARTIFACT_STORE: s3
    S3_BUCKET: ml-artifacts
    S3_PREFIX: chachaml/
    AWS_REGION: us-east-1
    # AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY via secrets
```

See [use-s3-for-artifacts](use-s3-for-artifacts.md) (P1).

## Troubleshooting

- **"connection refused" from another container** — inside Docker,
  use the service name (`postgres`), not `localhost`. The compose
  network resolves service names automatically.
- **UI starts but `/runs` is blank** — verify the chachaml container
  picked up the `JDBC_URL` env var (`docker compose logs chachaml`).
  If it fell back to SQLite, you'll see runs you create through *that
  container's* SQLite, but teammates connecting to Postgres will
  see nothing.
- **`docker compose down -v` deleted everything** — `-v` removes
  named volumes. That's intentional for resetting; for a non-destructive
  stop use `docker compose down` (no `-v`).
