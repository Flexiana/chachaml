# Using a development version of chachaml from another Clojure repo

You don't need to publish chachaml to a Maven repository to try it from
another project. There are three reliable options depending on the build
tool and how tightly you want the two repos coupled.

## What's available right now

The current state of `master` (M1 + M2) gives you a working core API:

- `chachaml.core/with-run`, `start-run!`, `end-run!`
- `chachaml.core/log-params`, `log-param`, `log-metrics`, `log-metric`
- `chachaml.core/runs`, `run`, `last-run`
- `chachaml.core/use-store!`, `with-store`
- Default SQLite store at `./chachaml.db` (auto-created on first call)

Artifacts (M3), `deftracked` (M4), and the model registry (M5) are not
yet implemented.

## Option 1 — `:local/root` in `deps.edn` (recommended)

In the consuming project's `deps.edn`, add chachaml as a local-root
dependency:

```clojure
{:deps
 {chachaml/chachaml {:local/root "/Users/jiriknesl/Documents/src/chachaml"}}}
```

Then from a REPL:

```clojure
(require '[chachaml.core :as ml])

(ml/with-run {:experiment "demo"}
  (ml/log-params {:lr 0.01})
  (ml/log-metric :acc 0.91))

(ml/last-run)
;; => {:id "…", :experiment "demo", :status :completed, …}

(:params (ml/run (:id (ml/last-run))))
;; => {:lr 0.01}
```

Edits to chachaml's source are picked up the next time the consuming
JVM is started (or after `(require '[chachaml.core :as ml] :reload)`
in the REPL). No publish step.

## Option 2 — `lein install` to your local Maven repo

If the consuming project uses Leiningen and you want to depend on a
versioned snapshot:

```bash
cd /Users/jiriknesl/Documents/src/chachaml
lein install
```

This installs `chachaml-0.1.0-SNAPSHOT.jar` into `~/.m2/repository/`.
Then in the consumer's `project.clj`:

```clojure
:dependencies [[chachaml "0.1.0-SNAPSHOT"]]
```

Re-run `lein install` after each chachaml change.

## Option 3 — Leiningen `checkouts` (live source)

Leiningen supports a `checkouts/` directory that symlinks into another
project's source for live editing without `lein install`:

```bash
cd /path/to/consumer
mkdir -p checkouts
ln -s /Users/jiriknesl/Documents/src/chachaml checkouts/chachaml
```

You still need a placeholder dependency in `project.clj`:

```clojure
:dependencies [[chachaml "0.1.0-SNAPSHOT"]]
```

Restart the JVM after symlinking. Subsequent edits to chachaml are
visible immediately to the REPL.

## A 30-second smoke test

Anywhere you have Clojure CLI installed:

```bash
mkdir -p /tmp/chachaml-smoke && cd /tmp/chachaml-smoke

cat > deps.edn <<EOF
{:deps {chachaml/chachaml {:local/root "/Users/jiriknesl/Documents/src/chachaml"}}}
EOF

clojure -M -e '
(require (quote [chachaml.core :as ml]))
(ml/with-run {:experiment "smoke"}
  (ml/log-params {:lr 0.1})
  (ml/log-metric :acc 0.9))
(prn (ml/last-run))
'
```

Expected: a printed run map with `:status :completed`, plus a fresh
`/tmp/chachaml-smoke/chachaml.db` file.

To inspect the database directly:

```bash
sqlite3 /tmp/chachaml-smoke/chachaml.db \
  "SELECT id, experiment, status, name FROM runs;"
```

## Choosing the store location

By default chachaml writes to `./chachaml.db` in the consumer's working
directory. To override:

```clojure
(ml/use-store! {:path "/path/to/runs.db"})
```

For tests, use an in-memory store that disappears at JVM exit:

```clojure
(ml/use-store! {:in-memory? true})
```

Or scope it locally:

```clojure
(require '[chachaml.store.sqlite :as sqlite])

(ml/with-store (sqlite/open {:in-memory? true})
  (ml/with-run {} (ml/log-metric :acc 1.0))
  (ml/last-run))
```

## Caveats while chachaml is pre-1.0

- Public API may change between milestones; check
  `CHANGELOG.md` before upgrading.
- The default store path is project-local. Add `chachaml.db*` and
  `chachaml-artifacts/` to your consumer's `.gitignore` (the entries
  are already in chachaml's own `.gitignore` for reference).
- `:in-memory?` mode holds a single open Connection and is not
  thread-safe; it's intended for tests.
