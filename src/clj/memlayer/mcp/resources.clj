(ns memlayer.mcp.resources
  "MCP resource definitions for memlayer."
  (:require [clojure.java.io :as io]))

(def ^:private skill-md
  "Lazily loaded SKILL.md content from classpath."
  (delay
    (or (some-> (io/resource "public/SKILL.md") slurp)
        "# Memlayer Memory\n\nSKILL.md not found. Visit https://memlayer.dev/SKILL.md for usage instructions.")))

(def resource-definitions
  [{:uri         "memlayer://skill"
    :name        "Memlayer Usage Guide"
    :description "Instructions for how to use memlayer memory tools effectively — when to retain, recall, reflect, and forget."
    :mimeType    "text/markdown"}])

(defn instructions-text
  "Returns the SKILL.md instructions for the MCP initialize response."
  []
  @skill-md)

(defn read-resource
  "Read a resource by URI. Returns {:contents [{:uri ... :mimeType ... :text ...}]}
   or throws if not found."
  [uri]
  (case uri
    "memlayer://skill"
    {:contents [{:uri      "memlayer://skill"
                 :mimeType "text/markdown"
                 :text     @skill-md}]}

    (throw (ex-info "Resource not found" {:uri uri}))))
