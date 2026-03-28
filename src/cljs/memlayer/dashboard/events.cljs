(ns memlayer.dashboard.events
  (:require [ajax.core :as ajax]
            [cljs.reader :as reader]
            [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]
            [memlayer.dashboard.config :as config]
            [memlayer.dashboard.db :as db]
            [memlayer.dashboard.fx :as fx]))

;; File reference held outside app-db (JS File is not serializable).
;; Used by file-upload events and the :ws-ingest effect.
(defonce ^:private file-ref (atom nil))

;; -- Init --

(rf/reg-event-db
 :initialize-db
 (fn [_ _]
   (let [stored-ns (try (.getItem js/localStorage "memlayer-namespace")
                        (catch :default _ nil))]
     (cond-> db/default-db
       (and stored-ns (seq stored-ns))
       (assoc :active-namespace stored-ns)))))

;; -- Routing --

(rf/reg-event-db
 :set-route
 (fn [db [_ match]]
   (assoc db :route match)))

;; -- Health --

(rf/reg-event-fx
 :fetch-health
 (fn [{:keys [db]} _]
   {:db         (assoc-in db [:health :loading?] true)
    :http-xhrio {:method          :get
                 :uri             (config/server-url "/health")
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:fetch-health-success]
                 :on-failure      [:fetch-health-failure]}}))

(rf/reg-event-db
 :fetch-health-success
 (fn [db [_ result]]
   (assoc db :health {:status result :loading? false :error nil})))

(rf/reg-event-db
 :fetch-health-failure
 (fn [db [_ error]]
   (assoc db :health {:status nil :loading? false :error error})))

;; -- Pipeline Status --

(rf/reg-event-fx
 :fetch-pipeline-status
 (fn [{:keys [db]} _]
   (let [api-key (get-in db [:auth :active-api-key])]
     (when api-key
       {:http-xhrio (fx/api-get "/pipeline/status" api-key
                                [:fetch-pipeline-status-success]
                                [:fetch-pipeline-status-failure])}))))

(rf/reg-event-db
 :fetch-pipeline-status-success
 (fn [db [_ result]]
   (assoc db :pipeline-status result)))

(rf/reg-event-db
 :fetch-pipeline-status-failure
 (fn [db [_ _error]]
   (assoc db :pipeline-status nil)))

;; -- Stats --

(rf/reg-event-fx
 :fetch-memory-stats
 (fn [{:keys [db]} _]
   (let [api-key   (get-in db [:auth :active-api-key])
         namespace (:active-namespace db)
         path      (str "/stats/memories?namespace=" (or namespace "default"))]
     {:db         (assoc-in db [:memory-stats :loading?] true)
      :http-xhrio (fx/api-get path api-key
                              [:fetch-memory-stats-success]
                              [:fetch-memory-stats-failure])})))

(rf/reg-event-db
 :fetch-memory-stats-success
 (fn [db [_ result]]
   (assoc db :memory-stats {:data result :loading? false :error nil})))

(rf/reg-event-db
 :fetch-memory-stats-failure
 (fn [db [_ error]]
   (assoc db :memory-stats {:data nil :loading? false :error error})))

(rf/reg-event-fx
 :fetch-consistency
 (fn [{:keys [db]} _]
   (let [api-key (get-in db [:auth :active-api-key])]
     {:db         (assoc-in db [:consistency :loading?] true)
      :http-xhrio (fx/api-get "/stats/consistency" api-key
                              [:fetch-consistency-success]
                              [:fetch-consistency-failure])})))

(rf/reg-event-db
 :fetch-consistency-success
 (fn [db [_ result]]
   (assoc db :consistency {:data result :loading? false :error nil})))

(rf/reg-event-db
 :fetch-consistency-failure
 (fn [db [_ error]]
   (assoc db :consistency {:data nil :loading? false :error error})))

;; -- Memories --

(rf/reg-event-fx
 :fetch-memories
 (fn [{:keys [db]} _]
   (let [api-key   (get-in db [:auth :active-api-key])
         params    (get-in db [:memories :params])
         namespace (or (:active-namespace db) "default")
         query-str (str "/memories?"
                        "namespace=" namespace "&"
                        (when (:layer params) (str "layer=" (name (:layer params)) "&"))
                        "limit=" (:limit params 20)
                        "&offset=" (:offset params 0))]
     {:db         (assoc-in db [:memories :loading?] true)
      :http-xhrio (fx/api-get query-str api-key
                              [:fetch-memories-success]
                              [:fetch-memories-failure])})))

