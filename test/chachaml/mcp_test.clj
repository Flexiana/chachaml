(ns chachaml.mcp-test
  "Tests for the MCP server. Calls `handle-message` directly
  (the testable core) and also `run-server!` with piped streams
  for an integration test."
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [chachaml.mcp :as mcp]
            [chachaml.registry :as reg]
            [chachaml.test-helpers :as h]
            [clojure.data.json :as json]
            [clojure.string]
            [clojure.test :refer [deftest is testing use-fixtures]])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))

(use-fixtures :each h/with-fresh-store)

;; --- Helpers ---------------------------------------------------------

(defn- request
  "Build a JSON-RPC 2.0 request map."
  [id method & [params]]
  {"jsonrpc" "2.0" "id" id "method" method "params" (or params {})})

(defn- result-text
  "Extract the first text content from a tool-call response."
  [resp]
  (get-in resp ["result" "content" 0 "text"]))

;; --- Protocol handshake ---------------------------------------------

(deftest initialize-returns-server-info
  (let [resp (mcp/handle-message (request 1 "initialize"))]
    (is (= 1 (get resp "id")))
    (is (= "2024-11-05" (get-in resp ["result" "protocolVersion"])))
    (is (= "chachaml" (get-in resp ["result" "serverInfo" "name"])))))

(deftest notifications-produce-no-response
  (is (nil? (mcp/handle-message
             {"jsonrpc" "2.0" "method" "notifications/initialized"}))))

(deftest ping-returns-empty-result
  (let [resp (mcp/handle-message (request 1 "ping"))]
    (is (= {} (get resp "result")))))

(deftest unknown-method-returns-error
  (let [resp (mcp/handle-message (request 1 "bogus/method"))]
    (is (= -32601 (get-in resp ["error" "code"])))))

;; --- tools/list ------------------------------------------------------

(deftest tools-list-returns-all-tools
  (let [resp (mcp/handle-message (request 1 "tools/list"))
        tool-names (mapv #(get % "name") (get-in resp ["result" "tools"]))]
    (is (>= (count tool-names) 5))
    (is (some #{"list_runs"} tool-names))
    (is (some #{"get_run"} tool-names))
    (is (some #{"compare_runs"} tool-names))
    (is (some #{"list_models"} tool-names))
    (is (some #{"get_model"} tool-names))))

;; --- tools/call: list_runs ------------------------------------------

(deftest list-runs-on-empty-store
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "list_runs" "arguments" {}}))]
    (is (clojure.string/includes? (result-text resp) "(no runs)"))))

(deftest list-runs-with-data
  (ml/with-run {:experiment "test"})
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "list_runs"
                        "arguments" {"experiment" "test"}}))]
    (is (clojure.string/includes? (result-text resp) "test"))))

;; --- tools/call: get_run --------------------------------------------

(deftest get-run-existing
  (let [rid (atom nil)]
    (ml/with-run {:experiment "demo"}
      (reset! rid (:id (ctx/current-run)))
      (ml/log-params {:lr 0.01})
      (ml/log-metric :acc 0.9))
    (let [resp (mcp/handle-message
                (request 1 "tools/call"
                         {"name" "get_run"
                          "arguments" {"run_id" @rid}}))]
      (is (clojure.string/includes? (result-text resp) "demo"))
      (is (clojure.string/includes? (result-text resp) ":lr")))))

(deftest get-run-missing
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "get_run"
                        "arguments" {"run_id" "no-such"}}))]
    (is (clojure.string/includes? (result-text resp) "No run found"))))

;; --- tools/call: compare_runs ---------------------------------------

(deftest compare-runs-tool
  (ml/with-run {:experiment "a"} (ml/log-params {:lr 0.01}))
  (Thread/sleep 5)
  (ml/with-run {:experiment "b"} (ml/log-params {:lr 0.05}))
  (let [ids  (mapv :id (ml/runs))
        resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "compare_runs"
                        "arguments" {"run_ids" ids}}))]
    (is (clojure.string/includes? (result-text resp) "Comparing 2 runs"))))

(deftest compare-runs-too-few
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "compare_runs"
                        "arguments" {"run_ids" ["only-one"]}}))]
    (is (clojure.string/includes? (result-text resp) "at least 2"))))

