(ns build
  "tools.build entry points for chachaml."
  (:require [clojure.tools.build.api :as b]))

(def lib       'org.clojars.jiriknesl/chachaml)
(def version   "0.4.0")
(def class-dir "target/classes")
(def jar-file  (format "target/%s-%s.jar" (name lib) version))

(defn- basis [] (b/create-basis {:project "deps.edn"}))

(defn clean
  "Remove the target directory."
  [_]
  (b/delete {:path "target"}))

(defn jar
  "Build a library jar from the current sources."
  [_]
  (clean nil)
  (b/write-pom {:class-dir class-dir
                :lib       lib
                :version   version
                :basis     (basis)
                :src-dirs  ["src"]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))