(rf/reg-event-db
 :fetch-memories-success
 (fn [db [_ result]]
   (-> db
       (assoc-in [:memories :items] (:memories result))
       (assoc-in [:memories :total] (:total result))
       (assoc-in [:memories :loading?] false)
       (assoc-in [:memories :error] nil))))

(rf/reg-event-db
 :fetch-memories-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:memories :loading?] false)
       (assoc-in [:memories :error] error))))

(rf/reg-event-db
 :set-memory-params
 (fn [db [_ params]]
   (update-in db [:memories :params] merge params)))

;; -- Single Memory --

(rf/reg-event-fx
 :fetch-memory
 (fn [{:keys [db]} [_ id]]
   (let [api-key (get-in db [:auth :active-api-key])]
     {:http-xhrio (fx/api-get (str "/memories/" id) api-key
                              [:fetch-memory-success]
                              [:fetch-memory-failure])})))

(rf/reg-event-db
 :fetch-memory-success
 (fn [db [_ result]]
   (assoc db :selected-memory (merge result (select-keys (:selected-memory db) [:relationships])))))

(rf/reg-event-db
 :fetch-memory-failure
 (fn [db [_ _error]]
   (assoc db :selected-memory nil)))

(rf/reg-event-db
 :close-memory-detail
 (fn [db _]
   (assoc db :selected-memory nil)))

;; -- Memory Relationships --

(rf/reg-event-fx
 :fetch-memory-relationships
 (fn [{:keys [db]} [_ id]]
   (let [api-key (get-in db [:auth :active-api-key])]
     {:http-xhrio (fx/api-post "/relationships"
                               {:memory-ids [id]}
                               api-key
                               [:fetch-memory-relationships-success]
                               [:fetch-memory-relationships-failure])})))

(rf/reg-event-db
 :fetch-memory-relationships-success
 (fn [db [_ result]]
   (assoc-in db [:selected-memory :relationships] (:relationships result))))

(rf/reg-event-db
 :fetch-memory-relationships-failure
 (fn [db [_ _error]]
   db))

;; -- Graph --

(rf/reg-event-fx
 :fetch-graph-data
 (fn [{:keys [db]} _]
   (let [api-key   (get-in db [:auth :active-api-key])
         namespace (or (:active-namespace db) "default")
         path      (str "/memories?namespace=" namespace "&limit=200")]
     {:db (assoc-in db [:graph :loading?] true)
      :http-xhrio (fx/api-get path api-key
                              [:fetch-graph-data-success]
                              [:fetch-graph-data-failure])})))

(rf/reg-event-fx
 :fetch-graph-data-success
 (fn [{:keys [db]} [_ result]]
   (let [memories (:memories result)
         ids      (mapv :id memories)]
     (cond-> {:db (-> db
                      (assoc-in [:graph :memories] memories)
                      (assoc-in [:graph :loading?] false))}
       (seq ids) (assoc :dispatch [:fetch-graph-relationships ids])))))

(rf/reg-event-db
 :fetch-graph-data-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:graph :loading?] false)
       (assoc-in [:graph :error] error))))

(rf/reg-event-fx
 :fetch-graph-relationships
 (fn [{:keys [db]} [_ memory-ids]]
   (let [api-key (get-in db [:auth :active-api-key])]
     {:http-xhrio (fx/api-post "/relationships"
                               {:memory-ids memory-ids}
                               api-key
                               [:fetch-graph-relationships-success]
                               [:fetch-graph-relationships-failure])})))

(rf/reg-event-db
 :fetch-graph-relationships-success
 (fn [db [_ result]]
   (assoc-in db [:graph :relationships] (:relationships result))))

(rf/reg-event-db
 :fetch-graph-relationships-failure
 (fn [db [_ _error]]
   db))

(rf/reg-event-db
 :toggle-layer-visibility
 (fn [db [_ layer]]
   (update-in db [:graph :visible-layers]
              (fn [layers]
                (if (contains? layers layer)
                  (disj layers layer)
                  (conj layers layer))))))

(rf/reg-event-db
 :toggle-hierarchy
 (fn [db _]
   (update-in db [:graph :show-hierarchy?] not)))

(rf/reg-event-db
 :graph/set-zoom-level
 (fn [db [_ level]]
   (assoc-in db [:graph :zoom-level] level)))

(rf/reg-event-db
 :graph/select-node
 (fn [db [_ node-id]]
   (assoc-in db [:graph :selected-node-id] node-id)))

