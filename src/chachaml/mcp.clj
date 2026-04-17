(ns chachaml.mcp
  "MCP (Model Context Protocol) server for chachaml.

  Exposes experiment runs, models, and artifacts as MCP tools over
  stdio (JSON-RPC 2.0). Run as a subprocess by an MCP client such as
  Claude Code, VS Code + Continue, etc.

  Start from the command line:

      clojure -M:mcp              # uses ./chachaml.db
      clojure -M:mcp path/to.db   # custom DB path

  Or configure in `.claude/mcp.json`:

      {\"chachaml\": {
         \"command\": \"clojure\",
         \"args\": [\"-M:mcp\"],
         \"cwd\": \"/path/to/project\"}}

  The server reads one JSON-RPC message per line from stdin and writes
  one response per line to stdout. Logging goes to stderr."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.registry :as reg]
            [chachaml.repl :as repl]
            [chachaml.store.sqlite :as sqlite]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]))

;; --- Tool definitions ------------------------------------------------

(def ^:private tools
  [{:name        "list_runs"
    :description "List recent ML experiment runs. Returns a formatted table."
    :inputSchema {:type       "object"
                  :properties {"experiment" {:type        "string"
                                             :description "Filter by experiment name"}
                               "status"     {:type        "string"
                                             :enum        ["running" "completed" "failed"]
                                             :description "Filter by run status"}
                               "limit"      {:type        "integer"
                                             :description "Max results (default 20)"}}}}
   {:name        "get_run"
    :description "Get full details of a run: params, metrics, artifacts, env."
    :inputSchema {:type       "object"
                  :properties {"run_id" {:type        "string"
                                         :description "The run UUID"}}
                  :required   ["run_id"]}}
   {:name        "compare_runs"
    :description "Diff params and final metrics across 2+ runs."
    :inputSchema {:type       "object"
                  :properties {"run_ids" {:type        "array"
                                          :items       {:type "string"}
                                          :description "Two or more run UUIDs to compare"}}
                  :required   ["run_ids"]}}
   {:name        "list_models"
    :description "List all registered models in the registry."
    :inputSchema {:type "object" :properties {}}}
   {:name        "get_model"
    :description "Get a model's details and version history."
    :inputSchema {:type       "object"
                  :properties {"model_name" {:type        "string"
                                             :description "The registered model name"}}
                  :required   ["model_name"]}}
   {:name        "get_model_version"
    :description "Get metadata for a specific model version or the latest production/staging version."
    :inputSchema {:type       "object"
                  :properties {"model_name" {:type        "string"
                                             :description "The registered model name"}
                               "version"    {:type        "integer"
                                             :description "Specific version number (omit for stage-based lookup)"}
                               "stage"      {:type        "string"
                                             :enum        ["none" "staging" "production" "archived"]
                                             :description "Find latest version with this stage (default: production)"}}
                  :required   ["model_name"]}}])

;; --- Tool handlers ---------------------------------------------------

(defn- text-result
  "Wrap a string as an MCP tool result."
  [s]
  {:content [{:type "text" :text s}]})

(defn- run-filters [args]
  (cond-> {:limit (or (get args "limit") 20)}
    (get args "experiment") (assoc :experiment (get args "experiment"))
    (get args "status")     (assoc :status (keyword (get args "status")))))

(defmulti handle-tool
  "Dispatch a tool call by name. Returns `{:content [{:type :text :text ...}]}`."
  (fn [tool-name _args] tool-name))

(defmethod handle-tool "list_runs" [_ args]
  (text-result (with-out-str (repl/runs-table (run-filters args)))))

(defmethod handle-tool "get_run" [_ args]
  (let [run-id (get args "run_id")]
    (if-let [r (ml/run run-id)]
      (text-result (with-out-str (repl/inspect r)))
      (text-result (str "No run found with id " run-id)))))

