# Wire up Slack alerts

Send a Slack message every time a chachaml metric crosses a
threshold.

## Goal

You log accuracy on every training run. You want to know, in
`#ml-alerts`, whenever a run's accuracy drops below 0.9 — without
checking the dashboard.

## Prerequisites

- Slack workspace + permission to install an incoming webhook.
- A working chachaml project with at least one run that has the
  metric you want to alert on.

## Steps

### 1. Create a Slack incoming webhook

1. <https://api.slack.com/apps> → "Create New App" → "From scratch".
2. Pick a name (e.g. `chachaml`) and your workspace.
3. In the app, "Incoming Webhooks" → toggle on → "Add New Webhook to
   Workspace" → pick a channel.
4. Copy the webhook URL. It looks like
   `https://hooks.slack.com/services/T.../B.../...`.

Verify it works:

```bash
curl -X POST -H 'Content-type: application/json' \
  --data '{"text":"chachaml alerts wired up"}' \
  $WEBHOOK_URL
# → ok
```

You should see the message in the channel.

### 2. Define the alert

```clojure
(require '[chachaml.alerts :as alerts])

(alerts/set-alert! "accuracy-regression"
                   {:experiment   "iris"
                    :metric-key   :accuracy
                    :op           :<
                    :threshold    0.9
                    :webhook-url  "https://hooks.slack.com/services/T.../B.../..."})
```

`set-alert!` is upsert-shaped: calling it again with the same name
replaces the existing alert. The op can be any of `:<`, `:>`, `:<=`,
`:>=`, `:=`.

### 3. Run the alert checker

`check-alerts!` evaluates every defined alert against the latest run
in each alert's experiment. It posts to the webhook on breach and
appends to the alert's history table.

```clojure
(alerts/check-alerts!)
;; => {:checked 1 :triggered 1 :webhook-results [...]}
```

You should see a Slack message in `#ml-alerts`.

### 4. Schedule it

`check-alerts!` is a one-shot evaluation. Schedule it however you
schedule jobs — a few options:

- **In-process scheduler** — `at-at`, `chime`, or a simple
  `(future (loop [] (Thread/sleep 60000) (alerts/check-alerts!) (recur)))`.
- **External cron** — drop a tiny script in `cron`/`systemd-timer`
  that does `clj -e "(require '[chachaml.alerts :as a]) (a/check-alerts!)"`.
- **CI on every merge** — add an `alerts.yml` workflow that runs
  after training jobs complete.

The library deliberately doesn't pick a scheduler; whatever you
already use for periodic work is fine.

### 5. Inspect history

```clojure
(alerts/alert-history "accuracy-regression")
;; => [{:fired-at ... :run-id "..." :metric-value 0.78 :webhook-status 200} ...]

(alerts/alerts)
;; => list of all defined alerts

(alerts/deactivate-alert! "accuracy-regression")
;; turn off without deleting
```

## Troubleshooting

- **Webhook returns 404** — the webhook URL is wrong, or it was
  deleted. Regenerate in Slack and `set-alert!` again. See
  [Troubleshooting](../TROUBLESHOOTING.md#slack-webhook-returns-404).
- **`check-alerts!` reports 0 triggered when you expected one** —
  the alert evaluates the *latest* run's metric value, not all runs.
  If your most recent run didn't log the metric, the alert silently
  passes.
- **Slack messages are unformatted** — the webhook payload chachaml
  sends is `{"text": "..."}`. For richer formatting, post-process the
  message yourself or use a Slack workflow that re-formats inbound
  webhooks.

## Where to go next

- For a dedicated alert relay (PagerDuty, Opsgenie, Discord) — adapt
  the same pattern; `:webhook-url` is just an HTTPS POST endpoint
  with a `text` field. Custom formatters are a planned but
  unimplemented extension; until then, run the checker yourself and
  reformat in your own glue code.