(rf/reg-event-db
 :graph/clear-selection
 (fn [db _]
   (assoc-in db [:graph :selected-node-id] nil)))

(rf/reg-fx
 :navigate!
 (fn [route-name]
   (rfe/push-state route-name)))

(rf/reg-event-fx
 :graph/navigate-with-highlights
 (fn [{:keys [db]} [_ memory-ids]]
   {:db        (assoc-in db [:graph :highlighted-ids] (set (map str memory-ids)))
    :navigate! :graph
    :dispatch  [:fetch-graph-data]}))

(rf/reg-event-db
 :graph/clear-highlights
 (fn [db _]
   (-> db
       (assoc-in [:graph :highlighted-ids] #{})
       (assoc-in [:graph :highlighted-rel-ids] #{})
       (assoc-in [:graph :recall :results] nil)
       (assoc-in [:graph :recall :error] nil))))

;; -- Graph: Recall --

(rf/reg-event-db
 :graph/set-recall-query
 (fn [db [_ query]]
   (assoc-in db [:graph :recall :query] query)))

(rf/reg-event-fx
 :graph/recall!
 (fn [{:keys [db]} _]
   (let [api-key    (get-in db [:auth :active-api-key])
         query      (get-in db [:graph :recall :query])
         namespace  (let [ns (:active-namespace db)]
                      ns)
         ;; Use graph size as limit so activation covers the full graph
         graph-size (count (get-in db [:graph :memories]))]
     (when (seq query)
       {:db         (-> db
                        (assoc-in [:graph :recall :loading?] true)
                        (assoc-in [:graph :recall :error] nil))
        :http-xhrio (fx/api-post "/recall"
                                 {:query        query
                                  :namespace    namespace
                                  :expand-graph true
                                  :limit        (max 10 graph-size)}
                                 api-key
                                 [:graph/recall-success]
                                 [:graph/recall-failure])}))))

(rf/reg-event-db
 :graph/recall-success
 (fn [db [_ result]]
   (let [activation  (:activation result)
         memory-ids  (set (or (:memory-ids activation) []))
         rel-ids     (set (or (:relationship-ids activation) []))]
     (-> db
         (assoc-in [:graph :recall :loading?] false)
         (assoc-in [:graph :recall :results] result)
         (assoc-in [:graph :highlighted-ids] memory-ids)
         (assoc-in [:graph :highlighted-rel-ids] rel-ids)))))

(rf/reg-event-db
 :graph/recall-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:graph :recall :loading?] false)
       (assoc-in [:graph :recall :error] error))))

;; -- Reflect (Consolidate) --

(rf/reg-event-fx
 :reflect!
 (fn [{:keys [db]} [_ since]]
   (let [api-key   (get-in db [:auth :active-api-key])
         namespace (:active-namespace db)]
     {:db         (assoc db :reflect-loading? true)
      :http-xhrio (fx/api-post "/reflect"
                               (cond-> {:namespace (or namespace "default")}
                                 since (assoc :since since))
                               api-key
                               [:reflect-success]
                               [:reflect-failure])})))

(rf/reg-event-fx
 :reflect-success
 (fn [{:keys [db]} [_ _result]]
   {:db       (assoc db :reflect-loading? false)
    :dispatch [:fetch-graph-data]}))

(rf/reg-event-db
 :reflect-failure
 (fn [db [_ _error]]
   (assoc db :reflect-loading? false)))

;; -- Playground: Retain --

(rf/reg-event-db
 :set-retain-field
 (fn [db [_ field value]]
   (when (= field :content)
     (reset! file-ref nil))
   (cond-> (assoc-in db [:playground :retain :request field] value)
     ;; Typing content clears file selection (mutual exclusivity)
     (= field :content) (assoc-in [:playground :file-upload :status] :open)
     (= field :content) (assoc-in [:playground :file-upload :file-name] nil)
     (= field :content) (assoc-in [:playground :file-upload :file-size] nil))))

(rf/reg-event-fx
 :retain!
 (fn [{:keys [db]} _]
   (let [api-key   (get-in db [:auth :active-api-key])
         request   (get-in db [:playground :retain :request])
         namespace (:active-namespace db)
         request   (assoc request :namespace namespace)]
     {:db         (-> db
                      (assoc-in [:playground :retain :loading?] true)
                      (assoc-in [:playground :retain :error] nil))
      :http-xhrio (fx/api-post "/retain"
                               request
                               api-key
                               [:retain-success]
                               [:retain-failure])})))

