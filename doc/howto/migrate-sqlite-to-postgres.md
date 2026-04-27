# Migrate from SQLite to Postgres

You started solo with the default SQLite store. Now a colleague is
joining and you want shared state. This guide swaps the backend
without touching application code.

## Goal

Move a working SQLite-backed chachaml setup to a shared Postgres
instance. Same API, same map shapes, same UI.

## Prerequisites

- A reachable Postgres instance (16+ recommended). For local
  experimentation: `docker run -d --name pg -p 5432:5432 -e POSTGRES_PASSWORD=secret postgres:16-alpine`.
- A database created for chachaml: `createdb chachaml` or via SQL:
  `CREATE DATABASE chachaml;`.
- The `:postgres` alias in your `deps.edn` (already there in this
  project).

## Steps

### 1. Install the Postgres deps

The `:postgres` alias bundles the JDBC driver + HikariCP connection
pool:

```clojure
:postgres {:extra-deps {org.postgresql/postgresql {:mvn/version "42.7.4"}
                        com.zaxxer/HikariCP       {:mvn/version "6.2.1"}}}
```

Start your REPL with the alias:

```bash
clj -A:postgres
```

### 2. Open the Postgres store

```clojure
(require '[chachaml.core :as ml]
         '[chachaml.store :as store])

(ml/use-store!
 (store/open {:type     :postgres
              :jdbc-url "jdbc:postgresql://localhost:5432/chachaml"
              :username "chachaml"
              :password "secret"}))
```

The schema is created automatically on first connect (idempotent).

### 3. Verify

```clojure
(ml/with-run {:experiment "smoke-test"}
  (ml/log-metric :hello 1.0))

(ml/last-run)
;; => {:id "..." :experiment "smoke-test" ...}
```

Open the UI against the same backend:

```bash
DB_TYPE=postgres \
JDBC_URL=jdbc:postgresql://localhost:5432/chachaml \
DB_USER=chachaml \
DB_PASSWORD=secret \
clojure -M:ui:postgres
```

Browse to `http://localhost:8080/runs` and you should see your test
run.

### 4. (Optional) Move existing data

Schemas are compatible at the conceptual level but not at the SQL
level — column names and types are tuned per backend. There's no
built-in migrator. Two practical options:

1. **Cut over and start fresh.** For a small team, the simplest path
   is to keep both stores around for a week, then archive the SQLite
   file once everyone has migrated.
2. **Replay through the API.** If you need history, write a small
   script that reads runs from the SQLite store and re-logs them
   through the public API. Sketch:

   ```clojure
   (require '[chachaml.store.sqlite :as sqlite])

   (def src (sqlite/open {:path "old.db"}))
   (def dst (store/open {:type :postgres ...}))

   (chachaml.core/with-store dst
     (doseq [r (chachaml.core/with-store src (ml/runs {:limit 10000}))]
       (ml/with-run (select-keys r [:experiment :name :tags])
         (ml/log-params (:params r))
         (doseq [m (:metrics r)]
           (ml/log-metric (:key m) (:value m) (:step m))))))
   ```

   Note: this re-creates runs with new ids and new timestamps; it's a
   *replay*, not a 1:1 mirror.

## Troubleshooting

- **"connection refused"** — check `psql -h ... -U chachaml -d chachaml`
  works from the same machine. If you're running chachaml inside
  Docker, `localhost` won't reach the host; use the service name or
  `host.docker.internal`. See [Troubleshooting](../TROUBLESHOOTING.md).
- **Schema migration errors** — on first connect chachaml runs DDL
  to create tables. The DB user must have `CREATE` privileges on the
  database. If you're using a least-privilege user, run the DDL once
  as a superuser, then drop create privileges.
- **Slow first write** — HikariCP pool warm-up can add ~100ms to the
  first query in a session. Steady-state writes are sub-ms.

## Where to go next

- [team-deployment-docker](team-deployment-docker.md) — same Postgres
  + UI in one `docker compose up`.
- [share-runs-across-team.md](share-runs-across-team.md) (P1) — user
  attribution and `my-runs` filtering.
