(ns memlayer.integration.test-system
  "Boots a real memlayer system for integration testing.
   Uses in-memory datahike/proximum + real LLM providers."
  (:require [integrant.core :as ig]
            [memlayer.config :as config]
            [memlayer.persistence.datahike :as datahike]
            [memlayer.persistence.proximum :as proximum]
            [memlayer.persistence.usage :as usage]
            [memlayer.provider.openai :as openai]
            [memlayer.provider.groq :as groq]
            [memlayer.router :as router]
            [memlayer.server :as server]
            ;; Retention flow
            [memlayer.operations.flow.retention-flow]
            ;; Handler namespaces — required so their ig/init-key defmethods are loaded
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
            [clojure.tools.logging :as log]))

;; Use a fixed port for e2e tests, overridable via env var for parallel workspaces
(def ^:private e2e-port
  (Long/parseLong (or (System/getenv "MEMLAYER_TEST_PORT") "18080")))

(defn- e2e-config []
  (let [base (config/load-config)]
    (-> base
        (assoc-in [:server :port] e2e-port)
        (assoc :datahike {:backend :memory}))))

;; Integrant methods that mirror system.clj but with e2e overrides

(defmethod ig/init-key ::config [_ _]
  (e2e-config))

(defmethod ig/init-key ::datahike [_ {:keys [config]}]
  (let [conn (datahike/create-connection! (:datahike config))]
    (usage/transact-schema! conn)
    (usage/seed-default-pricing! conn)
    (datahike/->DatahikeEntityStore conn)))

(defmethod ig/halt-key! ::datahike [_ store]
  (d/release (:conn store)))

(defmethod ig/init-key ::proximum [_ {:keys [config]}]
  (let [prox-config (:proximum config)]
    (atom (proximum/->ProximumVectorStore
           (proximum/create-index! prox-config) prox-config))))

(defmethod ig/init-key ::openai [_ {:keys [config]}]
  (openai/create-client (:openai config)))

(defmethod ig/init-key ::groq [_ {:keys [config]}]
  (groq/create-client (:groq config)))

(defmethod ig/init-key ::deps [_ {:keys [db vector openai groq config]}]
  {:db                 db
   :vector-index       vector
   :embedding-provider openai
   :chat-provider      groq
   :prompts            (:prompts config)
   :tuning             (:tuning config)})

(defmethod ig/init-key ::router
  [_ {:keys [retain recall forget evict ingest batch-retain reflect
             ws-ingest admin namespaces memories stats dashboard mcp
             retention-flow config]}]
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

(defmethod ig/init-key ::server [_ {:keys [handler config]}]
  (let [port (get-in config [:server :port])]
    (log/info "Starting e2e test server on port" port)
    (server/start! handler port)))

(defmethod ig/halt-key! ::server [_ stop-fn]
  (server/stop! stop-fn))

(def e2e-system-config
  {::config {}
   ::datahike  {:config (ig/ref ::config)}
   ::proximum  {:config (ig/ref ::config)}
   ::openai    {:config (ig/ref ::config)}
   ::groq      {:config (ig/ref ::config)}

   ::deps {:db      (ig/ref ::datahike)
           :vector  (ig/ref ::proximum)
           :openai  (ig/ref ::openai)
           :groq    (ig/ref ::groq)
           :config  (ig/ref ::config)}

   :memlayer/retention-flow {:deps   (ig/ref ::deps)
                             :config (ig/ref ::config)}

   ;; Handlers
   :handler/retain       {:flow (ig/ref :memlayer/retention-flow)}
   :handler/recall       {:deps (ig/ref ::deps)}
   :handler/forget       {:deps (ig/ref ::deps)}
   :handler/evict        {:deps (ig/ref ::deps)}
   :handler/ingest       {:flow (ig/ref :memlayer/retention-flow)}
   :handler/batch-retain {:flow (ig/ref :memlayer/retention-flow)
                          :deps (ig/ref ::deps)}
   :handler/reflect      {:deps (ig/ref ::deps)}
   :handler/ws-ingest    {:flow   (ig/ref :memlayer/retention-flow)
                          :deps   (ig/ref ::deps)
                          :config (ig/ref ::config)}
   :handler/admin        {:db           (ig/ref ::datahike)
                          :vector-index (ig/ref ::proximum)}
   :handler/namespaces   {:db (ig/ref ::datahike)}
   :handler/memories     {:db   (ig/ref ::datahike)
                          :deps (ig/ref ::deps)}
   :handler/stats        {:deps (ig/ref ::deps)}
   :handler/dashboard    {:db (ig/ref ::datahike)}
   :handler/mcp          {:flow (ig/ref :memlayer/retention-flow)
                          :deps (ig/ref ::deps)}

   ;; Router + Server
   ::router {:retain         (ig/ref :handler/retain)
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
             :config         (ig/ref ::config)}

   ::server {:handler (ig/ref ::router)
             :config  (ig/ref ::config)}})

(def ^:dynamic *system* nil)
(def ^:dynamic *base-url* nil)

(defn start-test-system! []
  (log/info "Starting e2e test system")
  (let [sys (ig/init e2e-system-config)]
    (alter-var-root #'*system* (constantly sys))
    (alter-var-root #'*base-url* (constantly (str "http://localhost:" e2e-port)))
    sys))

(defn stop-test-system! [sys]
  (log/info "Stopping e2e test system")
  (ig/halt! sys)
  (alter-var-root #'*system* (constantly nil))
  (alter-var-root #'*base-url* (constantly nil)))

(defn base-url []
  (or *base-url* (str "http://localhost:" e2e-port)))
