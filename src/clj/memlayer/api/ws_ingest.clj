(ns memlayer.api.ws-ingest
  "WebSocket handler for streaming document ingestion.

   Protocol (4-phase ping-pong, per memlayer-ingest.allium):
   1. Client connects, receives {\"type\": \"connected\"}
   2. Client sends auth: {\"type\": \"auth\", \"api_key\": \"mlk_...\"}
      Server responds {\"type\": \"auth_ok\"} or closes with error
   3. Client sends metadata: {\"type\": \"metadata\", \"source\", \"namespace\", \"file_name\", \"size_bytes\"}
      Server responds {\"type\": \"ready\"}
   4. Client streams content chunks, server acks each:
      C->S: {\"type\": \"content\", \"data\": \"...\"}
      S->C: {\"type\": \"ack\", \"percentage\", \"chunks_retained\"}
      When done: C->S: {\"type\": \"done\"}
      S->C: {\"type\": \"complete\", \"created\", \"updated\", \"total_chunks\"}

   Auth is always required. The streaming chunker accumulates text and emits
   semantic chunks at natural boundaries (~8KB). Semantic chunks are batch-retained
   in groups of 10 for efficiency."
  (:require [org.httpkit.server :as http]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [memlayer.operations.chunk :as chunker]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.operations.reflect :as reflect]
            [memlayer.persistence.tokens :as tokens]
            [integrant.core :as ig])
  (:import [java.util.concurrent Executors ScheduledExecutorService TimeUnit]))

(def ^:private batch-size 10)

(defn- send! [channel msg]
  (http/send! channel (pr-str msg)))

(def ^:private ^ScheduledExecutorService auth-timeout-scheduler
  (Executors/newSingleThreadScheduledExecutor))

(defn- validate-api-key
  "Validate an API key against the token database.
   Returns {:user-id ...} or nil."
  [db api-key]
  (when api-key
    (let [key-hash (tokens/sha256 api-key)
          token    (tokens/find-token-by-hash db key-hash)]
      (cond
        (nil? token)              nil
        (:token/revoked-at token) (do (log/warn "Revoked API key used on WebSocket") nil)
        :else                     {:user-id (:token/owner token)}))))

(defn- count-decisions
  "Count CREATE and UPDATE decisions from a batch-retain result.
   Also returns memory-ids for post-ingest graph highlighting."
  [result]
  (let [decisions (:decisions result)]
    {:created    (count (filter #(= "CREATE" (:type %)) decisions))
     :updated    (count (filter #(= "UPDATE" (:type %)) decisions))
     :memory-ids (filterv some? (mapv :memory-id decisions))}))

(defn- process-batch!
  "Submit semantic chunks to the retention flow. Returns {:created N :updated N :memory-ids [...]}."
  [flow chunks source namespace]
  (try
    (let [items  (mapv (fn [chunk] {:content chunk :source (or source "file-upload")}) chunks)
          result (retention-flow/submit! flow {:items items :namespace namespace :source source})]
      (if result
        (count-decisions result)
        {:created 0 :updated 0 :memory-ids []}))
    (catch Exception e
      (log/error e "Failed to process chunks" {:count (count chunks)})
      {:created 0 :updated 0 :memory-ids []})))

(defn- handle-content!
  "Handle a content message: feed to chunker, batch-retain if buffer is full, send ack."
  [flow channel state data]
  (future
    (try
      (let [data-len (count data)]
        (swap! state update :bytes-received + data-len)
        (let [emitted ((:feed! (:chunker @state)) data)]
          (swap! state update :batch-buffer into emitted)
          ;; Process full batches
          (loop []
            (when (>= (count (:batch-buffer @state)) batch-size)
              (let [batch (subvec (:batch-buffer @state) 0 batch-size)
                    rst   (subvec (:batch-buffer @state) batch-size)
                    {:keys [created updated memory-ids]}
                    (process-batch! flow batch (:source @state) (:namespace @state))]
                (swap! state
                       (fn [s]
                         (-> s
                             (assoc :batch-buffer rst)
                             (update :created + created)
                             (update :updated + updated)
                             (update :chunks-retained + (count batch))
                             (update :memory-ids into memory-ids))))
                (recur))))
          ;; Send ack
          (let [{:keys [bytes-received size-bytes chunks-retained]} @state
                pct (if (pos? size-bytes)
                      (min 99 (int (* 100 (/ bytes-received size-bytes))))
                      0)]
            (send! channel {:type             "ack"
                            :percentage       pct
                            :chunks-retained  chunks-retained}))))
      (catch Exception e
        (log/error e "Error processing content chunk")
        (send! channel {:type "error" :message "Processing failed"})
        (http/close channel)))))

(defn- handle-done!
  "Handle a done message: flush chunker, batch-retain remaining, reflect, send complete."
  [flow deps channel state]
  (future
    (try
      ;; Flush chunker
      (when-let [final ((:flush! (:chunker @state)))]
        (swap! state update :batch-buffer conj final))
      ;; Batch-retain remaining chunks
      (let [remaining (:batch-buffer @state)]
        (when (seq remaining)
          (let [{:keys [created updated memory-ids]}
                (process-batch! flow remaining (:source @state) (:namespace @state))]
            (swap! state
                   (fn [s]
                     (-> s
                         (assoc :batch-buffer [])
                         (update :created + created)
                         (update :updated + updated)
                         (update :chunks-retained + (count remaining))
                         (update :memory-ids into memory-ids)))))))
      ;; Best-effort reflect once
      (when (pos? (:chunks-retained @state))
        (try
          (reflect/reflect! deps {:dry-run false :namespace (:namespace @state)})
          (catch Exception e
            (log/warn "Post-ingest reflect failed:" (.getMessage e)))))
      ;; Send complete
      (send! channel {:type           "complete"
                      :created        (:created @state)
                      :updated        (:updated @state)
                      :total-chunks   (:chunks-retained @state)
                      :memory-ids     (:memory-ids @state)})
      (http/close channel)
      (catch Exception e
        (log/error e "WebSocket ingest completion failed")
        (send! channel {:type "error" :message "Processing failed"})
        (http/close channel)))))

(defn- handle-streaming-message
  "Handle a message in the :streaming state."
  [flow deps channel state msg]
  (case (:type msg)
    "content"
    (if (nil? (:data msg))
      (send! channel {:type "error" :message "Content data is required"})
      (handle-content! flow channel state (:data msg)))

    "done"
    (handle-done! flow deps channel state)

    ;; Unknown
    (send! channel {:type "error" :message (str "Unknown message type: " (:type msg))})))

(defn handler
  "WebSocket handler for /api/v1/ingest/stream.
   Uses http-kit's async WebSocket support.
   Authentication is always required via API token."
  [flow deps]
  (let [db (:db deps)]
    (fn [request]
      (let [conn-state (atom {:status          :awaiting-auth
                              :user-id         nil
                              :chunker         nil
                              :batch-buffer    []
                              :source          nil
                              :namespace       nil
                              :size-bytes      0
                              :bytes-received  0
                              :created         0
                              :updated         0
                              :chunks-retained 0
                              :memory-ids      []})]
        (http/as-channel request
                         {:on-open
                          (fn [channel]
                            (log/info "WebSocket ingest connection opened")
                            (send! channel {:type "connected"})
                            ;; Close connection if no auth within 10 seconds
                            (.schedule auth-timeout-scheduler
                                       ^Runnable
                                       (fn []
                                         (when (= :awaiting-auth (:status @conn-state))
                                           (log/warn "WebSocket auth timeout - closing connection")
                                           (send! channel {:type    "error"
                                                           :message "authentication timeout"})
                                           (http/close channel)))
                                       10 TimeUnit/SECONDS))

                          :on-receive
                          (fn [channel data]
                            (try
                              (let [msg (edn/read-string data)]
                                (case (:status @conn-state)
                                  :awaiting-auth
                                  (if (= "auth" (:type msg))
                                    (if-let [user-ctx (validate-api-key db (:api-key msg))]
                                      (do
                                        (swap! conn-state assoc
                                               :status :authenticated
                                               :user-id (:user-id user-ctx))
                                        (log/info "WebSocket authenticated for user:" (:user-id user-ctx))
                                        (send! channel {:type "auth_ok"}))
                                      (do
                                        (log/warn "WebSocket authentication failed")
                                        (send! channel {:type    "error"
                                                        :message "authentication failed"})
                                        (http/close channel)))
                                    (do
                                      (send! channel {:type    "error"
                                                      :message "authentication required"})
                                      (http/close channel)))

                                  :authenticated
                                  (if (= "metadata" (:type msg))
                                    (do
                                      (swap! conn-state assoc
                                             :status     :streaming
                                             :chunker    (chunker/make-stream-chunker)
                                             :source     (:source msg)
                                             :namespace  (:namespace msg)
                                             :size-bytes (or (:size-bytes msg) 0))
                                      (log/info "WebSocket streaming started"
                                                {:file-name  (:file-name msg)
                                                 :size-bytes (:size-bytes msg)})
                                      (send! channel {:type "ready"}))
                                    (do
                                      (send! channel {:type    "error"
                                                      :message "metadata message expected"})
                                      (http/close channel)))

                                  :streaming
                                  (handle-streaming-message flow deps channel conn-state msg)

                                  ;; Default: unexpected state
                                  (do (log/error "WebSocket in unexpected state:" (:status @conn-state))
                                      (http/close channel))))
                              (catch Exception e
                                (log/error e "Failed to process WebSocket message")
                                (send! channel {:type    "error"
                                                :message "Invalid message format"}))))

                          :on-close
                          (fn [_channel status]
                            (log/info "WebSocket ingest connection closed:" status))})))))

(defmethod ig/init-key :handler/ws-ingest [_ {:keys [flow deps config]}]
  (handler flow (assoc deps :auth-config (:auth config))))