(rf/reg-event-fx
 :retain-success
 (fn [{:keys [db]} [_ result]]
   {:db       (-> db
                  (assoc-in [:playground :retain :response] result)
                  (assoc-in [:playground :retain :loading?] false)
                  (assoc-in [:playground :retain :error] nil)
                  (assoc-in [:playground :retain :request :content] ""))
    :dispatch [:fetch-memory-stats]}))

(rf/reg-event-db
 :retain-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:playground :retain :loading?] false)
       (assoc-in [:playground :retain :error] error))))

;; -- Playground: Recall --

(rf/reg-event-db
 :set-recall-field
 (fn [db [_ field value]]
   (assoc-in db [:playground :recall :params field] value)))

(rf/reg-event-fx
 :recall!
 (fn [{:keys [db]} _]
   (let [api-key   (get-in db [:auth :active-api-key])
         params    (get-in db [:playground :recall :params])
         namespace (:active-namespace db)
         params    (assoc params :namespace namespace)]
     {:db         (-> db
                      (assoc-in [:playground :recall :loading?] true)
                      (assoc-in [:playground :recall :error] nil))
      :http-xhrio (fx/api-post "/recall"
                               params
                               api-key
                               [:recall-success]
                               [:recall-failure])})))

(rf/reg-event-db
 :recall-success
 (fn [db [_ result]]
   (-> db
       (assoc-in [:playground :recall :results] result)
       (assoc-in [:playground :recall :loading?] false)
       (assoc-in [:playground :recall :error] nil))))

(rf/reg-event-db
 :recall-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:playground :recall :loading?] false)
       (assoc-in [:playground :recall :error] error))))

(rf/reg-event-db
 :set-playground-tab
 (fn [db [_ tab]]
   (assoc-in db [:playground :active-tab] tab)))

;; -- Playground: File Upload --
;; State machine: :closed → :open → :selected → :uploading → :processing → :complete
;;                                                         ↘ :error
;; File content is never stored in app-db. The JS File object is held in
;; file-ref atom (defined at top of file) and streamed over WebSocket.

(rf/reg-event-db
 :file-upload/toggle
 (fn [db _]
   (let [status (get-in db [:playground :file-upload :status])]
     (assoc-in db [:playground :file-upload :status]
               (if (= :closed status) :open :closed)))))

(rf/reg-event-db
 :file-upload/select-file
 (fn [db [_ file]]
   (reset! file-ref file)
   (-> db
       (assoc-in [:playground :file-upload :status] :selected)
       (assoc-in [:playground :file-upload :file-name] (.-name file))
       (assoc-in [:playground :file-upload :file-size] (.-size file)))))

(rf/reg-event-db
 :file-upload/clear
 (fn [db _]
   (reset! file-ref nil)
   (-> db
       (assoc-in [:playground :file-upload :status] :open)
       (assoc-in [:playground :file-upload :file-name] nil)
       (assoc-in [:playground :file-upload :file-size] nil))))

;; -- WebSocket streaming effect --

(defn- send-edn! [ws msg]
  (.send ws (pr-str msg)))

