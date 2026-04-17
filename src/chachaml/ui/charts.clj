(ns chachaml.ui.charts
  "Vega-Lite spec builders for metric visualisation."
  (:require [clojure.data.json :as json]))

(defn metric-line-chart
  "Build a Vega-Lite JSON spec for a single metric's step-series.
  `rows` is a seq of `{:step :value}` maps."
  [metric-key rows]
  (json/write-str
   {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
    :width    "container"
    :height   200
    :title    (str metric-key)
    :data     {:values (mapv (fn [{:keys [step value]}]
                               {"step" step "value" value})
                             rows)}
    :mark     {:type "line" :point true}
    :encoding {:x {:field "step"  :type "quantitative" :title "Step"}
               :y {:field "value" :type "quantitative" :title (name metric-key)}}}))

(defn multi-run-line-chart
  "Build a Vega-Lite JSON spec overlaying the same metric from multiple
  runs. `series` is `[{:run-id \"…\" :rows [{:step :value} …]} …]`."
  [metric-key series]
  (let [values (mapcat (fn [{:keys [run-id rows]}]
                         (mapv (fn [{:keys [step value]}]
                                 {"step" step "value" value
                                  "run"  (subs run-id 0 (min 8 (count run-id)))})
                               rows))
                       series)]
    (json/write-str
     {:$schema  "https://vega.github.io/schema/vega-lite/v5.json"
      :width    "container"
      :height   250
      :title    (str metric-key " — run comparison")
      :data     {:values values}
      :mark     {:type "line" :point true}
      :encoding {:x     {:field "step"  :type "quantitative" :title "Step"}
                 :y     {:field "value" :type "quantitative" :title (name metric-key)}
                 :color {:field "run"   :type "nominal"      :title "Run"}}})))

(defn chart-div
  "Hiccup fragment that embeds a Vega-Lite chart. `spec-json` is the
  Vega-Lite JSON string; `dom-id` is a unique element id."
  [dom-id spec-json]
  [:div
   [:div {:id dom-id :class "w-full"}]
   [:script (str "vegaEmbed('#" dom-id "', " spec-json ", {actions: false});")]])
