(ns chachaml.ui.views
  "Hiccup view functions for the chachaml web UI.

  Each public function returns a hiccup data structure (or a full HTML
  string via `layout/page`) for one screen. Views are pure functions of
  their data arguments — no side effects, no store access."
  (:require [chachaml.format :as fmt]
            [chachaml.ui.charts :as charts]
            [chachaml.ui.layout :as layout]))

;; --- View-specific helpers -------------------------------------------

(defn- status-badge
  "Render a colored status pill."
  [status]
  (let [colors (case status
                 :completed "bg-green-100 text-green-800"
                 :failed    "bg-red-100 text-red-800"
                 :running   "bg-yellow-100 text-yellow-800"
                 "bg-gray-100 text-gray-800")]
    [:span {:class (str "px-2 py-0.5 rounded text-xs font-medium " colors)}
     (name status)]))

(defn- stage-badge
  "Render a colored stage pill."
  [stage]
  (let [colors (case stage
                 :production "bg-green-100 text-green-800"
                 :staging    "bg-blue-100 text-blue-800"
                 :archived   "bg-gray-100 text-gray-500"
                 "bg-gray-100 text-gray-600")]
    [:span {:class (str "px-2 py-0.5 rounded text-xs font-medium " colors)}
     (name stage)]))

;; --- Runs dashboard --------------------------------------------------

(defn runs-page
  "Landing page: table of recent runs."
  [runs experiments current-experiment current-status]
  (layout/page "Runs"
               [:div {:class "flex items-center justify-between mb-4"}
                [:h1 {:class "text-2xl font-bold"} "Experiment Runs"]
                [:form {:class "flex gap-2" :method "get" :action "/runs"}
                 [:select {:name "experiment" :class "border rounded px-2 py-1 text-sm"}
                  [:option {:value ""} "All experiments"]
                  (for [e experiments]
                    [:option (cond-> {:value e}
                               (= e current-experiment) (assoc :selected true))
                     e])]
                 [:select {:name "status" :class "border rounded px-2 py-1 text-sm"}
                  [:option {:value ""} "All statuses"]
                  (for [s ["completed" "failed" "running"]]
                    [:option (cond-> {:value s}
                               (= s current-status) (assoc :selected true))
                     s])]
                 [:button {:type "submit"
                           :class "bg-indigo-600 text-white px-3 py-1 rounded text-sm hover:bg-indigo-700"}
                  "Filter"]
                 [:a {:href (str "/api/export?experiment=" (or current-experiment ""))
                      :class "bg-gray-200 text-gray-700 px-3 py-1 rounded text-sm hover:bg-gray-300"}
                  "Export CSV"]]]

               [:div {:hx-get     (str "/runs?experiment=" (or current-experiment "")
                                       "&status=" (or current-status ""))
                      :hx-trigger "every 10s"
                      :hx-swap    "innerHTML"
                      :hx-select  "#runs-table"}
                [:table {:id "runs-table"
                         :class "w-full text-sm border-collapse"}
                 [:thead
                  [:tr {:class "text-left border-b bg-gray-100"}
                   [:th {:class "p-2"} ""]
                   [:th {:class "p-2"} "ID"]
                   [:th {:class "p-2"} "Experiment"]
                   [:th {:class "p-2"} "Name"]
                   [:th {:class "p-2"} "Status"]
                   [:th {:class "p-2"} "Started"]
                   [:th {:class "p-2"} "Duration"]]]
                 [:tbody
                  (if (empty? runs)
                    [:tr [:td {:colspan 7 :class "p-4 text-center text-gray-400"}
                          "No runs yet"]]
                    (for [{:keys [id experiment status start-time end-time]
                           run-name :name} runs]
                      [:tr {:class "border-b hover:bg-gray-50"}
                       [:td {:class "p-2"}
                        [:input {:type "checkbox" :name "ids" :value id
                                 :class "accent-indigo-600"}]]
                       [:td {:class "p-2"}
                        [:a {:href (str "/runs/" id)
                             :class "text-indigo-600 hover:underline font-mono text-xs"}
                         (fmt/short-id id)]]
                       [:td {:class "p-2"} experiment]
                       [:td {:class "p-2"} (or run-name "—")]
                       [:td {:class "p-2"} (status-badge status)]
                       [:td {:class "p-2 text-xs text-gray-500"} (fmt/fmt-instant start-time)]
                       [:td {:class "p-2 text-xs text-gray-500"}
                        (or (fmt/fmt-duration start-time end-time) "—")]]))]]]))