(defn- read-and-send!
  "Read one chunk from the ReadableStream reader, decode it, and send over WebSocket.
   When the reader is exhausted, flush the TextDecoder and send a done message.
   The next call is triggered by the ack handler (continuation-passing)."
  [ws reader decoder]
  (-> (.read reader)
      (.then (fn [result]
               (if (.-done result)
                 ;; Flush decoder and send done
                 (let [remaining (.decode decoder)]
                   (when (pos? (.-length remaining))
                     (send-edn! ws {:type "content" :data remaining}))
                   (send-edn! ws {:type "done"}))
                 ;; Send decoded chunk, wait for ack to continue
                 (let [text (.decode decoder (.-value result) #js {:stream true})]
                   (send-edn! ws {:type "content" :data text})))))
      (.catch (fn [err]
                (rf/dispatch [:file-upload/error (str "File read error: " (.-message err))])))))

(rf/reg-fx
 :ws-ingest
 (fn [{:keys [file source namespace api-key]}]
   (let [url     (str config/api-ws-base "/ingest/stream")
         ws      (js/WebSocket. url)
         reader  (atom nil)
         decoder (atom nil)]
     (set! (.-onmessage ws)
           (fn [e]
             (let [msg (reader/read-string (.-data e))]
               (case (:type msg)
                 ;; Phase 1: connected -> send auth
                 "connected"
                 (send-edn! ws {:type "auth" :api-key api-key})

                 ;; Phase 2: auth ok -> send metadata
                 "auth_ok"
                 (send-edn! ws {:type       "metadata"
                                :source     source
                                :namespace  namespace
                                :file-name  (.-name file)
                                :size-bytes (.-size file)})

                 ;; Phase 3: ready -> start streaming file
                 "ready"
                 (do
                   (reset! reader (.getReader (.stream file)))
                   (reset! decoder (js/TextDecoder. "utf-8" #js {:stream true}))
                   (read-and-send! ws @reader @decoder))

                 ;; Phase 4: ack -> update progress, read next chunk
                 "ack"
                 (do
                   (rf/dispatch [:file-upload/progress msg])
                   (read-and-send! ws @reader @decoder))

                 "complete" (rf/dispatch [:file-upload/complete msg])
                 "error"    (rf/dispatch [:file-upload/error (:message msg)])
                 nil))))
     (set! (.-onerror ws)
           (fn [_]
             (rf/dispatch [:file-upload/error "WebSocket connection failed"])))
     (set! (.-onclose ws)
           (fn [_] nil)))))

(rf/reg-event-fx
 :file-upload/ingest!
 (fn [{:keys [db]} _]
   (let [retain  (get-in db [:playground :retain :request])
         api-key (get-in db [:auth :active-api-key])
         file    @file-ref]
     (if (nil? api-key)
       {:db (assoc-in db [:playground :file-upload :error]
                      "No API key available. Create a token in Settings first.")
        :dispatch [:auth/fetch-active-token]}
       {:db        (-> db
                       (assoc-in [:playground :file-upload :status] :uploading)
                       (assoc-in [:playground :file-upload :percentage] 0)
                       (assoc-in [:playground :file-upload :result] nil)
                       (assoc-in [:playground :file-upload :error] nil))
        :ws-ingest {:file      file
                    :source    (or (:source retain) "file-upload")
                    :namespace (let [ns (:active-namespace db)]
                                 ns)
                    :api-key   api-key}}))))

(rf/reg-event-db
 :file-upload/progress
 (fn [db [_ msg]]
   (-> db
       (assoc-in [:playground :file-upload :status] :processing)
       (assoc-in [:playground :file-upload :percentage] (:percentage msg))
       (assoc-in [:playground :file-upload :chunks-retained] (:chunks-retained msg)))))

(rf/reg-event-db
 :file-upload/complete
 (fn [db [_ msg]]
   (-> db
       (assoc-in [:playground :file-upload :status] :complete)
       (assoc-in [:playground :file-upload :percentage] 100)
       (assoc-in [:playground :file-upload :result] msg))))

(rf/reg-event-db
 :file-upload/error
 (fn [db [_ error-msg]]
   (-> db
       (assoc-in [:playground :file-upload :status] :error)
       (assoc-in [:playground :file-upload :error] error-msg))))

(rf/reg-event-db
 :file-upload/reset
 (fn [db _]
   (reset! file-ref nil)
   (assoc-in db [:playground :file-upload]
             {:status :closed :file-name nil :file-size nil :percentage 0
              :chunks-retained nil :result nil :error nil})))

;; -- Namespaces --

(rf/reg-event-fx
 :fetch-namespaces
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:db         (assoc-in db [:namespaces :loading?] true)
        :http-xhrio (fx/dashboard-get "/namespaces" id-token
                                      [:fetch-namespaces-success]
                                      [:fetch-namespaces-failure])}))))

(rf/reg-event-db
 :fetch-namespaces-success
 (fn [db [_ result]]
   (let [items (:namespaces result)
         has-default? (some #(= "default" (:name %)) items)
         items (if has-default?
                 items
                 (into [{:id "default" :name "default" :created-at nil}] items))]
     (-> db
         (assoc-in [:namespaces :items] items)
         (assoc-in [:namespaces :loading?] false)))))

(rf/reg-event-db
 :fetch-namespaces-failure
 (fn [db [_ _error]]
   (assoc-in db [:namespaces :loading?] false)))

(rf/reg-fx
 :persist-namespace
 (fn [ns-name]
   (try
     (.setItem js/localStorage "memlayer-namespace" (or ns-name "default"))
     (catch :default _))))

(rf/reg-event-fx
 :set-active-namespace
 (fn [{:keys [db]} [_ ns-name]]
   {:db                (assoc db :active-namespace ns-name)
    :persist-namespace ns-name
    :dispatch-n        [[:fetch-memory-stats]
                        [:fetch-memories]
                        [:fetch-graph-data]
                        [:fetch-health]
                        [:fetch-consistency]]}))

;; ============================================================================
;; Token events
;; ============================================================================

(rf/reg-event-fx
 :tokens/fetch
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:db         (assoc-in db [:tokens :loading?] true)
        :http-xhrio (fx/dashboard-get "/tokens" id-token
                                      [:tokens/fetch-success]
                                      [:tokens/fetch-failure])}))))

