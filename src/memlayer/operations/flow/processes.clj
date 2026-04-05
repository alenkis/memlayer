(ns memlayer.operations.flow.processes
  "Flow process definitions for the unified retention pipeline.

   Each function returns a flow/process launcher. Processes carry their
   deps in closures (not in state) so flow/ping can serialize
   process state without choking on Java objects."
  (:require [clojure.core.async.flow :as flow]
            [memlayer.operations.pipeline :as pipeline]
            [memlayer.operations.flow.correlation :as corr]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.usage :as usage]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.llm.completion :as completion]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(defn- truncate [s n]
  (if (and (string? s) (> (count s) n))
    (str (subs s 0 n) "\u2026")
    s))

(defn- push-op
  "Prepend `op` to `:recent-ops` in state, keeping at most 10."
  [state op]
  (update state :recent-ops
          (fn [ops] (vec (take 10 (cons op (or ops [])))))))

;; ---------------------------------------------------------------------------
;; :prepare-context
;;
;; Builds context from recent memories for LLM extraction.
;; ---------------------------------------------------------------------------

(defn prepare-context-proc
  "Factory: closes over deps so they stay out of process state."
  [{:keys [db tuning]}]
  (flow/process
   (fn
     ([] {:ins {:in nil} :outs {:out nil} :workload :io})
     ([_args] {})
     ([state _transition] state)
     ([state _in-id msg]
      (let [{:keys [correlation-id items namespace source]} msg
            context-limit (or (:retain-context-limit tuning) 10)
            {:keys [text memories]} (pipeline/prepare-context db namespace context-limit)]
        [(push-op state
                  {:correlation-id   (str correlation-id)
                   :item-count       (count items)
                   :items            (mapv (fn [item]
                                             {:content (truncate (:content item) 200)
                                              :source  (:source item)})
                                           items)
                   :namespace        namespace
                   :source           source
                   :context-memories (mapv (fn [m] {:content (truncate (:content m) 200)})
                                           memories)
                   :context-count    (count memories)
                   :context-limit    context-limit})
         {:out [{:correlation-id correlation-id
                 :items          items
                 :namespace      namespace
                 :source         (or source "api")
                 :context        text}]}])))))

;; ---------------------------------------------------------------------------
;; :batch-extract
;;
;; Single LLM call to extract structured memories from items.
;; Fans out: emits one message per extracted memory.
;; ---------------------------------------------------------------------------

(defn batch-extract-proc
  "Factory: closes over deps so they stay out of process state."
  [{:keys [db chat-provider prompts correlation-map]}]
  (flow/process
   (fn
     ([] {:ins {:in nil} :outs {:out nil} :workload :io})
     ([_args] {})
     ([state _transition] state)
     ([state _in-id msg]
      (let [{:keys [correlation-id items namespace source context]} msg
            {:keys [result usage]}
            (completion/extract-memories-batch chat-provider prompts items context)
            _  (when usage
                 (usage/record-from-provider-safe! db
                                                   {:operation "retain" :step "extraction" :namespace namespace}
                                                   usage))
            extracted (vec result)
            total     (count extracted)
            new-state (push-op state
                               {:correlation-id (str correlation-id)
                                :input-items    (count items)
                                :items          (mapv (fn [item]
                                                        {:content (truncate (:content item) 200)
                                                         :source  (:source item)})
                                                      items)
                                :extracted      total
                                :memories       (mapv (fn [mem]
                                                        {:content    (truncate (:content mem) 200)
                                                         :layer      (:layer mem)})
                                                      extracted)
                                :layers         (frequencies (map :layer extracted))
                                :tokens         usage})]
        (log/info "Extracted" total "memories from" (count items) "items")
        (if (zero? total)
          (do (corr/resolve! correlation-map correlation-id
                             {:memory-ids [] :decisions []})
              [new-state {}])
          [new-state {:out (mapv (fn [mem]
                                   {:correlation-id   correlation-id
                                    :mem              mem
                                    :namespace        namespace
                                    :source           source
                                    :extraction-usage usage
                                    :batch-meta       {:total total}})
                                 extracted)}]))))))

;; ---------------------------------------------------------------------------
;; :collect-embeds
;;
;; Accumulates individual messages into batches for efficient embedding.
;; Runs on :io workload. Flush signals are injected into :in port as
;; sentinel messages with {:type :flush}.
;; ---------------------------------------------------------------------------

