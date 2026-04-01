(ns memlayer.operations.flow.retention-flow
  "Unified retention flow: a long-lived core.async.flow pipeline
   that handles all retain/batch-retain/ingest operations.

   The flow is an Integrant component. API handlers submit work via
   `submit!` and receive results through a correlation map."
  (:require [clojure.core.async.flow :as flow]
            [clojure.core.async :as a]
            [memlayer.operations.flow.processes :as procs]
            [memlayer.operations.flow.correlation :as corr]
            [memlayer.operations.flow.event-log :as event-log]
            [memlayer.schema :as schema]
            [integrant.core :as ig]
            [clojure.tools.logging :as log])
  (:import [java.util.concurrent Executors ExecutorService]))

(defn- start-error-consumer!
  "Consume the flow's error channel, logging errors.
   Returns a future that runs until the channel closes."
  [error-chan]
  (future
    (loop []
      (when-let [err (a/<!! error-chan)]
        (log/error "Flow process error"
                   {:pid    (:pid err)
                    :status (:status err)
                    :error  (some-> (::flow/ex err) .getMessage)})
        (recur)))))

(defn create-flow-config
  "Build the flow configuration map from deps.
   Deps are passed to proc factories (closures), not as :args,
   so flow/ping can serialize process state without
   encountering Java objects."
  [{:keys [db vector-index embedding-provider chat-provider prompts tuning cost-config
           correlation-map io-threads]}]
  (let [shared {:db                 db
                :vector-index       vector-index
                :embedding-provider embedding-provider
                :chat-provider      chat-provider
                :prompts            prompts
                :tuning             tuning
                :correlation-map    correlation-map
                :cost-config        cost-config}]
    {:procs
     {:prepare-context {:proc (procs/prepare-context-proc shared)}
      :batch-extract   {:proc (procs/batch-extract-proc shared)}
      :embed-and-dedup {:proc (procs/embed-and-dedup-proc shared)}
      :decide          {:proc (procs/decide-proc shared)}
      :execute         {:proc (procs/execute-proc shared)}}

     :conns
     [[[:prepare-context :out] [:batch-extract :in]]
      [[:batch-extract :out] [:embed-and-dedup :in]]
      [[:embed-and-dedup :out] [:decide :in]]
      [[:decide :out] [:execute :in]]]

     :io-exec (Executors/newFixedThreadPool (or io-threads 8))}))

(defn start-flow!
  "Create and start the retention flow. Returns the flow handle map."
  [config]
  (let [g    (flow/create-flow config)
        chans (flow/start g)]
    (flow/resume g)
    {:graph      g
     :report-chan (:report-chan chans)
     :error-chan  (:error-chan chans)}))

(defn submit!
  "Submit a batch of items to the retention flow and await the result.
   Items is a seq of {:content :source}. Returns the result map (with :operation-id) or nil on timeout."
  [{:keys [graph correlation-map timeout-ms event-log]} {:keys [items namespace source]}]
  (let [namespace (or namespace schema/default-namespace)
        [cid chan] (corr/register! correlation-map)
        timeout   (or timeout-ms 120000)]
    (when event-log
      (event-log/record-start! event-log cid items namespace source))
    (flow/inject graph [:prepare-context :in]
                 [{:correlation-id cid
                   :items          (vec items)
                   :namespace      namespace
                   :source         (or source "api")}])
    (let [result (corr/await-result correlation-map cid chan timeout)]
      (when event-log
        (if result
          (event-log/record-complete! event-log cid result)
          (event-log/record-timeout! event-log cid)))
      (when result
        (assoc result :operation-id (str cid))))))

;; -- Standalone lifecycle (for MCP mode, REPL) --

(defn start-standalone!
  "Start a retention flow without Integrant. Returns the flow handle map."
  [deps config]
  (let [flow-config     (:flow config)
        cost-config     (:cost config)
        correlation-map (corr/create-correlation-map)
        fc              (create-flow-config
                         (assoc deps
                                :correlation-map correlation-map
                                :cost-config     cost-config
                                :io-threads      (:io-threads flow-config)))
        {:keys [graph error-chan]} (start-flow! fc)
        error-consumer  (start-error-consumer! error-chan)]
    {:graph           graph
     :correlation-map correlation-map
     :event-log       (event-log/create-log)
     :error-consumer  error-consumer
     :io-exec         (:io-exec fc)
     :timeout-ms      (:submit-timeout-ms flow-config 120000)}))

(defn stop-standalone!
  "Stop a standalone retention flow."
  [{:keys [graph correlation-map ^ExecutorService io-exec]}]
  (flow/stop graph)
  (corr/drain! correlation-map)
  (when io-exec (.shutdown io-exec)))

;; -- Integrant lifecycle --

(defmethod ig/init-key :memlayer/retention-flow
  [_ {:keys [deps config]}]
  (log/debug "Starting retention flow")
  (let [flow-config    (:flow config)
        cost-config    (:cost config)
        correlation-map (corr/create-correlation-map)
        fc             (create-flow-config
                        (assoc deps
                               :correlation-map correlation-map
                               :cost-config     cost-config
                               :io-threads      (:io-threads flow-config)))
        {:keys [graph report-chan error-chan]} (start-flow! fc)
        error-consumer (start-error-consumer! error-chan)]
    {:graph           graph
     :correlation-map correlation-map
     :event-log       (event-log/create-log)
     :report-chan     report-chan
     :error-chan      error-chan
     :error-consumer  error-consumer
     :io-exec         (:io-exec fc)
     :timeout-ms      (:submit-timeout-ms flow-config 120000)}))

(defmethod ig/halt-key! :memlayer/retention-flow
  [_ {:keys [graph correlation-map ^ExecutorService io-exec]}]
  (log/debug "Stopping retention flow")
  (flow/stop graph)
  (corr/drain! correlation-map)
  (when io-exec (.shutdown io-exec)))
