(ns chachaml.env-test
  "Tests for `chachaml.env` capture functions.

  Most assertions are shape-only — we cannot assume git is installed,
  the cwd is a repo, or any specific JVM/OS values. We do assert that
  the documented contracts hold (no exceptions, nil rather than throw)."
  (:require [chachaml.env :as env]
            [clojure.test :refer [deftest is testing]]))

(deftest jvm-info-shape
  (let [m (env/jvm-info)]
    (is (string? (:version m)))
    (is (string? (:vendor m)))))

(deftest os-info-shape
  (let [m (env/os-info)]
    (is (every? string? (vals m)))
    (is (= #{:name :arch :version} (set (keys m))))))

(deftest user-info-shape
  (let [m (env/user-info)]
    (is (string? (:name m)))
    (testing ":host is a string when DNS resolves, otherwise nil"
      (is (or (nil? (:host m)) (string? (:host m)))))))

(deftest capture-returns-map-with-known-keys
  (let [m (env/capture)]
    (is (= #{:git :jvm :os :user :clojure} (set (keys m))))
    (is (string? (:clojure m)))
    (is (map? (:git m)))
    (is (= #{:sha :branch :dirty?} (set (keys (:git m)))))))

(deftest git-helpers-do-not-throw
  (testing "git-sha/git-branch/git-dirty? all return without throwing,
            regardless of whether git is on PATH or cwd is a repo"
    (is (or (nil? (env/git-sha)) (string? (env/git-sha))))
    (is (or (nil? (env/git-branch)) (string? (env/git-branch))))
    (is (contains? #{nil true false} (env/git-dirty?)))))