(rf/reg-event-db
 :tokens/fetch-success
 (fn [db [_ result]]
   (-> db
       (assoc-in [:tokens :items] (:tokens result))
       (assoc-in [:tokens :loading?] false)
       (assoc-in [:tokens :error] nil))))

(rf/reg-event-db
 :tokens/fetch-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:tokens :loading?] false)
       (assoc-in [:tokens :error] error))))

(rf/reg-event-fx
 :tokens/create
 (fn [{:keys [db]} [_ token-name]]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio (fx/dashboard-post "/tokens" {:name token-name} id-token
                                       [:tokens/create-success]
                                       [:tokens/create-failure])}))))

(rf/reg-event-fx
 :tokens/create-success
 (fn [{:keys [db]} [_ result]]
   {:db (-> db
            (assoc-in [:tokens :new-token] result)
            (update-in [:tokens :items] (fn [items] (conj (or items []) result))))
    :dispatch [:auth/fetch-active-token]}))

(rf/reg-event-db
 :tokens/create-failure
 (fn [db [_ error]]
   (assoc-in db [:tokens :error] error)))

(rf/reg-event-fx
 :tokens/revoke
 (fn [{:keys [db]} [_ token-id]]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio (fx/dashboard-delete (str "/tokens/" token-id) id-token
                                         [:tokens/revoke-success token-id]
                                         [:tokens/revoke-failure])}))))

(rf/reg-event-db
 :tokens/revoke-success
 (fn [db [_ token-id _result]]
   (update-in db [:tokens :items]
              (fn [items]
                (mapv (fn [t]
                        (if (= (:id t) token-id)
                          (assoc t :revoked-at (.toISOString (js/Date.)))
                          t))
                      items)))))

(rf/reg-event-db
 :tokens/revoke-failure
 (fn [db [_ error]]
   (assoc-in db [:tokens :error] error)))

(rf/reg-event-db
 :tokens/dismiss-new
 (fn [db _]
   (assoc-in db [:tokens :new-token] nil)))

;; ============================================================================
;; Settings events
;; ============================================================================

(rf/reg-event-fx
 :settings/fetch
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:db         (assoc-in db [:settings :loading?] true)
        :http-xhrio (fx/dashboard-get "/settings" id-token
                                      [:settings/fetch-success]
                                      [:settings/fetch-failure])}))))

(rf/reg-event-db
 :settings/fetch-success
 (fn [db [_ result]]
   (-> db
       (assoc-in [:settings :data] result)
       (assoc-in [:settings :loading?] false)
       (assoc-in [:settings :error] nil))))

(rf/reg-event-db
 :settings/fetch-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:settings :loading?] false)
       (assoc-in [:settings :error] error))))

(rf/reg-event-fx
 :settings/save-keys
 (fn [{:keys [db]} [_ keys-map]]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio (fx/dashboard-post "/settings/keys" keys-map id-token
                                       [:settings/save-keys-success]
                                       [:settings/save-keys-failure])}))))

(rf/reg-event-fx
 :settings/save-keys-success
 (fn [_ _]
   {:dispatch [:settings/fetch]}))

(rf/reg-event-db
 :settings/save-keys-failure
 (fn [db [_ error]]
   (assoc-in db [:settings :error] error)))

(rf/reg-event-fx
 :settings/delete-keys
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio (fx/dashboard-delete "/settings/keys" id-token
                                         [:settings/delete-keys-success]
                                         [:settings/delete-keys-failure])}))))

(rf/reg-event-fx
 :settings/delete-keys-success
 (fn [_ _]
   {:dispatch [:settings/fetch]}))

(rf/reg-event-db
 :settings/delete-keys-failure
 (fn [db [_ error]]
   (assoc-in db [:settings :error] error)))

(rf/reg-event-db
 :settings/set-tab
 (fn [db [_ tab]]
   (assoc-in db [:settings :active-tab] tab)))

(rf/reg-event-db
 :settings/set-key-field
 (fn [db [_ field value]]
   (assoc-in db [:settings :keys-form field] value)))

