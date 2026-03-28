(ns memlayer.json
  "Shared JSON serialization helpers for hyphen ↔ underscore key conversion."
  (:require [jsonista.core :as j]
            [clojure.string :as str]))

(defn encode-key
  "Convert a keyword to a JSON-safe string, replacing hyphens with underscores."
  [k]
  (-> (name k) (str/replace "-" "_")))

(defn decode-key
  "Convert a JSON string key to an idiomatic Clojure keyword with hyphens."
  [s]
  (-> s (str/replace "_" "-") keyword))

(def mapper
  "Jsonista object-mapper that converts hyphens ↔ underscores in keys."
  (j/object-mapper {:encode-key-fn encode-key
                    :decode-key-fn decode-key}))
