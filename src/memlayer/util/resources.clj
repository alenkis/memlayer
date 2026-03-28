(ns memlayer.util.resources
  "Utilities for loading classpath resources."
  (:require [clojure.java.io :as io])
  (:import [java.io PushbackReader]))

(defn read-edn!
  "Read and parse an EDN file from the classpath. Returns nil if not found."
  [resource-path]
  (when-let [url (io/resource resource-path)]
    (with-open [rdr (PushbackReader. (io/reader url))]
      (read rdr))))
