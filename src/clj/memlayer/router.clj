(ns memlayer.router
  "Reitit router with JSON middleware and Malli coercion."
  (:require [reitit.ring :as ring]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.ring.coercion :as coercion]
            [reitit.coercion.malli :as malli-coercion]
            [muuntaja.core :as m]
            [clojure.core.async.flow :as flow]
            [memlayer.json :as json]
            [memlayer.schema :as schema]
            [memlayer.api.health :as health]
            [memlayer.middleware.firebase-auth :as firebase-auth]
            [memlayer.middleware.rate-limit :as rate-limit]
            [memlayer.middleware.cors :as cors]
            [memlayer.middleware.trace :as trace]
            [memlayer.operations.flow.event-log :as event-log]))

;; Ordered pipeline stages — this vector controls display order.
(def ^:private pipeline-stages
  [{:pid :prepare-context :label "Prepare Context" :workload "compute"
    :description "Build LLM context, check limits"}
   {:pid :batch-extract   :label "Batch Extract"   :workload "io"
    :description "Extract structured memories via LLM"}
   {:pid :embed-and-dedup :label "Embed & Dedup"   :workload "io"
    :description "Embed content, search duplicates"}
   {:pid :decide          :label "Decide"          :workload "io"
    :description "LLM decides CREATE/UPDATE/DELETE/NOOP"}
   {:pid :execute         :label "Execute"         :workload "compute"
    :description "DB writes, resolve promises"}])

(def ^:private pipeline-connections
  [["prepare-context" "batch-extract"]
   ["batch-extract" "embed-and-dedup"]
   ["embed-and-dedup" "decide"]
   ["decide" "execute"]])

(defn- extract-proc-state
  "Extract safe, serializable state from a process ping result."
  [_pid info]
  (let [state (::flow/state info)]
    (when (map? state)
      (cond-> {}
        (:recent-ops state) (assoc :recent-ops (:recent-ops state))
        (:batches state)    (assoc :in-flight-batches (count (:batches state)))))))

(defn- normalize-ping-result [ping-result]
  (mapv (fn [stage]
          (let [pid  (:pid stage)
                info (get ping-result pid)]
            (merge (dissoc stage :pid)
                   {:pid    (name pid)
                    :status (if info
                              (some-> (::flow/status info) name)
                              "unreachable")
                    :count  (or (::flow/count info) 0)
                    :state  (when info (extract-proc-state pid info))})))
        pipeline-stages))

(defn- make-pipeline-graph-handler [retention-flow]
  (fn [_]
    (let [graph   (:graph retention-flow)
          ping    (when graph (flow/ping graph :timeout-ms 2000))
          procs   (normalize-ping-result ping)]
      {:status 200
       :body   {:processes   procs
                :connections pipeline-connections}})))