;; ============================================================================
;; Usage events
;; ============================================================================

(rf/reg-event-fx
 :usage/fetch
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])
         range-val (get-in db [:usage :range] "30d")]
     (when id-token
       {:db         (assoc-in db [:usage :loading?] true)
        :http-xhrio (fx/dashboard-get (str "/usage?range=" range-val) id-token
                                      [:usage/fetch-success]
                                      [:usage/fetch-failure])}))))

(rf/reg-event-db
 :usage/fetch-success
 (fn [db [_ result]]
   (-> db
       (assoc-in [:usage :data] result)
       (assoc-in [:usage :loading?] false)
       (assoc-in [:usage :error] nil))))

(rf/reg-event-db
 :usage/fetch-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:usage :loading?] false)
       (assoc-in [:usage :error] error))))

(rf/reg-event-fx
 :usage/set-range
 (fn [{:keys [db]} [_ range-val]]
   {:db       (assoc-in db [:usage :range] range-val)
    :dispatch [:usage/fetch]}))

;; ============================================================================
;; Namespace CRUD events (extending existing)
;; ============================================================================

(rf/reg-event-db
 :namespaces/open-create-modal
 (fn [db _]
   (-> db
       (assoc-in [:namespaces :create-modal :open?] true)
       (assoc-in [:namespaces :create-modal :name] ""))))

(rf/reg-event-db
 :namespaces/close-create-modal
 (fn [db _]
   (assoc-in db [:namespaces :create-modal :open?] false)))

(rf/reg-event-db
 :namespaces/set-create-name
 (fn [db [_ name]]
   (assoc-in db [:namespaces :create-modal :name] name)))

(rf/reg-event-fx
 :namespaces/create
 (fn [{:keys [db]} [_ ns-name]]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio (fx/dashboard-post "/namespaces" {:name ns-name} id-token
                                       [:namespaces/create-success]
                                       [:namespaces/create-failure])}))))

(rf/reg-event-fx
 :namespaces/create-success
 (fn [{:keys [db]} [_ result]]
   {:db (-> db
            (update-in [:namespaces :items] conj (:namespace result))
            (assoc-in [:namespaces :create-modal :open?] false))}))

(rf/reg-event-db
 :namespaces/create-failure
 (fn [db [_ error]]
   (assoc-in db [:namespaces :error] error)))

(rf/reg-event-db
 :namespaces/start-rename
 (fn [db [_ ns-info]]
   (assoc-in db [:namespaces :rename-target] ns-info)))

(rf/reg-event-db
 :namespaces/cancel-rename
 (fn [db _]
   (assoc-in db [:namespaces :rename-target] nil)))

(rf/reg-event-fx
 :namespaces/rename
 (fn [{:keys [db]} [_ ns-id new-name]]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:db (assoc-in db [:namespaces :rename-target] nil)
        :http-xhrio (fx/dashboard-put (str "/namespaces/" ns-id) {:name new-name} id-token
                                      [:namespaces/rename-success]
                                      [:namespaces/rename-failure])}))))

(rf/reg-event-fx
 :namespaces/rename-success
 (fn [_ _]
   {:dispatch [:fetch-namespaces]}))

(rf/reg-event-db
 :namespaces/rename-failure
 (fn [db [_ error]]
   (assoc-in db [:namespaces :error] error)))

(rf/reg-event-db
 :namespaces/set-delete-target
 (fn [db [_ ns-info]]
   (assoc-in db [:namespaces :delete-target] ns-info)))

(rf/reg-event-db
 :namespaces/clear-delete-target
 (fn [db _]
   (assoc-in db [:namespaces :delete-target] nil)))

(rf/reg-event-fx
 :namespaces/delete
 (fn [{:keys [db]} [_ ns-id]]
   (let [id-token (get-in db [:auth :id-token])]
     (when (and id-token ns-id)
       {:db         (assoc-in db [:namespaces :delete-target] nil)
        :http-xhrio (fx/dashboard-delete (str "/namespaces/" ns-id) id-token
                                         [:namespaces/delete-success]
                                         [:namespaces/delete-failure])}))))

(rf/reg-event-fx
 :namespaces/delete-success
 (fn [_ _]
   {:dispatch [:fetch-namespaces]}))

(rf/reg-event-db
 :namespaces/delete-failure
 (fn [db [_ error]]
   (assoc-in db [:namespaces :error] error)))

;; ============================================================================
;; Pipeline events
;; ============================================================================