;; --- tools/call: list_models ----------------------------------------

(deftest list-models-empty
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "list_models" "arguments" {}}))]
    (is (clojure.string/includes? (result-text resp) "(no models"))))

(deftest list-models-with-data
  (ml/with-run {} (ml/log-artifact "m" {:x 1})
               (reg/register-model "demo" {:artifact "m"}))
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "list_models" "arguments" {}}))]
    (is (clojure.string/includes? (result-text resp) "demo"))))

;; --- tools/call: get_model ------------------------------------------

(deftest get-model-existing
  (ml/with-run {} (ml/log-artifact "m" {:x 1})
               (reg/register-model "iris" {:artifact "m" :stage :staging}))
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "get_model"
                        "arguments" {"model_name" "iris"}}))]
    (is (clojure.string/includes? (result-text resp) "Model: iris"))))

(deftest get-model-missing
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "get_model"
                        "arguments" {"model_name" "nope"}}))]
    (is (clojure.string/includes? (result-text resp) "No model named"))))

;; --- tools/call: unknown tool ----------------------------------------

(deftest unknown-tool
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "bogus_tool" "arguments" {}}))]
    (is (true? (get-in resp ["result" "isError"])))))

;; --- v0.4 MCP tools --------------------------------------------------

(deftest mcp-search-runs
  (ml/with-run {:experiment "s"} (ml/log-metric :accuracy 0.95))
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "search_runs"
                        "arguments" {"metric_key" "accuracy"
                                     "op" ">" "metric_value" 0.9}}))]
    (is (some? (result-text resp)))))

(deftest mcp-best-run
  (ml/with-run {:experiment "b"} (ml/log-metric :accuracy 0.9))
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "best_run"
                        "arguments" {"metric" "accuracy"}}))]
    (is (some? (result-text resp)))))

(deftest mcp-add-tag-and-get
  (let [rid (atom nil)]
    (ml/with-run {} (reset! rid (:id (ctx/current-run))))
    (mcp/handle-message
     (request 1 "tools/call"
              {"name" "add_tag"
               "arguments" {"run_id" @rid "key" "quality" "value" "good"}}))
    (let [resp (mcp/handle-message
                (request 2 "tools/call"
                         {"name" "get_tags"
                          "arguments" {"run_id" @rid}}))]
      (is (clojure.string/includes? (result-text resp) "quality")))))

(deftest mcp-set-note
  (let [rid (atom nil)]
    (ml/with-run {} (reset! rid (:id (ctx/current-run))))
    (let [resp (mcp/handle-message
                (request 1 "tools/call"
                         {"name" "set_note"
                          "arguments" {"run_id" @rid "note" "test note"}}))]
      (is (clojure.string/includes? (result-text resp) "Note saved")))))

(deftest mcp-list-experiments
  (ml/create-experiment! "mcp-test" {:description "test"})
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "list_experiments" "arguments" {}}))]
    (is (clojure.string/includes? (result-text resp) "mcp-test"))))

(deftest mcp-export-runs
  (ml/with-run {:experiment "ex"} (ml/log-params {:lr 0.01}))
  (let [resp (mcp/handle-message
              (request 1 "tools/call"
                       {"name" "export_runs" "arguments" {"limit" 5}}))]
    (is (clojure.string/includes? (result-text resp) ":lr"))))

;; --- Integration: stdio round-trip -----------------------------------

(deftest ^:integration stdio-round-trip
  (testing "run-server! reads from an InputStream and writes to an OutputStream"
    (let [input  (str (json/write-str (request 1 "initialize")) "\n"
                      (json/write-str (request 2 "tools/list")) "\n")
          in     (ByteArrayInputStream. (.getBytes input "UTF-8"))
          out    (ByteArrayOutputStream.)
          store  (ctx/current-store)]
      (mcp/run-server! store in out)
      (let [lines  (clojure.string/split-lines (.toString out "UTF-8"))
            parsed (mapv json/read-str (remove clojure.string/blank? lines))]
        (is (= 2 (count parsed)))
        (is (= "chachaml" (get-in (first parsed)
                                  ["result" "serverInfo" "name"])))
        (is (seq (get-in (second parsed) ["result" "tools"])))))))
