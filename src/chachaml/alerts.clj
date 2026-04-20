(ns chachaml.alerts
  "Metric alerts and drift detection.

  Define alerts on metrics (e.g. 'accuracy < 0.9 in experiment X').
  After each run, call `check-alerts!` to evaluate all active alerts
  against recent runs. Triggered alerts are recorded in the
  `alert_events` table.

  Usage:

      (require '[chachaml.alerts :as alerts])

      (alerts/set-alert! \"acc-drop\"
        {:experiment \"production\"
         :metric-key :accuracy
         :op         :<
         :threshold  0.9})

      ;; After a run completes:
      (alerts/check-alerts!)  ; returns triggered events

      (alerts/alert-history \"acc-drop\")  ; all past triggers"
  (:require [chachaml.context :as ctx]
            [chachaml.core :as ml]
            [next.jdbc :as jdbc])
  (:import [java.io OutputStreamWriter]
           [java.net HttpURLConnection URL]))

(defn set-alert!
  "Create or replace an alert rule. Returns the alert map.

  Required keys in `opts`:
  - `:metric-key`   keyword
  - `:op`           one of `:>`, `:>=`, `:<`, `:<=`, `:=`
  - `:threshold`    number

  Optional:
  - `:experiment`   scope to an experiment (nil = all)
  - `:webhook-url`  URL to POST when triggered (e.g. Slack incoming webhook)"
  [alert-name {:keys [experiment metric-key op threshold webhook-url]}]
  (let [store    (ctx/current-store)
        alert-id (str (random-uuid))
        now      (System/currentTimeMillis)]
    (jdbc/execute-one!
     (:datasource store)
     ["INSERT OR REPLACE INTO alerts (id, name, experiment, metric_key, op,
                                      threshold, active, webhook_url, created_at)
       VALUES (?, ?, ?, ?, ?, ?, 1, ?, ?)"
      alert-id alert-name experiment (name metric-key) (name op)
      (double threshold) webhook-url now])
    {:id alert-id :name alert-name :experiment experiment
     :metric-key metric-key :op op :threshold threshold
     :webhook-url webhook-url}))

(defn alerts
  "List all active alerts."
  []
  (let [store (ctx/current-store)]
    (->> (jdbc/execute!
          (:datasource store)
          ["SELECT * FROM alerts WHERE active = 1 ORDER BY name"])
         (mapv (fn [row]
                 (cond-> {:id         (:alerts/id row)
                          :name       (:alerts/name row)
                          :experiment (:alerts/experiment row)
                          :metric-key (keyword (:alerts/metric_key row))
                          :op         (keyword (:alerts/op row))
                          :threshold  (:alerts/threshold row)
                          :active     (= 1 (:alerts/active row))
                          :created-at (:alerts/created_at row)}
                   (:alerts/webhook_url row)
                   (assoc :webhook-url (:alerts/webhook_url row))))))))

(defn deactivate-alert!
  "Deactivate an alert by name (doesn't delete history)."
  [alert-name]
  (let [store (ctx/current-store)]
    (jdbc/execute-one!
     (:datasource store)
     ["UPDATE alerts SET active = 0 WHERE name = ?" alert-name])))

(defn- post-webhook!
  "POST a JSON payload to a webhook URL. Best-effort — logs errors but
  doesn't throw so alert processing continues."
  [url payload]
  (try
    (let [conn (doto ^HttpURLConnection (.openConnection (URL. url))
                 (.setRequestMethod "POST")
                 (.setDoOutput true)
                 (.setConnectTimeout 5000)
                 (.setReadTimeout 10000)
                 (.setRequestProperty "Content-Type" "application/json"))]
      (with-open [w (OutputStreamWriter. (.getOutputStream conn))]
        (.write w ^String payload)
        (.flush w))
      (.getResponseCode conn))
    (catch Exception e
      (binding [*out* *err*]
        (println "[chachaml-alerts] Webhook failed:" (ex-message e))))))

(defn- slack-payload
  "Build a Slack-compatible JSON payload for a triggered alert."
  [event]
  (let [text (str ":warning: *Alert triggered: " (:alert-name event) "*\n"
                  "Metric `" (name (:metric-key event)) "` = "
                  (:metric-value event) " "
                  (name (:op event)) " " (:threshold event) "\n"
                  "Run: `" (:run-id event) "`")]
    (str "{\"text\":" (pr-str text) "}")))

(defn- eval-op [op actual threshold]
  (case op
    :>  (> actual threshold)
    :>= (>= actual threshold)
    :<  (< actual threshold)
    :<= (<= actual threshold)
    :=  (== actual threshold)
    false))

(defn check-alerts!
  "Evaluate all active alerts against the most recent run per experiment.
  Returns a seq of triggered events.

  Call this after training runs complete (e.g. at the end of a pipeline
  or in a post-run hook)."
  []
  (let [store  (ctx/current-store)
        active (alerts)
        now    (System/currentTimeMillis)]
    (vec
     (for [alert active
           :let  [runs (ml/search-runs
                        (cond-> {:sort-by-metric (:metric-key alert)
                                 :limit 1}
                          (:experiment alert) (assoc :experiment (:experiment alert))))
                  latest-run (first runs)]
           :when latest-run
           :let  [run-detail (ml/run (:id latest-run))
                  metrics    (:metrics run-detail)
                  matching   (filter #(= (:metric-key alert) (:key %)) metrics)
                  latest-val (when (seq matching)
                               (:value (last (sort-by :step matching))))]
           :when (and latest-val (eval-op (:op alert) latest-val (:threshold alert)))]
       (let [event-id (str (random-uuid))]
         (jdbc/execute-one!
          (:datasource store)
          ["INSERT INTO alert_events (id, alert_id, run_id, metric_value, triggered_at)
            VALUES (?, ?, ?, ?, ?)"
           event-id (:id alert) (:id latest-run) (double latest-val) now])
         (jdbc/execute-one!
          (:datasource store)
          ["UPDATE alerts SET last_checked = ?, last_triggered = ? WHERE id = ?"
           now now (:id alert)])
         (let [event {:alert-name   (:name alert)
                      :run-id       (:id latest-run)
                      :metric-key   (:metric-key alert)
                      :metric-value latest-val
                      :threshold    (:threshold alert)
                      :op           (:op alert)
                      :triggered-at now}]
           ;; Fire webhook if configured
           (when-let [url (:webhook-url alert)]
             (post-webhook! url (slack-payload event)))
           event))))))

(defn alert-history
  "Get all past trigger events for an alert."
  [alert-name]
  (let [store (ctx/current-store)]
    (->> (jdbc/execute!
          (:datasource store)
          ["SELECT ae.*, a.name as alert_name
            FROM alert_events ae JOIN alerts a ON ae.alert_id = a.id
            WHERE a.name = ?
            ORDER BY ae.triggered_at DESC" alert-name])
         (mapv (fn [row]
                 {:alert-name   (:alert_name row)
                  :run-id       (:alert_events/run_id row)
                  :metric-value (:alert_events/metric_value row)
                  :triggered-at (:alert_events/triggered_at row)})))))
