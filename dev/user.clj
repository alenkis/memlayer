(ns user
  "Dev REPL utilities. Loaded automatically via `clj -M:dev`.

   Usage — jack in with :dev alias, then:

     (go)                                     ;; in-memory (default)
     (go {:store \".data/dev-db\"})           ;; file-backed (bare path)
     (halt)                                   ;; stop
     (reset)                                  ;; stop + start (same config)

   Convenience fns work against the running system:

     (retain \"User prefers dark mode\")
     (recall \"preferences\")
     (memories)
     (db)                  ;; raw datahike conn
     (vidx)                ;; vector-index atom"
  (:require [integrant.core :as ig]
            [integrant.repl :refer [halt reset set-prep!]]
            [integrant.repl.state :refer [system]]
            [memlayer.system :as sys]
            [memlayer.config :as config]
            [memlayer.persistence.datahike :as dh]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.operations.recall :as recall]
            [aero.core :as aero])
  (:import [io.github.cdimascio.dotenv Dotenv]))

;; Load .env for REPL sessions (bb tasks source .env for CLI commands)
(defonce ^:private dotenv
  (-> (Dotenv/configure)
      (.ignoreIfMissing)
      (.load)))

;; Override Aero's #env reader to also check .env file
(defmethod aero/reader 'env [_ _ value]
  (let [k (str value)]
    (or (System/getenv k) (.get dotenv k))))

;; ---------------------------------------------------------------------------
;; Config override — lets us swap datahike backend without touching env vars
;; ---------------------------------------------------------------------------

(defonce ^:private dev-overrides (atom {}))

(defmethod ig/init-key :memlayer/config [_ _]
  (merge-with merge (config/load-config) @dev-overrides))

;; Default prep: all in-memory unless MEMLAYER_DATA is set
(reset! dev-overrides (if (System/getenv "MEMLAYER_DATA")
                        {}
                        {:datahike  {:backend :memory}
                         :proximum {:backend :memory}}))
(set-prep! (constantly sys/system-config))

;; ---------------------------------------------------------------------------
;; System lifecycle
;; ---------------------------------------------------------------------------

(defn go
  "Start the system.

   (go)                                     ;; in-memory (ephemeral)
   (go {:store \"/tmp/my-data\"})           ;; file-backed (persistent)"
  ([] (go {}))
  ([{:keys [store]}]
   (reset! dev-overrides (if store
                           {:datahike  {:backend :file :path (str store "/db")}
                            :proximum {:backend :file :path (str store "/vectors")}}
                           {:datahike  {:backend :memory}
                            :proximum {:backend :memory}}))
   (integrant.repl/go)))

;; ---------------------------------------------------------------------------
;; Accessors
;; ---------------------------------------------------------------------------

(defn db
  "Datahike conn from the running system."
  []
  (:persistence/datahike system))

(defn vidx
  "Vector-index atom from the running system."
  []
  (:persistence/proximum system))

(defn sys-deps
  "Build a deps map from the running system for retain!/recall!."
  []
  (let [config (:memlayer/config system)]
    {:db                 (db)
     :vector-index       (vidx)
     :embedding-provider (:provider/openai system)
     :chat-provider      (:provider/groq system)
     :prompts            (:prompts config)
     :tuning             (:tuning config)}))

;; ---------------------------------------------------------------------------
;; Convenience
;; ---------------------------------------------------------------------------

(defn retain
  "Run retain pipeline against the running system.

   (retain \"User prefers dark mode\")
   (retain \"User prefers dark mode\" {:source \"chat\" :namespace \"prefs\"})"
  ([content] (retain content {}))
  ([content {:keys [source namespace] :or {source "repl" namespace "default"}}]
   (retention-flow/submit! (:memlayer/retention-flow system)
                           {:items     [{:content content :source source}]
                            :namespace namespace
                            :source    source})))

(defn recall
  "Run recall against the running system.

   (recall \"dark mode\")"
  ([query] (recall query {}))
  ([query {:keys [namespace limit] :or {limit 10}}]
   (recall/recall! (sys-deps) (cond-> {:query query :limit limit}
                                namespace (assoc :namespace namespace)))))

(defn memories
  "List all memories in the running system."
  ([] (memories {}))
  ([{:keys [namespace layer limit] :or {limit 100}}]
   (dh/get-all-memories (db) :namespace namespace :layer layer
                        :limit limit :offset 0)))

(comment
  ;; -- Quick start --
  ;; Jack in with :dev alias, then eval these:

  ;; In-memory (ephemeral, gone on halt)
  (go)

  ;; File-backed (persists across restarts)
  ;; (go {:store ".data/dev-db"})

  ;; Store a memory (calls real OpenAI + Groq)
  (retain "User prefers dark mode and Vim keybindings")

  ;; Recall
  (recall "editor preferences")

  ;; Browse
  (memories)
  (memories {:namespace "default" :layer :layer/fact})

  ;; Raw datahike queries
  (dh/get-recent-memories (db) :limit 5)
  (dh/count-all-memories (db))
  (dh/get-distinct-namespaces (db))

  ;; Restart (preserves config, re-initializes components)
  (reset)

  ;; Stop
  (halt))
