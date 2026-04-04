(ns memlayer.mcp.resources
  "MCP resource definitions for memlayer."
  (:require [clojure.java.io :as io]
            [clojure.tools.logging :as log]))

(def ^:private skill-md
  "Lazily loaded SKILL.md content from classpath."
  (delay
    (or (some-> (io/resource "public/SKILL.md") slurp)
        "# Memlayer Memory\n\nSKILL.md not found. Visit https://memlayer.dev/SKILL.md for usage instructions.")))

(defn- load-user-instructions
  "Read user instructions file at path. Returns nil if missing or unreadable."
  [path]
  (when path
    (let [f (io/file path)]
      (when (.isFile f)
        (try
          (slurp f :encoding "UTF-8")
          (catch Exception e
            (log/warn "Could not read user instructions file" path (.getMessage e))
            nil))))))

(def resource-definitions
  [{:uri         "memlayer://skill"
    :name        "Memlayer Usage Guide"
    :description "Instructions for how to use memlayer memory tools effectively — when to retain, recall, reflect, and forget."
    :mimeType    "text/markdown"}])

(defn instructions-text
  "Returns instructions for the MCP initialize response.
   Zero-arity returns base SKILL.md. One-arity returns the user's custom
   instructions file if it exists, otherwise falls back to base SKILL.md."
  ([] @skill-md)
  ([instructions-file]
   (or (load-user-instructions instructions-file) @skill-md)))

(defn read-resource
  "Read a resource by URI. Returns {:contents [{:uri ... :mimeType ... :text ...}]}
   or throws if not found. Optional instructions-file path for user customizations."
  ([uri] (read-resource uri nil))
  ([uri instructions-file]
   (case uri
     "memlayer://skill"
     {:contents [{:uri      "memlayer://skill"
                  :mimeType "text/markdown"
                  :text     (instructions-text instructions-file)}]}

     (throw (ex-info "Resource not found" {:uri uri})))))
