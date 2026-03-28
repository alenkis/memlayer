(ns memlayer.version
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.shell :as shell]))

(defn- git-describe []
  (let [{:keys [exit out]} (shell/sh "git" "describe" "--tags" "--always" "--dirty" "--match" "v*")]
    (when (zero? exit)
      (.trim out))))

(defn- git-sha []
  (let [{:keys [exit out]} (shell/sh "git" "rev-parse" "--short" "HEAD")]
    (when (zero? exit)
      (.trim out))))

(def build-info
  (delay
    (if-let [r (io/resource "version.edn")]
      (edn/read-string (slurp r))
      ;; REPL fallback: read from git directly
      {:version  (or (git-describe) "dev")
       :git-sha  (or (git-sha) "unknown")
       :built-at "dev"})))

(defn version [] (:version @build-info))
(defn git-sha* [] (:git-sha @build-info))
(defn built-at [] (:built-at @build-info))