(defn collect-embeds-proc
  "Factory: accumulates messages and emits batch messages for embed-and-dedup.
   Closes over max-batch-size from flow config.
   Flush signals ({:type :flush}) are injected via flow/inject to the :in port."
  [{:keys [max-batch-size]}]
  (let [max-batch-size (or max-batch-size 64)]
    (flow/process
     (fn
       ([] {:ins {:in nil} :outs {:out nil} :workload :io})
       ([_args] {:buffer []})
       ([state _transition] state)
       ([state _in-id msg]
        (if (= :flush (:type msg))
          ;; Flush signal: emit whatever's buffered
          (let [buf (:buffer state)]
            (if (seq buf)
              (do (log/debug "Flush: emitting batch of" (count buf) "items")
                  [(push-op (assoc state :buffer [])
                            {:op :flush :batch-size (count buf)})
                   {:out [{:batch-items buf}]}])
              [state {}]))
          ;; Regular message: buffer it
          (let [buf (conj (:buffer state) msg)]
            (if (>= (count buf) max-batch-size)
              ;; Buffer full: emit batch
              (do (log/debug "Buffer full: emitting batch of" (count buf) "items")
                  [(push-op (assoc state :buffer [])
                            {:op :full-batch :batch-size (count buf)})
                   {:out [{:batch-items buf}]}])
              ;; Still accumulating
              [(assoc state :buffer buf) {}]))))))))

;; ---------------------------------------------------------------------------
;; :embed-and-dedup
;;
;; Embeds memories and searches for duplicate candidates.
;; Handles both batch messages (from :collect-embeds) and single messages
;; (fallback). Runs on :io workload for concurrent HTTP calls.
;; ---------------------------------------------------------------------------

(defn- split-usage-proportional
  "Split batch embedding usage tokens proportional to text lengths."
  [batch-usage texts]
  (let [lengths   (mapv count texts)
        total-len (reduce + 0 lengths)
        n         (count texts)
        total-pt  (:prompt-tokens batch-usage 0)
        total-tt  (:total-tokens batch-usage 0)
        model     (:model batch-usage)
        provider  (:provider batch-usage)]
    (mapv (fn [len]
            (let [frac (if (pos? total-len)
                         (/ (double len) total-len)
                         (/ 1.0 n))]
              {:prompt-tokens     (long (Math/round (* total-pt frac)))
               :completion-tokens 0
               :total-tokens      (long (Math/round (* total-tt frac)))
               :model             model
               :provider          provider}))
          lengths)))

(defn- dedup-single
  "Embed a single memory, search for candidates, filter by namespace.
   Returns [embedding usage filtered-candidates]."
  [embedding-provider vector-index db candidate-limit content retain-ns]
  (let [{:keys [embedding usage]} (llm-provider/embed embedding-provider content)
        _ (when usage
            (usage/record-from-provider-safe! db
                                              {:operation "retain" :step "dedup-embed"
                                               :namespace retain-ns}
                                              usage))
        candidates (when @vector-index
                     (try
                       (let [search-results (protocols/search @vector-index embedding candidate-limit)
                             result-ids (mapv (fn [{:keys [id]}] (UUID/fromString id)) search-results)
                             result-mems (when (seq result-ids)
                                           (dh/get-memories-batch-full db result-ids))
                             mems-by-id (into {} (map (fn [m] [(:memory/id m) m]))
                                              (or result-mems []))]
                         (mapv (fn [{:keys [id distance]}]
                                 (let [mem-id (UUID/fromString id)
                                       db-mem (get mems-by-id mem-id)]
                                   (when db-mem
                                     {:memory-id  mem-id
                                      :content    (:memory/content db-mem)
                                      :namespace  (:memory/namespace db-mem)
                                      :distance   distance})))
                               search-results))
                       (catch Exception e
                         (log/warn "Vector search failed:" (.getMessage e))
                         [])))
        filtered (->> (or candidates [])
                      (filterv (fn [c]
                                 (and (some? c)
                                      (= (:namespace c) retain-ns)))))]
    [embedding usage filtered]))

(defn- dedup-search
  "Search for candidates given a pre-computed embedding. Returns filtered candidates."
  [vector-index db candidate-limit embedding retain-ns]
  (let [candidates (when @vector-index
                     (try
                       (let [search-results (protocols/search @vector-index embedding candidate-limit)
                             result-ids (mapv (fn [{:keys [id]}] (UUID/fromString id)) search-results)
                             result-mems (when (seq result-ids)
                                           (dh/get-memories-batch-full db result-ids))
                             mems-by-id (into {} (map (fn [m] [(:memory/id m) m]))
                                              (or result-mems []))]
                         (mapv (fn [{:keys [id distance]}]
                                 (let [mem-id (UUID/fromString id)
                                       db-mem (get mems-by-id mem-id)]
                                   (when db-mem
                                     {:memory-id  mem-id
                                      :content    (:memory/content db-mem)
                                      :namespace  (:memory/namespace db-mem)
                                      :distance   distance})))
                               search-results))
                       (catch Exception e
                         (log/warn "Vector search failed:" (.getMessage e))
                         [])))]
    (->> (or candidates [])
         (filterv (fn [c]
                    (and (some? c)
                         (= (:namespace c) retain-ns)))))))