(defmethod handle-tool "compare_runs" [_ args]
  (let [ids (get args "run_ids")]
    (if (and (sequential? ids) (>= (count ids) 2))
      (let [cmp (repl/compare-runs ids)]
        (text-result (with-out-str (repl/print-comparison cmp))))
      (text-result "Provide at least 2 run_ids to compare."))))

(defmethod handle-tool "list_models" [_ _]
  (let [models (reg/models)]
    (if (empty? models)
      (text-result "(no models registered)")
      (text-result
       (with-out-str
         (doseq [m models]
           (repl/inspect m)
           (println)))))))

(defmethod handle-tool "get_model" [_ args]
  (let [model-name (get args "model_name")]
    (if-let [m (reg/model model-name)]
      (text-result (with-out-str (repl/inspect m)))
      (text-result (str "No model named " model-name)))))

(defmethod handle-tool "get_model_version" [_ args]
  (let [model-name (get args "model_name")
        selector   (cond
                     (get args "version") {:version (get args "version")}
                     (get args "stage")   {:stage (keyword (get args "stage"))}
                     :else                {})
        versions   (reg/model-versions model-name)
        matcher    (fn [ver]
                     (cond
                       (:version selector) (= (:version ver) (:version selector))
                       (:stage selector)   (= (:stage ver) (:stage selector))
                       :else               (= :production (:stage ver))))
        v          (first (filter matcher versions))]
    (if v
      (text-result (with-out-str (repl/inspect v)))
      (text-result (str "No matching version for " model-name
                        (when (seq selector)
                          (str " " (pr-str selector))))))))

(defmethod handle-tool :default [tool-name _]
  {:content [{:type "text" :text (str "Unknown tool: " tool-name)}]
   :isError true})

;; --- JSON-RPC 2.0 handler -------------------------------------------

(def ^:private server-info
  {:name    "chachaml"
   :version "0.2.0"})

(def ^:private capabilities
  {:tools {}})

(defn handle-message
  "Process a parsed JSON-RPC message map and return a response map,
  or nil for notifications (no `:id`). This is the testable entry point;
  the stdio loop in `-main` is a thin wrapper."
  [msg]
  (let [id     (get msg "id")
        method (get msg "method")
        params (get msg "params")]
    (try
      (let [result
            (case method
              "initialize"
              {:protocolVersion "2024-11-05"
               :serverInfo      server-info
               :capabilities    capabilities}

              "notifications/initialized" nil ; ACK, no response

              "ping" {}

              "tools/list"
              {:tools tools}

              "tools/call"
              (handle-tool (get params "name")
                           (get params "arguments"))

              ;; unknown method
              (throw (ex-info "Method not found"
                              {:code -32601 :method method})))]
        (when (and id result)
          {"jsonrpc" "2.0"
           "id"      id
           "result"  (walk/stringify-keys result)}))
      (catch Exception e
        (when id
          (let [data (ex-data e)]
            {"jsonrpc" "2.0"
             "id"      id
             "error"   (cond-> {"code"    (or (:code data) -32603)
                                "message" (or (ex-message e) "Internal error")}
                         data (assoc "data" (pr-str data)))}))))))

;; --- Stdio loop ------------------------------------------------------

(defn- log [& args]
  (binding [*out* *err*]
    (apply println "[chachaml-mcp]" args)))

(defn run-server!
  "Start the MCP server loop. Reads JSON-RPC from `in` and writes
  responses to `out`. Intended for stdio but also usable with test
  streams."
  [store in out]
  (binding [ctx/*store* store]
    (let [reader (io/reader in)
          writer (io/writer out)]
      (doseq [line (line-seq reader)
              :when (not (clojure.string/blank? line))]
        (when-let [resp (handle-message (json/read-str line))]
          (.write writer ^String (json/write-str resp))
          (.write writer "\n")
          (.flush writer))))))

(defn -main
  "Entry point: `clojure -M:mcp [db-path]`. Opens the store and starts
  the stdio MCP server."
  [& args]
  (let [path  (or (first args) "chachaml.db")
        store (sqlite/open {:path path})]
    (log "Starting MCP server on" path)
    (run-server! store System/in System/out)))
