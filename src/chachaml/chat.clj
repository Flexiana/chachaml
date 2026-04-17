(ns chachaml.chat
  "Chat-with-data: LLM-powered analysis of chachaml experiments.

  Sends user questions to the Anthropic or OpenAI API along with
  chachaml tool definitions. The LLM can call tools (same as the MCP
  server) to query runs, compare experiments, find best models, etc.

  The API key is provided per-request (never stored server-side).
  The UI stores it in the browser's localStorage.

  Usage from REPL:

      (require '[chachaml.chat :as chat])

      (chat/ask \"Which experiment has the best accuracy?\"
                {:provider :anthropic
                 :api-key  \"sk-ant-...\"
                 :model    \"claude-sonnet-4-20250514\"})

  From the UI: the `/chat` page provides a textarea + provider
  selector. Messages are sent to `/api/chat` which proxies to the
  chosen provider."
  (:require [chachaml.mcp :as mcp]
            [clojure.data.json :as json]
            [clojure.walk :as walk])
  (:import [java.io OutputStreamWriter BufferedReader InputStreamReader]
           [java.net HttpURLConnection URL]))

;; --- Tool definitions for LLM API -----------------------------------

(defn- tools-for-api
  "Return the MCP tools formatted for the Anthropic tool_use API."
  []
  (mapv (fn [t]
          {:name        (:name t)
           :description (:description t)
           :input_schema (:inputSchema t)})
        @(resolve 'chachaml.mcp/tools)))

(defn- tools-for-openai
  "Return tools formatted for the OpenAI function calling API."
  []
  (mapv (fn [t]
          {:type     "function"
           :function {:name        (:name t)
                      :description (:description t)
                      :parameters  (:inputSchema t)}})
        @(resolve 'chachaml.mcp/tools)))

;; --- Tool execution --------------------------------------------------

(defn- execute-tool
  "Execute a chachaml tool by name with the given arguments map.
  Returns the text result."
  [tool-name args]
  (let [result (mcp/handle-tool tool-name args)]
    (get-in result [:content 0 :text] (pr-str result))))

;; --- HTTP helpers ----------------------------------------------------

(defn- http-post
  "POST JSON to a URL with headers. Returns parsed JSON response."
  [url headers body]
  (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
               (.setRequestMethod "POST")
               (.setDoOutput true)
               (.setConnectTimeout 30000)
               (.setReadTimeout 120000))]
    (doseq [[k v] headers]
      (.setRequestProperty conn k v))
    (with-open [w (OutputStreamWriter. (.getOutputStream conn))]
      (.write w ^String (json/write-str body))
      (.flush w))
    (let [status (.getResponseCode conn)
          stream (if (>= status 400)
                   (.getErrorStream conn)
                   (.getInputStream conn))]
      (with-open [r (BufferedReader. (InputStreamReader. stream))]
        (let [resp (json/read-str (slurp r) :key-fn keyword)]
          (when (>= status 400)
            (throw (ex-info (str "API error: " status)
                            {:status status :body resp})))
          resp)))))

;; --- Anthropic provider ----------------------------------------------

(defn- anthropic-chat
  "Send a message to the Anthropic Messages API with tools."
  [messages {:keys [api-key model] :or {model "claude-sonnet-4-20250514"}}]
  (http-post
   "https://api.anthropic.com/v1/messages"
   {"Content-Type"      "application/json"
    "x-api-key"         api-key
    "anthropic-version"  "2023-06-01"}
   {:model      model
    :max_tokens 4096
    :system     "You are an ML experiment analyst. You have access to chachaml tools to query experiment runs, metrics, models, and artifacts. Use the tools to answer the user's questions with specific data. Be concise."
    :tools      (tools-for-api)
    :messages   messages}))

(defn- anthropic-loop
  "Run the Anthropic tool-use loop until we get a text response."
  [question opts]
  (loop [messages [{:role "user" :content question}]
         iterations 0]
    (when (> iterations 10)
      (throw (ex-info "Too many tool iterations" {:iterations iterations})))
    (let [resp    (anthropic-chat messages opts)
          content (:content resp)
          stop    (:stop_reason resp)]
      (if (= "tool_use" stop)
        ;; Execute tool calls and continue
        (let [tool-blocks (filter #(= "tool_use" (:type %)) content)
              results     (mapv (fn [tb]
                                  {:type    "tool_result"
                                   :tool_use_id (:id tb)
                                   :content (execute-tool (:name tb) (walk/stringify-keys (:input tb)))})
                                tool-blocks)]
          (recur (into messages
                       [{:role "assistant" :content content}
                        {:role "user" :content results}])
                 (inc iterations)))
        ;; Return the text
        (let [text-blocks (filter #(= "text" (:type %)) content)]
          {:answer (apply str (map :text text-blocks))
           :iterations iterations})))))

;; --- OpenAI provider -------------------------------------------------

(defn- openai-chat
  "Send a message to the OpenAI Chat Completions API with tools."
  [messages {:keys [api-key model] :or {model "gpt-4o"}}]
  (http-post
   "https://api.openai.com/v1/chat/completions"
   {"Content-Type"  "application/json"
    "Authorization" (str "Bearer " api-key)}
   {:model    model
    :tools    (tools-for-openai)
    :messages messages}))

(defn- openai-loop
  "Run the OpenAI function calling loop."
  [question opts]
  (loop [messages [{:role "system" :content "You are an ML experiment analyst. Use the provided functions to query chachaml experiment data. Be concise."}
                   {:role "user" :content question}]
         iterations 0]
    (when (> iterations 10)
      (throw (ex-info "Too many tool iterations" {:iterations iterations})))
    (let [resp   (openai-chat messages opts)
          choice (get-in resp [:choices 0])
          msg    (:message choice)
          reason (:finish_reason choice)]
      (if (= "tool_calls" reason)
        (let [calls   (:tool_calls msg)
              results (mapv (fn [tc]
                              {:role         "tool"
                               :tool_call_id (:id tc)
                               :content      (execute-tool
                                              (get-in tc [:function :name])
                                              (json/read-str (get-in tc [:function :arguments])))})
                            calls)]
          (recur (into (conj messages msg) results)
                 (inc iterations)))
        {:answer (:content msg)
         :iterations iterations}))))

;; --- Public API ------------------------------------------------------

(defn ask
  "Ask a question about your experiments. The LLM uses chachaml tools
  to query your data and formulate an answer.

  `opts` must include:
  - `:provider`  `:anthropic` or `:openai`
  - `:api-key`   Your API key (never stored)
  - `:model`     Optional model override

  Returns `{:answer \"...\" :iterations N}`."
  [question opts]
  (case (:provider opts)
    :anthropic (anthropic-loop question opts)
    :openai    (openai-loop question opts)
    (throw (ex-info "Unknown provider" {:provider (:provider opts)}))))
