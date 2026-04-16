(ns chachaml.context
  "Dynamic context for chachaml.

  Two dynamic vars carry implicit context through the call stack so that
  user code does not need to thread store/run identifiers explicitly:

  - `*store*` — the active backend. Bound by `chachaml.core/use-store!`
    or `chachaml.core/with-store`.
  - `*run*`   — the active run map inside a `with-run` block.

  When `*store*` is unbound, `current-store` falls back to a single
  delay-initialized default backend opened against `./chachaml.db`.

  See ADR-0004 for the rationale behind dynamic vars over alternatives."
  (:require [chachaml.store.sqlite :as sqlite]))

(def ^:dynamic *store*
  "Currently bound store. Use `current-store` rather than reading directly."
  nil)

(def ^:dynamic *run*
  "Currently active run map inside a `with-run` block, or nil."
  nil)

(defonce ^:private default-store-delay
  (delay (sqlite/open)))

(defn current-store
  "Return the bound `*store*`, falling back to the default SQLite store.

  The default store is created lazily on first call and lives for the
  duration of the JVM."
  []
  (or *store* @default-store-delay))

(defn current-run
  "Return the currently active run map, or nil if not inside `with-run`."
  []
  *run*)

(defn require-run!
  "Return the current run, throwing if there isn't one. Used by the
  public API to give a clear error when `log-*` is called outside a
  `with-run` block."
  []
  (or *run*
      (throw (ex-info "No active chachaml run; call inside (with-run …)"
                      {:type ::no-active-run}))))