;; --- Run detail ------------------------------------------------------

(defn- render-markdown
  "Hiccup fragment that renders markdown text with KaTeX math support."
  [dom-id text]
  (when (and text (seq text))
    [:div
     [:div {:id dom-id :class "prose prose-sm max-w-none bg-white border rounded p-4 mt-2"} text]
     [:script (str "document.getElementById('" dom-id "').innerHTML = "
                   "marked.parse(document.getElementById('" dom-id "').textContent);"
                   "renderMathInElement(document.getElementById('" dom-id "'), "
                   "{delimiters: [{left: '$$', right: '$$', display: true},"
                   "{left: '$', right: '$', display: false}]});")]]))

(defn run-page
  "Full detail view for a single run."
  [{:keys [id experiment status start-time end-time error tags env
           params metrics artifacts datasets]
    run-name :name}]
  (let [scalars    (filter #(= 1 (count (second %)))
                           (group-by :key metrics))
        timeseries (filter #(> (count (second %)) 1)
                           (group-by :key metrics))
        note       (:note tags)
        table-arts (filter #(= "application/x-chachaml-table" (:content-type %)) artifacts)
        other-arts (remove #(= "application/x-chachaml-table" (:content-type %)) artifacts)]
    (layout/page (str "Run " (fmt/short-id id))
      ;; Header
                 [:div {:class "mb-6"}
                  [:h1 {:class "text-2xl font-bold mb-2"}
                   (str "Run " (fmt/short-id id))
                   " " (status-badge status)]
                  [:dl {:class "grid grid-cols-2 md:grid-cols-4 gap-2 text-sm mt-2"}
                   [:div [:dt {:class "text-gray-500"} "Experiment"]
                    [:dd {:class "font-medium"} experiment]]
                   (when run-name
                     [:div [:dt {:class "text-gray-500"} "Name"]
                      [:dd {:class "font-medium"} run-name]])
                   [:div [:dt {:class "text-gray-500"} "Started"]
                    [:dd (fmt/fmt-instant start-time)]]
                   (when end-time
                     [:div [:dt {:class "text-gray-500"} "Duration"]
                      [:dd (fmt/fmt-duration start-time end-time)]])
                   (when error
                     [:div {:class "col-span-full"}
                      [:dt {:class "text-red-600"} "Error"]
                      [:dd {:class "text-red-700 font-mono text-xs"} error]])
                   (when (seq tags)
                     [:div {:class "col-span-full"}
                      [:dt {:class "text-gray-500"} "Tags"]
                      [:dd (for [[k v] tags]
                             [:span {:class "inline-block bg-gray-100 rounded px-2 py-0.5 text-xs mr-1 mb-1"}
                              (str (name k) "=" v)])]])
                   (when-let [git (:git env)]
                     [:div {:class "col-span-full"}
                      [:dt {:class "text-gray-500"} "Git"]
                      [:dd {:class "font-mono text-xs"}
                       (str (or (:sha git) "—") " on " (or (:branch git) "?")
                            (when (:dirty? git) " (dirty)"))]])]]

      ;; Params
                 (when (seq params)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Params"]
                    [:table {:class "w-full text-sm border-collapse"}
                     [:thead [:tr {:class "border-b bg-gray-100"}
                              [:th {:class "p-2 text-left"} "Key"]
                              [:th {:class "p-2 text-left"} "Value"]]]
                     [:tbody
                      (for [[k v] (sort-by (comp str key) params)]
                        [:tr {:class "border-b"}
                         [:td {:class "p-2 font-mono text-xs"} (str k)]
                         [:td {:class "p-2 font-mono text-xs"} (pr-str v)]])]]])

      ;; Scalar metrics
                 (when (seq scalars)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Scalar Metrics"]
                    [:div {:class "grid grid-cols-2 md:grid-cols-4 gap-3"}
                     (for [[k [row]] (sort-by (comp str key) scalars)]
                       [:div {:class "bg-white border rounded p-3"}
                        [:div {:class "text-xs text-gray-500"} (name k)]
                        [:div {:class "text-lg font-bold"} (format "%.6g" (double (:value row)))]])]])

      ;; Time-series charts
                 (when (seq timeseries)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Metric Curves"]
                    [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
                     (for [[k rows] (sort-by (comp str key) timeseries)]
                       (charts/chart-div
                        (str "chart-" (name k))
                        (charts/metric-line-chart k (sort-by :step rows))))]])

      ;; Note (markdown + math)
                 (when note
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Note"]
                    (render-markdown "run-note" note)])

      ;; Datasets
                 (when (seq datasets)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Datasets"]
                    [:table {:class "w-full text-sm border-collapse"}
                     [:thead [:tr {:class "border-b bg-gray-100"}
                              [:th {:class "p-2 text-left"} "Role"]
                              [:th {:class "p-2 text-left"} "Rows"]
                              [:th {:class "p-2 text-left"} "Cols"]
                              [:th {:class "p-2 text-left"} "Features"]
                              [:th {:class "p-2 text-left"} "Hash"]
                              [:th {:class "p-2 text-left"} "Source"]]]
                     [:tbody
                      (for [{:keys [role n-rows n-cols features source]
                             ds-hash :hash} datasets]
                        [:tr {:class "border-b"}
                         [:td {:class "p-2"} (or role "—")]
                         [:td {:class "p-2"} (or n-rows "—")]
                         [:td {:class "p-2"} (or n-cols "—")]
                         [:td {:class "p-2 font-mono text-xs"}
                          (when features (pr-str features))]
                         [:td {:class "p-2 font-mono text-xs text-gray-400"}
                          (when ds-hash (fmt/short-id ds-hash))]
                         [:td {:class "p-2 text-xs text-gray-500"}
                          (or source "—")]])]]])

      ;; Table artifacts (rendered inline)
                 (when (seq table-arts)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Tables"]
                    (for [{art-name :name art-id :id} table-arts]
                      [:div {:class "mb-4"}
                       [:h3 {:class "text-sm font-medium text-gray-600"} art-name]
                       ;; Table content loaded via HTMX
                       [:div {:hx-get    (str "/api/artifacts/" art-id "/download")
                              :hx-trigger "load"
                              :hx-swap   "innerHTML"
                              :class     "text-sm"}
                        "Loading..."]])])

      ;; Other artifacts
                 (when (seq other-arts)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Artifacts"]
                    [:table {:class "w-full text-sm border-collapse"}
                     [:thead [:tr {:class "border-b bg-gray-100"}
                              [:th {:class "p-2 text-left"} "Name"]
                              [:th {:class "p-2 text-left"} "Size"]
                              [:th {:class "p-2 text-left"} "Type"]
                              [:th {:class "p-2 text-left"} "SHA-256"]
                              [:th {:class "p-2 text-left"} ""]]]
                     [:tbody
                      (for [{art-name :name :keys [id size content-type]
                             digest :hash} other-arts]
                        [:tr {:class "border-b"}
                         [:td {:class "p-2 font-mono text-xs"} art-name]
                         [:td {:class "p-2"} (if size (fmt/size-str size) "—")]
                         [:td {:class "p-2 text-xs text-gray-500"} (or content-type "—")]
                         [:td {:class "p-2 font-mono text-xs text-gray-400"}
                          (when digest (subs digest 0 (min 12 (count digest))))]
                         [:td {:class "p-2"}
                          (when id
                            (cond
                              (and content-type (re-find #"^image/" content-type))
                              [:img {:src (str "/api/artifacts/" id "/download")
                                     :class "max-h-20 rounded"
                                     :alt art-name}]
                              :else
                              [:a {:href (str "/api/artifacts/" id "/download")
                                   :class "text-indigo-600 hover:underline text-xs"}
                               "download"]))]])]]]))))

