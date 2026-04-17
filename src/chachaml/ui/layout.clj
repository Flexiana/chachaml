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
      ;; Tailwind via CDN (no build step)
      [:script {:src "https://cdn.tailwindcss.com"}]
      ;; HTMX
      [:script {:src "https://unpkg.com/htmx.org@2.0.4"
                :integrity "sha384-HGfztofotfshcF7+8n44JQL2oJmowVChPTg48S+jvZoztPfvwD79OC/LTtG6dMp+"
                :crossorigin "anonymous"}]
      ;; Vega-Lite for metric charts
      [:script {:src "https://cdn.jsdelivr.net/npm/vega@5"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/vega-lite@5"}]
      [:script {:src "https://cdn.jsdelivr.net/npm/vega-embed@6"}]]
     [:body {:class "bg-gray-50 text-gray-900 min-h-screen"}
      [:nav {:class "bg-indigo-700 text-white px-6 py-3 flex items-center gap-6 shadow"}
       [:a {:href "/" :class "font-bold text-lg"} "chachaml"]
       [:a {:href "/runs" :class "hover:underline"} "Runs"]
       [:a {:href "/models" :class "hover:underline"} "Models"]]
      [:main {:class "max-w-7xl mx-auto px-4 py-6"}
       body]]])))
