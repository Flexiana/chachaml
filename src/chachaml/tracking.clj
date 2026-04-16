(ns chachaml.tracking
  "Function-level tracking macros.

  Provides `deftracked`, a `defn`-shaped macro that wraps the body in
  a chachaml run. Each call:

  - Opens a run (or nests under the current one — `with-run` already
    propagates `parent-run-id`).
  - Marks the run `:completed` on normal return, `:failed` on
    exception, and re-throws.

  The body still calls `chachaml.core/log-params`,
  `chachaml.core/log-metrics`, etc. explicitly. We intentionally do
  not auto-log function arguments: most ML inputs (datasets, models)
  are too large or not EDN-serializable.

  Supports single-arity definitions in v0.1.")

(defmacro deftracked
  "Define a function whose body is wrapped in a chachaml run.

  Shape:

      (deftracked fn-name [args] body)
      (deftracked fn-name docstring [args] body)
      (deftracked fn-name opts-map [args] body)
      (deftracked fn-name docstring opts-map [args] body)

  `opts-map` accepts:
  - `:experiment` string, default = the *current* namespace name
  - `:name`       string, default = the fn symbol's local name
  - `:tags`       map merged into the run tags. A `:tracked-fn` tag
                  carrying the qualified symbol is always added.

  The wrapped fn returns the body's value on success and re-throws
  exceptions. The created run is available via
  `chachaml.context/current-run` inside the body."
  {:arglists '([fn-name args & body]
               [fn-name docstring args & body]
               [fn-name opts args & body]
               [fn-name docstring opts args & body])}
  [fn-name & more]
  (let [[docstring more]       (if (string? (first more))
                                 [(first more) (rest more)]
                                 [nil more])
        [opts arg-list & body] (if (map? (first more))
                                 more
                                 (cons {} more))
        ns-str     (str *ns*)
        qualified  (str ns-str "/" (name fn-name))
        experiment (:experiment opts ns-str)
        run-name   (:name opts (name fn-name))
        extra-tags (:tags opts)
        defn-args  (cond-> [fn-name]
                     docstring (conj docstring)
                     :always   (conj arg-list))]
    `(defn ~@defn-args
       (chachaml.core/with-run
        {:experiment ~experiment
         :name       ~run-name
         :tags       (merge {:tracked-fn ~qualified} ~extra-tags)}
         ~@body))))
