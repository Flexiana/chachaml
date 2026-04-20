(ns build
  "tools.build entry points for chachaml."
  (:require [clojure.tools.build.api :as b]))

(def lib       'com.flexiana/chachaml)
(def version   "0.6.0")
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
                :src-dirs  ["src"]
                :scm       {:url "https://github.com/flexiana/chachaml"}})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir class-dir})
  (b/jar {:class-dir class-dir
          :jar-file  jar-file})
  (println "Built" jar-file))

(defn deploy
  "Deploy the jar to Clojars. Requires CLOJARS_USERNAME and
  CLOJARS_PASSWORD environment variables."
  [_]
  (jar nil)
  ((requiring-resolve 'deps-deploy.deps-deploy/deploy)
   {:installer :remote
    :artifact  (b/resolve-path jar-file)
    :pom-file  (b/pom-path {:class-dir class-dir :lib lib})}))