(defonce ^:private pipeline-poll-interval (atom nil))

(rf/reg-fx
 :pipeline/start-poll-timer
 (fn [interval-ms]
   (when-let [old @pipeline-poll-interval] (js/clearInterval old))
   (reset! pipeline-poll-interval
           (js/setInterval #(rf/dispatch [:pipeline/fetch-graph]) interval-ms))))

(rf/reg-fx
 :pipeline/stop-poll-timer
 (fn [_]
   (when-let [h @pipeline-poll-interval]
     (js/clearInterval h)
     (reset! pipeline-poll-interval nil))))

(rf/reg-event-fx
 :pipeline/start-polling
 (fn [_ _]
   {:dispatch                  [:pipeline/fetch-graph]
    :pipeline/start-poll-timer 2500}))

(rf/reg-event-fx
 :pipeline/stop-polling
 (fn [_ _]
   {:pipeline/stop-poll-timer nil}))

(rf/reg-event-fx
 :pipeline/fetch-graph
 (fn [{:keys [db]} _]
   (let [api-key (get-in db [:auth :active-api-key])]
     (when api-key
       {:db         (assoc-in db [:pipeline :loading?] true)
        :http-xhrio (fx/api-get "/pipeline/graph" api-key
                                [:pipeline/fetch-graph-success]
                                [:pipeline/fetch-graph-failure])}))))

(rf/reg-event-db
 :pipeline/fetch-graph-success
 (fn [db [_ result]]
   (let [prev-counts (get-in db [:pipeline :prev-counts])
         new-counts  (into {} (map (fn [p] [(:pid p) (:count p)]))
                           (:processes result))
         deltas      (when prev-counts
                       (into {} (map (fn [[pid cnt]]
                                       [pid (- cnt (get prev-counts pid 0))]))
                             new-counts))]
     (-> db
         (assoc-in [:pipeline :data] result)
         (assoc-in [:pipeline :prev-counts] new-counts)
         (assoc-in [:pipeline :deltas] deltas)
         (assoc-in [:pipeline :loading?] false)
         (assoc-in [:pipeline :error] nil)))))

(rf/reg-event-db
 :pipeline/fetch-graph-failure
 (fn [db [_ error]]
   (-> db
       (assoc-in [:pipeline :loading?] false)
       (assoc-in [:pipeline :error] error))))

;; -- Pipeline operations --

(rf/reg-event-fx
 :pipeline/fetch-operations
 (fn [{:keys [db]} _]
   (let [api-key (get-in db [:auth :active-api-key])]
     (when api-key
       {:db         (assoc-in db [:pipeline :ops-loading?] true)
        :http-xhrio (fx/api-get "/pipeline/operations" api-key
                                [:pipeline/fetch-operations-success]
                                [:pipeline/fetch-operations-failure])}))))

(rf/reg-event-db
 :pipeline/fetch-operations-success
 (fn [db [_ result]]
   (let [ops        (:operations result)
         pending-id (get-in db [:pipeline :pending-op-id])
         pending-op (when pending-id
                      (some #(when (= (:id %) pending-id) %) ops))]
     (cond-> (-> db
                 (assoc-in [:pipeline :operations] ops)
                 (assoc-in [:pipeline :ops-loading?] false))
       pending-op
       (-> (assoc-in [:pipeline :selected-op] pending-op)
           (assoc-in [:pipeline :pending-op-id] nil))))))

(rf/reg-event-db
 :pipeline/fetch-operations-failure
 (fn [db [_ _error]]
   (assoc-in db [:pipeline :ops-loading?] false)))

(rf/reg-event-db
 :pipeline/select-operation
 (fn [db [_ op]]
   (assoc-in db [:pipeline :selected-op] op)))

(rf/reg-event-db
 :pipeline/clear-selection
 (fn [db _]
   (assoc-in db [:pipeline :selected-op] nil)))

(rf/reg-event-fx
 :pipeline/navigate-to-operation
 (fn [{:keys [db]} [_ operation-id]]
   (let [ops (get-in db [:pipeline :operations])
         op  (some #(when (= (:id %) operation-id) %) ops)]
     (cond-> {:db        (-> db
                             (assoc-in [:pipeline :selected-op] op)
                             (assoc-in [:pipeline :pending-op-id]
                                       (when-not op operation-id)))
              :navigate! :pipeline}
       ;; If op not in cache, fetch operations to find it
       (nil? op)
       (assoc :dispatch [:pipeline/fetch-operations])))))