(defn- process-batch-embed
  "Process a batch of messages: embed all texts in one call, then dedup each."
  [embedding-provider vector-index db candidate-limit items]
  (let [texts      (mapv #(get-in % [:mem :content]) items)
        {:keys [embeddings usage]} (llm-provider/embed-batch embedding-provider texts)
        per-usages (split-usage-proportional usage texts)]
    (log/info "Batch embedded" (count texts) "texts in single call")
    (mapv (fn [msg embedding item-usage]
            (let [retain-ns (:namespace msg)
                  _ (when item-usage
                      (usage/record-from-provider-safe! db
                                                        {:operation "retain" :step "dedup-embed"
                                                         :namespace retain-ns}
                                                        item-usage))
                  filtered (dedup-search vector-index db candidate-limit embedding retain-ns)]
              {:msg       (assoc msg
                                 :mem (assoc (:mem msg)
                                             :embedding embedding
                                             :candidates filtered)
                                 :embed-usage item-usage)
               :usage     item-usage
               :filtered  filtered}))
          items embeddings per-usages)))

(defn embed-and-dedup-proc
  "Factory: closes over deps so they stay out of process state.
   Handles both batch messages {:batch-items [...]} from collect-embeds
   and single messages (fallback)."
  [{:keys [embedding-provider vector-index db tuning]}]
  (flow/process
   (fn
     ([] {:ins {:in nil} :outs {:out nil} :workload :io})
     ([_args] {})
     ([state _transition] state)
     ([state _in-id msg]
      (if-let [batch-items (:batch-items msg)]
        ;; Batch path: embed all texts in one HTTP call, then dedup each
        (let [candidate-limit (or (:retain-candidate-limit tuning) 5)
              results (process-batch-embed embedding-provider vector-index db
                                           candidate-limit batch-items)
              out-msgs (mapv :msg results)
              new-state (push-op state
                                 {:op             :batch-embed
                                  :batch-size     (count batch-items)
                                  :items          (mapv (fn [{:keys [msg]}]
                                                          {:correlation-id (str (:correlation-id msg))
                                                           :content        (truncate (get-in msg [:mem :content]) 200)})
                                                        results)})]
          [new-state {:out out-msgs}])
        ;; Single message fallback
        (let [candidate-limit (or (:retain-candidate-limit tuning) 5)
              content         (get-in msg [:mem :content])
              retain-ns       (:namespace msg)
              [embedding usage filtered-candidates]
              (dedup-single embedding-provider vector-index db
                            candidate-limit content retain-ns)]
          [(push-op state
                    {:correlation-id    (str (:correlation-id msg))
                     :content           (truncate content 200)
                     :layer             (get-in msg [:mem :layer])
                     :candidate-count   (count filtered-candidates)
                     :candidates        (mapv (fn [c]
                                                {:distance (double (:distance c))
                                                 :content  (truncate (:content c) 200)})
                                              filtered-candidates)
                     :vector-index?     (some? @vector-index)
                     :tokens            usage})
           {:out [(assoc msg
                         :mem (assoc (:mem msg)
                                     :embedding embedding
                                     :candidates filtered-candidates)
                         :embed-usage usage)]}]))))))

;; ---------------------------------------------------------------------------
;; :decide
;;
;; LLM decides CREATE/UPDATE/DELETE/NOOP for each memory.
;; Runs on :io workload — concurrent.
;; ---------------------------------------------------------------------------

(defn decide-proc
  "Factory: closes over deps so they stay out of process state."
  [{:keys [db chat-provider prompts]}]
  (flow/process
   (fn
     ([] {:ins {:in nil} :outs {:out nil} :workload :io})
     ([_args] {})
     ([state _transition] state)
     ([state _in-id msg]
      (let [mem        (:mem msg)
            candidates (:candidates mem)
            candidate-ids (mapv :memory-id candidates)
            subgraph   (when (seq candidate-ids)
                         {:edges       (dh/get-relationships db candidate-ids)
                          :known-types (dh/get-distinct-relationship-types db)})
            {:keys [result usage]} (completion/decide-action chat-provider prompts
                                                             mem candidates
                                                             :subgraph subgraph)
            _  (log/info "Decision result" {:action (:action result)
                                            :content (truncate (:content mem) 60)
                                            :candidate-count (count candidates)
                                            :relationship-count (count (:relationships result))
                                            :relationships (:relationships result)})
            _  (when usage
                 (usage/record-from-provider-safe! db
                                                   {:operation "retain" :step "decision"
                                                    :namespace (:namespace msg)}
                                                   usage))]
        [(push-op state
                  (cond->
                   {:correlation-id  (str (:correlation-id msg))
                    :action          (:action result)
                    :reasoning       (:reasoning result)
                    :candidates-seen (count candidates)
                    :content         (truncate (:content mem) 200)
                    :layer           (:layer mem)
                    :candidates      (mapv (fn [c]
                                             {:content  (truncate (:content c) 200)
                                              :distance (some-> (:distance c) double)})
                                           candidates)
                    :tokens          usage}
                    ;; For UPDATE/DELETE: include the target and merged content
                    (:merged-content result)
                    (assoc :merged-content (truncate (:merged-content result) 200))
                    (= "UPDATE" (:action result))
                    (assoc :target (when-let [t (first candidates)]
                                     {:content  (truncate (:content t) 200)
                                      :distance (:distance t)}))))
         {:out [(assoc msg
                       :mem (assoc mem :decision result)
                       :decision-usage usage)]}])))))

;; ---------------------------------------------------------------------------
;; :execute
;;
;; Sequential DB writes. Accumulates results per correlation-id.
;; When all memories for a batch are received, resolves the promise-chan.
;; ---------------------------------------------------------------------------

(defn execute-proc
  "Factory: closes over deps so they stay out of process state."
  [{:keys [db vector-index correlation-map cost-config]}]
  (flow/process
   (fn
     ([] {:ins {:in nil} :outs {:out nil} :workload :io})
     ([_args] {:batches {}})
     ([state _transition] state)
     ([state _in-id msg]
      (let [{:keys [correlation-id mem namespace source batch-meta
                    extraction-usage embed-usage decision-usage]} msg
            total    (:total batch-meta)
            result   (try
                       (pipeline/execute-decision! db vector-index source namespace mem)
                       (catch Exception e
                         (log/error "Decision execution failed"
                                    {:action (get-in mem [:decision :action])
                                     :error  (.getMessage e)})
                         nil))
            ;; Update batch accumulator
            batch    (get-in state [:batches correlation-id]
                             {:expected total :received 0 :results [] :usage-acc pipeline/zero-usage})
            batch    (-> batch
                         (update :received inc)
                         (update :results conj result)
                         (update :usage-acc pipeline/merge-usage
                                 (reduce pipeline/merge-usage pipeline/zero-usage
                                         (filter some? [extraction-usage embed-usage decision-usage]))))
            state    (assoc-in state [:batches correlation-id] batch)]
        (if (>= (:received batch) (:expected batch))
          ;; Batch complete — resolve the promise
          (let [results     (filterv some? (:results batch))
                total-usage (:usage-acc batch)
                final       {:memory-ids     (filterv some? (mapv :memory-id results))
                             :decisions      results
                             :usage          (cond-> total-usage
                                               cost-config
                                               (assoc :estimated-cost
                                                      (pipeline/estimate-cost total-usage cost-config)))}
                creates (count (filter #(= "CREATE" (:type %)) results))
                updates (count (filter #(= "UPDATE" (:type %)) results))
                noops   (count (filter #(= "NOOP" (:type %)) results))
                forgets (count (filter #(= "FORGET" (:type %)) results))]
            (log/info "Batch complete for" correlation-id
                      {:creates creates :updates updates :noops noops})
            (corr/resolve! correlation-map correlation-id final)
            [(-> state
                 (update :batches dissoc correlation-id)
                 (push-op {:correlation-id (str correlation-id)
                           :creates        creates
                           :updates        updates
                           :noops          noops
                           :forgets        forgets
                           :memory-count   (count (filterv some? (mapv :memory-id results)))
                           :results        (mapv (fn [r]
                                                   {:type      (:type r)
                                                    :memory-id (some-> (:memory-id r) str)
                                                    :content   (truncate (:content r) 200)})
                                                 results)
                           :cost           (get-in final [:usage :estimated-cost])}))
             {}])
          ;; Still waiting for more
          [state {}]))))))
