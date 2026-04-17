(ns chachaml.ui.layout
  "Base HTML layout for the chachaml web UI.

  Provides the outer shell (head, nav, HTMX/Vega-Lite/Tailwind CDN
  includes) that views plug their content into."
  (:require [hiccup2.core :as h]))

(defn page
  "Wrap `body` hiccup forms in a full HTML document with the standard
  head + navigation. `title` appears in the browser tab."
  [title & body]
  (str
   (h/html
    {:mode :html}
    (h/raw "<!DOCTYPE html>")
    [:html {:lang "en"}
     [:head
      [:meta {:charset "utf-8"}]
      [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
      [:title (str title " — chachaml")]
      ;; All JS/CSS deps served locally from resources/public/
      [:link {:rel "stylesheet" :href "/css/katex.min.css"}]
      [:script {:src "/js/tailwind.js"}]
      [:script {:src "/js/htmx.min.js"}]
      [:script {:src "/js/vega.min.js"}]
      [:script {:src "/js/vega-lite.min.js"}]
      [:script {:src "/js/vega-embed.min.js"}]
      [:script {:src "/js/marked.min.js"}]
      [:script {:src "/js/katex.min.js"}]
      [:script {:src "/js/auto-render.min.js"}]]
     [:body {:class "bg-gray-50 text-gray-900 min-h-screen"}
      [:nav {:class "bg-indigo-700 text-white px-6 py-3 flex items-center gap-6 shadow"}
       [:a {:href "/" :class "font-bold text-lg"} "chachaml"]
       [:a {:href "/runs" :class "hover:underline"} "Runs"]
       [:a {:href "/models" :class "hover:underline"} "Models"]
       [:a {:href "/experiments" :class "hover:underline"} "Experiments"]
       [:a {:href "/search" :class "hover:underline"} "Search"]]
      [:main {:class "max-w-7xl mx-auto px-4 py-6"}
       body]]])))
