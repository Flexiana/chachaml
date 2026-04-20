(defproject chachaml "0.5.0"
  :description "Practical, REPL-first MLOps library for Clojure."
  :url "https://github.com/jiriknesl/chachaml"
  :license {:name "MIT"
            :url  "https://opensource.org/licenses/MIT"}

  :dependencies [[org.clojure/clojure "1.12.0"]
                 [com.github.seancorfield/next.jdbc "1.3.939"]
                 [org.xerial/sqlite-jdbc "3.46.1.0"]
                 [com.taoensso/nippy "3.4.2"]
                 [metosin/malli "0.16.4"]]

  :source-paths   ["src"]
  :resource-paths ["resources"]
  :test-paths     ["test"]
  :target-path    "target/%s"

  :profiles
  {:test {:dependencies [[org.clojure/test.check "1.1.1"]
                         [org.clojure/data.json "2.5.1"]
                         [ring/ring-core "1.13.0"]
                         [ring/ring-jetty-adapter "1.13.0"]
                         [metosin/reitit-ring "0.7.2"]
                         [hiccup "2.0.0-RC4"]
                         [ring/ring-mock "0.4.0"]]
          :source-paths ["examples"]}
   :dev  {:source-paths ["dev" "examples"]
          :dependencies [[org.clojure/test.check "1.1.1"]
                         [org.clojure/data.json "2.5.1"]
                         [ring/ring-core "1.13.0"]
                         [ring/ring-jetty-adapter "1.13.0"]
                         [metosin/reitit-ring "0.7.2"]
                         [hiccup "2.0.0-RC4"]
                         [ring/ring-mock "0.4.0"]]}})