(defn create-router
  "Create the reitit ring router with all routes and middleware.
   Accepts pre-built handler fns and middleware config."
  [{:keys [;; Pre-built handler fns (from Integrant init-keys)
           retain recall forget ingest batch-retain reflect
           ws-ingest admin namespaces memories stats dashboard mcp
           ;; Pipeline status
           retention-flow
           ;; Middleware config
           auth-config firebase rate-limit db dynamodb
           ;; Full app config (for CORS port, etc.)
           config]}]
  (let [firebase-pid   (get-in firebase [:project-id] "memlayer-c69b1")
        e2e-mode?      (:e2e-mode auth-config)
        ;; auth-enabled is an internal key for unit tests only (not in config schema).
        ;; Production config omits it, defaulting to true (auth always on).
        auth-enabled?  (:auth-enabled auth-config true)
        dashboard-auth {:db                  db
                        :firebase-project-id firebase-pid
                        :e2e-mode?           e2e-mode?
                        :auth-enabled?       auth-enabled?}
        api-auth       {:db                  db
                        :legacy-api-key-hash (:api-key-hash auth-config)
                        :e2e-mode?           e2e-mode?
                        :auth-enabled?       auth-enabled?}
        rl-config      rate-limit
        ddb            dynamodb
        limiter        (rate-limit/create-limiter
                        (cond-> {:max-requests (:max-requests rl-config 60)
                                 :window-ms    (:window-ms rl-config 60000)}
                          ddb (assoc :ddb-client  (:client ddb)
                                     :table-name  (:table ddb))))
        rl-mw          {:limiter  limiter
                        :enabled? (and (:enabled rl-config true) (not e2e-mode?))}
        pipeline-status (fn [_]
                          {:status 200
                           :body   {:running true}})
        pipeline-graph  (make-pipeline-graph-handler retention-flow)
        pipeline-ops    (fn [_]
                          {:status 200
                           :body   {:operations (or (when-let [log (:event-log retention-flow)]
                                                      (event-log/get-operations log))
                                                    [])}})]
    (trace/wrap-trace-id
     (cors/wrap-cors
      (ring/ring-handler
       (ring/router
        [["/health" {:get {:handler health/handler}}]

        ;; -- MCP route (X-API-Key auth, handler manages own serialization) --
         ["/mcp" {:middleware [[firebase-auth/wrap-api-auth api-auth]]
                  :post   {:handler (:post mcp)}
                  :delete {:handler (:delete mcp)}}]

         ;; -- WebSocket route (outside middleware to avoid body parsing) --
         ["/api/v1/ingest/stream" {:get {:handler ws-ingest}}]

         ;; -- Memory API routes (X-API-Key auth) --
         ["/api/v1" {:middleware [[firebase-auth/wrap-api-auth api-auth]
                                  [rate-limit/wrap-rate-limit rl-mw]]}
          ["/retain" {:post {:handler    retain
                             :parameters {:body [:map
                                                 [:content :string]
                                                 [:source :string]
                                                 [:namespace {:optional true :default "default"} schema/NamespaceSchema]]}
                             :coercion   malli-coercion/coercion}}]
          ["/recall" {:post {:handler    recall
                             :parameters {:body [:map
                                                 [:query :string]
                                                 [:namespace {:optional true :default "default"} schema/NamespaceSchema]
                                                 [:limit {:optional true} [:maybe :int]]
                                                 [:as-of {:optional true} [:maybe :string]]
                                                 [:expand-graph {:optional true} [:maybe :boolean]]
                                                 [:layer {:optional true} [:maybe :string]]]}
                             :coercion   malli-coercion/coercion}}]
          ["/forget" {:post {:handler    forget
                             :parameters {:body [:map
                                                 [:memory-id :string]]}
                             :coercion   malli-coercion/coercion}}]
          ["/ingest" {:post {:handler    ingest
                             :parameters {:body [:map
                                                 [:items [:vector [:map
                                                                   [:content :string]
                                                                   [:source :string]
                                                                   [:namespace {:optional true :default "default"} schema/NamespaceSchema]]]]]}
                             :coercion   malli-coercion/coercion}}]
          ["/retain/batch" {:post {:handler    batch-retain
                                   :parameters {:body [:map
                                                       [:namespace {:default "default"} schema/NamespaceSchema]
                                                       [:items [:vector [:map
                                                                         [:content :string]
                                                                         [:source {:optional true} :string]]]]]}
                                   :coercion   malli-coercion/coercion}}]
          ["/reflect" {:post {:handler    reflect
                              :parameters {:body [:map
                                                  [:dry-run {:optional true} [:maybe :boolean]]
                                                  [:namespace {:optional true :default "default"} schema/NamespaceSchema]
                                                  [:phases {:optional true} [:maybe [:vector :string]]]
                                                  [:since {:optional true} [:maybe [:or :string :int]]]]}
                              :coercion   malli-coercion/coercion}}]
          ["/admin/reset" {:post {:handler (:reset admin)}}]
          ["/memories" {:get {:handler (:list memories)}}]
          ["/memories/:id" {:get    {:handler (:get memories)}
                            :delete {:handler (:delete memories)}}]
          ["/memories/:id/children" {:get {:handler (:children memories)}}]
          ["/memories/:id/history" {:get {:handler (:history memories)}}]
          ["/relationships" {:post {:handler (:relationships memories)}}]
          ["/stats/memories" {:get {:handler (:memory-stats stats)}}]
          ["/stats/consistency" {:get {:handler (:consistency stats)}}]
          ["/namespaces" {:get {:handler (:list namespaces)}}]
          ["/pipeline/status" {:get {:handler pipeline-status}}]
          ["/pipeline/graph" {:get {:handler pipeline-graph}}]
          ["/pipeline/operations" {:get {:handler pipeline-ops}}]]

         ;; -- Account routes (Firebase JWT auth) --
         ["/api/v1/account" {:middleware [[firebase-auth/wrap-dashboard-auth dashboard-auth]
                                          [rate-limit/wrap-rate-limit rl-mw]]}
          ["/me" {:get {:handler (:me dashboard)}}]
          ["/tokens" {:get  {:handler (:list-tokens dashboard)}
                      :post {:handler (:create-token dashboard)}}]
          ["/tokens/:id" {:delete {:handler (:revoke-token dashboard)}}]
          ["/active-token" {:get {:handler (:active-token dashboard)}}]
          ["/settings" {:get {:handler (:settings dashboard)}}]
          ["/settings/keys" {:post   {:handler (:save-keys dashboard)}
                             :delete {:handler (:delete-keys dashboard)}}]
          ["/usage" {:get {:handler (:usage dashboard)}}]
          ["/namespaces" {:get  {:handler (:list-namespaces dashboard)}
                          :post {:handler (:create-namespace dashboard)}}]
          ["/namespaces/:id" {:put    {:handler (:rename-namespace dashboard)}
                              :delete {:handler (:delete-namespace dashboard)}}]]]
        {:data {:muuntaja   (m/create
                             (-> m/default-options
                                 (assoc-in [:formats "application/json" :encoder-opts]
                                           {:encode-key-fn json/encode-key})
                                 (assoc-in [:formats "application/json" :decoder-opts]
                                           {:decode-key-fn json/decode-key})))
                :middleware [parameters/parameters-middleware
                             muuntaja/format-middleware
                             coercion/coerce-exceptions-middleware
                             coercion/coerce-request-middleware
                             coercion/coerce-response-middleware]}})
       (ring/create-default-handler))
      {:dashboard-port (get-in config [:server :dashboard-port])}))))
