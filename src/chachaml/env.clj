(ns chachaml.env
  "Reproducibility metadata capture.

  Snapshots the runtime environment for inclusion in a run record:
  git revision/branch/dirty state, JVM info, OS info, Clojure version,
  current user. All capture is best-effort: missing tools (e.g. no git
  on PATH) silently produce nil rather than throwing."
  (:require [clojure.java.shell :as shell]
            [clojure.string :as str])
  (:import [java.net InetAddress UnknownHostException]))

(defn- safe-sh
  "Run a shell command, returning trimmed stdout on success or nil on any
  failure (non-zero exit, missing binary, exception)."
  [& args]
  (try
    (let [{:keys [exit out]} (apply shell/sh args)]
      (when (and (zero? exit) (string? out))
        (let [t (str/trim out)]
          (when-not (str/blank? t) t))))
    (catch Exception _ nil)))

(defn git-sha
  "Return the current git HEAD SHA, or nil if unavailable."
  []
  (safe-sh "git" "rev-parse" "HEAD"))

(defn git-branch
  "Return the current git branch name, or nil if unavailable."
  []
  (safe-sh "git" "rev-parse" "--abbrev-ref" "HEAD"))

(defn git-dirty?
  "Return true if the working tree has uncommitted changes, false if it
  is clean, or nil if git is unavailable."
  []
  (when (git-sha)
    (let [status (safe-sh "git" "status" "--porcelain")]
      (boolean (and status (not (str/blank? status)))))))

(defn jvm-info
  "Return a map describing the running JVM."
  []
  {:version (System/getProperty "java.version")
   :vendor  (System/getProperty "java.vendor")})

(defn os-info
  "Return a map describing the host operating system."
  []
  {:name    (System/getProperty "os.name")
   :arch    (System/getProperty "os.arch")
   :version (System/getProperty "os.version")})

(defn- hostname []
  (try
    (.getHostName (InetAddress/getLocalHost))
    (catch UnknownHostException _ nil)))

(defn user-info
  "Return a map describing the current user and host."
  []
  {:name (System/getProperty "user.name")
   :host (hostname)})

(defn capture
  "Snapshot the runtime environment as a plain map. All git fields are
  nil when git is unavailable or the cwd is not a repo."
  []
  {:git     {:sha    (git-sha)
             :branch (git-branch)
             :dirty? (git-dirty?)}
   :jvm     (jvm-info)
   :os      (os-info)
   :user    (user-info)
   :clojure (clojure-version)})
