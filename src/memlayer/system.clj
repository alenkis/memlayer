(ns memlayer.system
  "Integrant system definition with all component init/halt methods."
  (:gen-class)
  (:require [integrant.core :as ig]
            [memlayer.config :as config]
            [memlayer.persistence.datahike :as datahike]
            [memlayer.persistence.proximum :as proximum]
            [memlayer.persistence.usage :as usage]
            [memlayer.persistence.migrations :as migrations]
            [memlayer.provider.openai :as openai]
            [memlayer.provider.groq :as groq]
            [memlayer.provider.llm]
            [memlayer.router :as router]
            [memlayer.server :as server]
            [memlayer.version :as version]
            [memlayer.operations.flow.retention-flow]
            [memlayer.api.retain]
            [memlayer.api.recall]
            [memlayer.api.forget]
            [memlayer.api.evict]
            [memlayer.api.ingest]
            [memlayer.api.batch-retain]
            [memlayer.api.reflect]
            [memlayer.api.ws-ingest]
            [memlayer.api.admin]
            [memlayer.api.namespaces]
            [memlayer.api.memories]
            [memlayer.api.stats]
            [memlayer.api.dashboard]
            [memlayer.mcp.http]
            [datahike.api :as d]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(defmethod ig/init-key :memlayer/config [_ {:keys [path]}]
  (log/info "Loading configuration")
  (if path
    (config/load-config path)
    (config/load-config)))

(defmethod ig/init-key :persistence/datahike [_ {:keys [config]}]
  (let [{:keys [backend path]} (:datahike config)]
    (log/info (str "Initializing datahike [backend=" (name backend)
                   (when (= backend :file) (str " path=" path)) "]")))
  (let [conn (datahike/create-connection! (:datahike config))]
    ;; Transact additional schemas with raw conn (lifecycle)
    (usage/transact-schema! conn)
    (usage/seed-default-pricing! conn)
    (migrations/run-migrations! conn)
    (datahike/->DatahikeEntityStore conn)))

(defmethod ig/halt-key! :persistence/datahike [_ store]
  (log/info "Releasing datahike connection")
  (d/release (:conn store)))

(defmethod ig/init-key :persistence/proximum [_ {:keys [config]}]
  (let [{:keys [backend path]} (:proximum config)
        prox-config (:proximum config)]
    (log/info (str "Initializing proximum [backend=" (name backend)
                   (when (= backend :file) (str " path=" path)) "]"))
    (atom (proximum/->ProximumVectorStore
           (proximum/create-index! prox-config) prox-config))))

;; -- Providers --

(defmethod ig/init-key :provider/openai [_ {:keys [config]}]
  (let [openai-config (:openai config)]
    (when (str/blank? (:api-key openai-config))
      (throw (ex-info "OPENAI_API_KEY not set. Set it in .env or as environment variable."
                      {:component :provider/openai})))
    (log/info "Initializing OpenAI client")
    (openai/create-client openai-config)))

(defmethod ig/init-key :provider/groq [_ {:keys [config]}]
  (let [groq-config (:groq config)]
    (when (str/blank? (:api-key groq-config))
      (throw (ex-info "GROQ_API_KEY not set. Set it in .env or as environment variable."
                      {:component :provider/groq})))
    (log/info "Initializing Groq client")
    (groq/create-client groq-config)))

;; -- Deps (shared by handlers that delegate to operation functions) --

(defmethod ig/init-key :memlayer/deps [_ {:keys [db vector openai groq config]}]
  (log/info "Building deps")
  {:db                 db
   :vector-index       vector
   :embedding-provider openai
   :chat-provider      groq
   :prompts            (:prompts config)
   :tuning             (:tuning config)})

;; -- Router --

(defmethod ig/init-key :memlayer/router
  [_ {:keys [retain recall forget evict ingest batch-retain reflect
             ws-ingest admin namespaces memories stats dashboard
             mcp retention-flow config]}]
  (log/info "Creating router")
  (router/create-router {:retain         retain
                         :recall         recall
                         :forget         forget
                         :evict          evict
                         :ingest         ingest
                         :batch-retain   batch-retain
                         :reflect        reflect
                         :ws-ingest      ws-ingest
                         :admin          admin
                         :namespaces     namespaces
                         :memories       memories
                         :stats          stats
                         :dashboard      dashboard
                         :mcp            mcp
                         :retention-flow retention-flow
                         :config         config}))

;; -- Server --

(defmethod ig/init-key :memlayer/server [_ {:keys [handler config]}]
  (let [port (get-in config [:server :port])]
    (server/start! handler port)))

(defmethod ig/halt-key! :memlayer/server [_ stop-fn]
  (server/stop! stop-fn))

;; -- System config --

(def system-config
  {:memlayer/config {}

   :persistence/datahike  {:config (ig/ref :memlayer/config)}
   :persistence/proximum  {:config (ig/ref :memlayer/config)}

   :provider/openai {:config (ig/ref :memlayer/config)}
   :provider/groq   {:config (ig/ref :memlayer/config)}

   :memlayer/deps {:db      (ig/ref :persistence/datahike)
                   :vector  (ig/ref :persistence/proximum)
                   :openai  (ig/ref :provider/openai)
                   :groq    (ig/ref :provider/groq)
                   :config  (ig/ref :memlayer/config)}

   :memlayer/retention-flow {:deps   (ig/ref :memlayer/deps)
                             :config (ig/ref :memlayer/config)}

   :handler/retain       {:flow (ig/ref :memlayer/retention-flow)}
   :handler/recall       {:deps (ig/ref :memlayer/deps)}
   :handler/forget       {:deps (ig/ref :memlayer/deps)}
   :handler/evict        {:deps (ig/ref :memlayer/deps)}
   :handler/ingest       {:flow (ig/ref :memlayer/retention-flow)}
   :handler/batch-retain {:flow (ig/ref :memlayer/retention-flow)
                          :deps (ig/ref :memlayer/deps)}
   :handler/reflect      {:deps (ig/ref :memlayer/deps)}
   :handler/ws-ingest    {:flow (ig/ref :memlayer/retention-flow)
                          :deps (ig/ref :memlayer/deps)}
   :handler/admin        {:db           (ig/ref :persistence/datahike)
                          :vector-index (ig/ref :persistence/proximum)}
   :handler/namespaces   {:db (ig/ref :persistence/datahike)}
   :handler/memories     {:db  (ig/ref :persistence/datahike)
                          :deps (ig/ref :memlayer/deps)}
   :handler/stats        {:deps (ig/ref :memlayer/deps)}
   :handler/dashboard    {:db (ig/ref :persistence/datahike)}
   :handler/mcp          {:flow (ig/ref :memlayer/retention-flow)
                          :deps (ig/ref :memlayer/deps)}

   :memlayer/router {:retain         (ig/ref :handler/retain)
                     :recall         (ig/ref :handler/recall)
                     :forget         (ig/ref :handler/forget)
                     :evict          (ig/ref :handler/evict)
                     :ingest         (ig/ref :handler/ingest)
                     :batch-retain   (ig/ref :handler/batch-retain)
                     :reflect        (ig/ref :handler/reflect)
                     :ws-ingest      (ig/ref :handler/ws-ingest)
                     :admin          (ig/ref :handler/admin)
                     :namespaces     (ig/ref :handler/namespaces)
                     :memories       (ig/ref :handler/memories)
                     :stats          (ig/ref :handler/stats)
                     :dashboard      (ig/ref :handler/dashboard)
                     :mcp            (ig/ref :handler/mcp)
                     :retention-flow (ig/ref :memlayer/retention-flow)
                     :config         (ig/ref :memlayer/config)}

   :memlayer/server {:handler (ig/ref :memlayer/router)
                     :config  (ig/ref :memlayer/config)}})

;; NOTE: :memlayer/local-server init-key is defined in memlayer.local
;; to keep local-specific code out of this namespace.

(def local-system-config
  "Integrant config for local mode — serves dashboard from bundled static assets."
  (-> system-config
      (dissoc :memlayer/server)
      (assoc :memlayer/local-server {:handler (ig/ref :memlayer/router)
                                     :config  (ig/ref :memlayer/config)})))

(def mcp-system-config
  "Integrant config for MCP mode — core persistence + LLM providers only.
   No HTTP router or server."
  {:memlayer/config    {}
   :persistence/datahike {:config (ig/ref :memlayer/config)}
   :persistence/proximum {:config (ig/ref :memlayer/config)}
   :provider/openai      {:config (ig/ref :memlayer/config)}
   :provider/groq        {:config (ig/ref :memlayer/config)}})

(defn start-system! []
  (ig/init system-config))

(defn start-local-system! []
  (ig/init local-system-config))

(defn start-mcp-system! []
  (ig/init mcp-system-config))

(defn stop-system! [system]
  (ig/halt! system))

(defn -main [& _args]
  (let [info @version/build-info]
    (log/info (str "memlayer " (:version info) " (" (:git-sha info) ") built " (:built-at info))))
  (log/info "Starting memlayer HTTP server")
  (let [system (start-system!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(stop-system! system)))
    (log/info "Memlayer system started")))
