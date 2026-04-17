(ns chachaml.format
  "Shared formatting utilities for human-readable output.

  Used by both `chachaml.repl` (terminal) and `chachaml.ui.views`
  (HTML). Centralised here to avoid duplication."
  (:import [java.time Duration Instant ZoneId]
           [java.time.format DateTimeFormatter]))

(def ^:private ts-formatter
  "Thread-safe timestamp formatter bound to the system timezone."
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd HH:mm:ss")
             (ZoneId/systemDefault)))

(defn fmt-instant
  "Format a millisecond-epoch timestamp as `yyyy-MM-dd HH:mm:ss`."
  [^long ms]
  (.format ts-formatter (Instant/ofEpochMilli ms)))

(defn fmt-duration
  "Format the elapsed time between two millisecond-epoch timestamps."
  [^long start ^long end]
  (let [d  (Duration/ofMillis (max 0 (- end start)))
        ms (.toMillis d)
        s  (/ ms 1000.0)]
    (cond
      (< ms 1000) (format "%dms" ms)
      (< s 60)    (format "%.1fs" s)
      (< s 3600)  (format "%dm %ds" (long (/ s 60)) (long (rem s 60)))
      :else       (format "%dh %dm" (long (/ s 3600)) (long (rem (/ s 60) 60))))))

(defn short-id
  "Truncate a UUID string to the first 8 characters for display."
  [id]
  (when id (subs (str id) 0 (min 8 (count (str id))))))

(defn size-str
  "Format a byte count as a human-readable string (B, KiB, MiB, GiB)."
  [^long n]
  (cond
    (< n 1024)                (str n " B")
    (< n (* 1024 1024))       (format "%.1f KiB" (/ n 1024.0))
    (< n (* 1024 1024 1024))  (format "%.2f MiB" (/ n 1024.0 1024.0))
    :else                     (format "%.2f GiB" (/ n 1024.0 1024.0 1024.0))))

(defn pad
  "Right-pad `s` to width `n` with spaces."
  [s n]
  (format (str "%-" n "s") (str s)))

(defn last-metric-value
  "Given a seq of metric rows for one key (sorted by step), return
  the value of the last step. Used for metric summaries."
  [rows]
  (:value (last (sort-by :step rows))))

(defn metric-summary
  "Extract the latest value per metric key from a seq of metric rows.
  Returns `{:accuracy 0.94 :loss 0.12}`."
  [metrics]
  (->> metrics
       (group-by :key)
       (reduce-kv (fn [acc k vs]
                    (assoc acc k (last-metric-value vs)))
                  {})))