;; --- Run comparison --------------------------------------------------

(defn compare-page
  "Side-by-side comparison of selected runs."
  [run-ids comparison full-runs]
  (let [{:keys [params]} comparison
        time-series-keys (->> full-runs
                              (mapcat :metrics)
                              (group-by :key)
                              (filter (fn [[_ vs]] (> (count vs) 1)))
                              keys
                              set)]
    (layout/page "Compare Runs"
                 [:h1 {:class "text-2xl font-bold mb-4"} "Run Comparison"]
                 [:div {:class "flex gap-2 mb-4 text-sm"}
                  (for [id run-ids]
                    [:a {:href (str "/runs/" id)
                         :class "bg-indigo-100 text-indigo-700 px-2 py-1 rounded font-mono text-xs"}
                     (fmt/short-id id)])]

      ;; Params diff
                 [:div {:class "mb-6"}
                  [:h2 {:class "text-lg font-semibold mb-2"} "Params"]
                  [:table {:class "w-full text-sm border-collapse"}
                   [:thead
                    [:tr {:class "border-b bg-gray-100"}
                     [:th {:class "p-2 text-left"} "Key"]
                     (for [id run-ids]
                       [:th {:class "p-2 text-left font-mono text-xs"} (fmt/short-id id)])
                     [:th {:class "p-2 text-left"} ""]]]
                   [:tbody
                    (for [k (sort (set (concat (keys (:same params))
                                               (keys (:differ params))
                                               (keys (:partial params)))))]
                      (let [vs (cond
                                 (contains? (:same params) k)    (repeat (count run-ids) (get-in params [:same k]))
                                 (contains? (:differ params) k)  (get-in params [:differ k])
                                 :else                            (get-in params [:partial k]))
                            diff? (or (contains? (:differ params) k)
                                      (contains? (:partial params) k))]
                        [:tr {:class (str "border-b" (when diff? " bg-yellow-50"))}
                         [:td {:class "p-2 font-mono text-xs"} (str k)]
                         (for [v vs]
                           [:td {:class "p-2 font-mono text-xs"}
                            (if (some? v) (pr-str v) [:span {:class "text-gray-300"} "—"])])
                         [:td {:class "p-2 text-xs"}
                          (cond
                            (contains? (:same params) k)    [:span {:class "text-green-600"} "same"]
                            (contains? (:differ params) k)  [:span {:class "text-amber-600"} "differ"]
                            :else                           [:span {:class "text-gray-400"} "partial"])]]))]]]

      ;; Overlaid metric charts
                 (when (seq time-series-keys)
                   [:div {:class "mb-6"}
                    [:h2 {:class "text-lg font-semibold mb-2"} "Metric Curves"]
                    [:div {:class "grid grid-cols-1 md:grid-cols-2 gap-4"}
                     (for [k (sort time-series-keys)]
                       (let [series (for [r full-runs]
                                      {:run-id (:id r)
                                       :rows   (->> (:metrics r)
                                                    (filter #(= k (:key %)))
                                                    (sort-by :step))})]
                         (charts/chart-div
                          (str "cmp-" (name k))
                          (charts/multi-run-line-chart k series))))]]))))

;; --- Model registry --------------------------------------------------

(defn models-page
  "List of registered models."
  [models version-counts]
  (layout/page "Models"
               [:h1 {:class "text-2xl font-bold mb-4"} "Model Registry"]
               (if (empty? models)
                 [:p {:class "text-gray-400"} "No models registered yet."]
                 [:table {:class "w-full text-sm border-collapse"}
                  [:thead [:tr {:class "border-b bg-gray-100"}
                           [:th {:class "p-2 text-left"} "Name"]
                           [:th {:class "p-2 text-left"} "Versions"]
                           [:th {:class "p-2 text-left"} "Description"]
                           [:th {:class "p-2 text-left"} "Created"]]]
                  [:tbody
                   (for [{model-name :name :keys [description created-at]} models]
                     [:tr {:class "border-b hover:bg-gray-50"}
                      [:td {:class "p-2"}
                       [:a {:href (str "/models/" model-name)
                            :class "text-indigo-600 hover:underline font-medium"}
                        model-name]]
                      [:td {:class "p-2"} (get version-counts model-name 0)]
                      [:td {:class "p-2 text-gray-500 text-xs"} (or description "—")]
                      [:td {:class "p-2 text-xs text-gray-500"} (fmt/fmt-instant created-at)]])]])))

;; --- Model detail ----------------------------------------------------

(defn model-page
  "Detail view for one model's version history."
  [{model-name :name :keys [description created-at]} versions]
  (layout/page (str "Model: " model-name)
               [:div {:class "mb-6"}
                [:h1 {:class "text-2xl font-bold mb-1"} model-name]
                (when description
                  (render-markdown (str "model-note-" model-name) description))
                [:p {:class "text-xs text-gray-400"} (str "Created " (fmt/fmt-instant created-at))]]
               [:h2 {:class "text-lg font-semibold mb-2"} "Versions"]
               [:table {:class "w-full text-sm border-collapse"}
                [:thead [:tr {:class "border-b bg-gray-100"}
                         [:th {:class "p-2 text-left"} "Version"]
                         [:th {:class "p-2 text-left"} "Stage"]
                         [:th {:class "p-2 text-left"} "Run"]
                         [:th {:class "p-2 text-left"} "Description"]
                         [:th {:class "p-2 text-left"} "Created"]]]
                [:tbody
                 (for [{:keys [version stage run-id description created-at]} versions]
                   [:tr {:class "border-b"}
                    [:td {:class "p-2 font-medium"} (str "v" version)]
                    [:td {:class "p-2"} (stage-badge stage)]
                    [:td {:class "p-2"}
                     [:a {:href (str "/runs/" run-id)
                          :class "text-indigo-600 hover:underline font-mono text-xs"}
                      (fmt/short-id run-id)]]
                    [:td {:class "p-2 text-xs text-gray-500"} (or description "—")]
                    [:td {:class "p-2 text-xs text-gray-500"} (fmt/fmt-instant created-at)]])]]))

;; --- Experiments page ------------------------------------------------

(defn experiments-page
  "List of experiments with metadata."
  [experiments run-counts]
  (layout/page "Experiments"
               [:h1 {:class "text-2xl font-bold mb-4"} "Experiments"]
               (if (empty? experiments)
                 [:p {:class "text-gray-400"} "No experiments yet."]
                 [:table {:class "w-full text-sm border-collapse"}
                  [:thead [:tr {:class "border-b bg-gray-100"}
                           [:th {:class "p-2 text-left"} "Name"]
                           [:th {:class "p-2 text-left"} "Description"]
                           [:th {:class "p-2 text-left"} "Owner"]
                           [:th {:class "p-2 text-left"} "Runs"]
                           [:th {:class "p-2 text-left"} "Created"]]]
                  [:tbody
                   (for [{:keys [description owner created-at]
                          exp-name :name} experiments]
                     [:tr {:class "border-b hover:bg-gray-50"}
                      [:td {:class "p-2"}
                       [:a {:href (str "/runs?experiment=" exp-name)
                            :class "text-indigo-600 hover:underline font-medium"}
                        exp-name]]
                      [:td {:class "p-2 text-gray-500 text-xs"}
                       (or description "—")]
                      [:td {:class "p-2 text-xs"} (or owner "—")]
                      [:td {:class "p-2"} (get run-counts exp-name 0)]
                      [:td {:class "p-2 text-xs text-gray-500"}
                       (when created-at (fmt/fmt-instant created-at))]])]])))

;; --- Search page -----------------------------------------------------

(defn search-page
  "Search runs by metric values."
  [results experiment-list params-map]
  (layout/page "Search"
               [:h1 {:class "text-2xl font-bold mb-4"} "Search Runs by Metric"]
               [:form {:class "flex flex-wrap gap-2 mb-6" :method "get" :action "/search"}
                [:select {:name "experiment" :class "border rounded px-2 py-1 text-sm"}
                 [:option {:value ""} "All experiments"]
                 (for [e experiment-list]
                   [:option (cond-> {:value e}
                              (= e (:experiment params-map)) (assoc :selected true))
                    e])]
                [:input {:name "metric_key" :type "text" :placeholder "metric key (e.g. accuracy)"
                         :value (or (:metric-key params-map) "")
                         :class "border rounded px-2 py-1 text-sm w-40"}]
                [:select {:name "op" :class "border rounded px-2 py-1 text-sm"}
                 (for [o ["> >= < <= ="]]
                   [:option (cond-> {:value o}
                              (= o (str (:op params-map))) (assoc :selected true))
                    o])]
                [:input {:name "metric_value" :type "number" :step "any"
                         :placeholder "value"
                         :value (or (:metric-value params-map) "")
                         :class "border rounded px-2 py-1 text-sm w-24"}]
                [:button {:type "submit"
                          :class "bg-indigo-600 text-white px-3 py-1 rounded text-sm"}
                 "Search"]]
               (if results
                 (if (empty? results)
                   [:p {:class "text-gray-400"} "No matching runs."]
                   [:table {:class "w-full text-sm border-collapse"}
                    [:thead [:tr {:class "border-b bg-gray-100"}
                             [:th {:class "p-2 text-left"} "ID"]
                             [:th {:class "p-2 text-left"} "Experiment"]
                             [:th {:class "p-2 text-left"} "Status"]
                             [:th {:class "p-2 text-left"} "Started"]]]
                    [:tbody
                     (for [{:keys [id experiment status start-time]} results]
                       [:tr {:class "border-b hover:bg-gray-50"}
                        [:td {:class "p-2"}
                         [:a {:href (str "/runs/" id)
                              :class "text-indigo-600 hover:underline font-mono text-xs"}
                          (fmt/short-id id)]]
                        [:td {:class "p-2"} experiment]
                        [:td {:class "p-2"} (status-badge status)]
                        [:td {:class "p-2 text-xs text-gray-500"}
                         (fmt/fmt-instant start-time)]])]])
                 [:p {:class "text-gray-400"} "Enter search criteria above."])))
