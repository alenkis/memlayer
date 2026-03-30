(ns memlayer.operations.pipeline
  "Shared utilities for retain and batch-retain pipelines."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [clojure.tools.logging :as log]
            [clojure.string :as str])
  (:import [java.util UUID]))

(def zero-usage {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0})

(defn merge-usage [u1 u2]
  (let [base {:prompt-tokens     (+ (:prompt-tokens u1 0) (:prompt-tokens u2 0))
              :completion-tokens (+ (:completion-tokens u1 0) (:completion-tokens u2 0))
              :total-tokens      (+ (:total-tokens u1 0) (:total-tokens u2 0))}
        e1   (:embedding u1)
        e2   (:embedding u2)]
    (if (or e1 e2)
      (assoc base :embedding
             {:total-tokens (+ (get e1 :total-tokens 0)
                               (get e2 :total-tokens 0))})
      base)))

(defn estimate-cost
  "Estimate USD cost from token usage and provider pricing config.
   Returns {:embedding-cost :chat-cost :total-cost :currency}."
  [usage cost-config]
  (let [embed-tokens  (get-in usage [:embedding :total-tokens] 0)
        prompt-tokens (:prompt-tokens usage 0)
        compl-tokens  (:completion-tokens usage 0)
        embed-cost    (* (/ embed-tokens 1000.0)
                         (:embedding-per-1k-tokens cost-config 0.0))
        prompt-cost   (* (/ prompt-tokens 1000.0)
                         (:chat-prompt-per-1k-tokens cost-config 0.0))
        compl-cost    (* (/ compl-tokens 1000.0)
                         (:chat-completion-per-1k-tokens cost-config 0.0))
        chat-cost     (+ prompt-cost compl-cost)]
    {:embedding-cost embed-cost
     :chat-cost      chat-cost
     :total-cost     (+ embed-cost chat-cost)
     :currency       "USD"}))

(defn prepare-context
  "Build context from recent memories for LLM extraction.
   Returns {:text \"...\" :memories [{:content \"...\"}]}."
  [db-conn namespace context-limit]
  (let [recent (if namespace
                 (dh/get-memories-by-namespace db-conn namespace :limit context-limit)
                 (dh/get-recent-memories db-conn :limit context-limit))
        contents (mapv :memory/content recent)]
    {:text     (if (seq contents)
                 (->> contents
                      (str/join "\n- ")
                      (str "Recent memories:\n- "))
                 "")
     :memories (mapv (fn [m] {:content (:memory/content m)}) recent)}))

(def layer-str->keyword
  {"domain"  :layer/domain
   "concept" :layer/concept
   "fact"    :layer/fact
   "episode" :layer/episode})

(defn build-mem-attrs
  "Build memory attribute map from decision context. Pure function."
  [{:keys [content layer-kw source namespace display-title]}]
  (cond-> {:memory/content    content
           :memory/layer      (or layer-kw :layer/fact)
           :memory/source     source
           :memory/namespace  namespace}
    display-title (assoc :memory/display-title display-title)))

(defn- try-parse-uuid
  "Attempt to parse s as a UUID. Returns nil on failure."
  [s]
  (try (UUID/fromString (str s))
       (catch Exception _ nil)))

(defn- compensate-failed-create!
  "Attempt to roll back a memory insert after vector store failure."
  [db-conn mem-id]
  (try
    (dh/retract-memory! db-conn mem-id)
    (catch Exception ce
      (log/error "Compensation delete also failed"
                 {:memory-id mem-id :error (.getMessage ce)}))))

(defn store-embedding!
  "Store an embedding in the vector index, with compensation on failure for CREATE."
  [vector-index-atom mem-id embedding {:keys [db-conn compensate?]}]
  (try
    (swap! vector-index-atom
           (fn [store] (protocols/upsert! store (str mem-id) embedding)))
    (catch Exception e
      (log/error "Vector store failed" {:memory-id mem-id :error (.getMessage e)})
      (when compensate?
        (compensate-failed-create! db-conn mem-id))
      (throw e))))

(defn update-embedding!
  "Replace an embedding in the vector index."
  [vector-index-atom target-id embedding]
  (try
    (swap! vector-index-atom
           (fn [store]
             (-> store
                 (protocols/remove! (str target-id))
                 (protocols/upsert! (str target-id) embedding))))
    (catch Exception e
      (log/error "Vector update failed" {:target-id target-id :error (.getMessage e)})
      (throw e))))

(defn- create-inferred-relationships!
  "Create relationships inferred by the LLM decision.
   Validates target_id exists in the candidates set before inserting."
  [db-conn source-id candidates relationships]
  (log/info "Inferred relationships from LLM:" {:count (count relationships)
                                                :relationships relationships
                                                :candidate-count (count candidates)})
  (let [candidate-ids (into #{} (map :memory-id) candidates)]
    (doseq [rel relationships
            :let [target-id (some-> (:target-id rel) try-parse-uuid)
                  in-candidates? (and target-id (contains? candidate-ids target-id))]]
      (if in-candidates?
        (let [result (dh/insert-relationship!
                      db-conn
                      {:source-id source-id
                       :target-id target-id
                       :type      (keyword (:type rel))})]
          (log/info "Relationship insert result:" {:type (:type rel) :target-id target-id :result result}))
        (log/warn "Skipping relationship — target not in candidates:"
                  {:target-id (:target-id rel) :parsed target-id
                   :candidate-ids candidate-ids})))))

(defn execute-decision!
  "Execute a single decision: CREATE, UPDATE, DELETE, or NOOP.
   Includes compensation logic to maintain datahike/proximum consistency.
   Returns {:type :memory-id :content} or nil for NOOP.

   NOTE: The current two-phase approach (datahike tx first, then vector store)
   with compensation was inherited from the original xtdb+qdrant architecture
   where the stores were genuinely separate systems. With datahike+proximum
   both being embedded/in-process, there may be a simpler consistency model
   available — e.g. a single atomic operation that commits both, or leveraging
   proximum's immutable index semantics. Worth rethinking this whole approach
   rather than patching the compensation logic."
  [db-conn vector-index-atom source namespace mem]
  (let [{:keys [action merged-content relationships]} (:decision mem)
        content       (or merged-content (:content mem))
        display-title (:display-title mem)
        layer-kw      (layer-str->keyword (:layer mem))
        candidates    (:candidates mem)]
    (log/info "execute-decision!" {:action action
                                   :content (subs content 0 (min 50 (count content)))
                                   :candidate-count (count candidates)
                                   :relationship-count (count relationships)
                                   :relationships relationships})
    (let [attrs (build-mem-attrs {:content       content
                                  :layer-kw      layer-kw
                                  :source        source
                                  :namespace     namespace
                                  :display-title display-title})]
      (case action
        "CREATE"
        (let [mem-id (dh/insert-memory! db-conn attrs)]
          (when-let [embedding (:embedding mem)]
            (store-embedding! vector-index-atom mem-id embedding
                              {:db-conn db-conn :compensate? true}))
          (when (seq relationships)
            (create-inferred-relationships! db-conn mem-id candidates relationships))
          {:type "CREATE" :memory-id mem-id :content content})

        "UPDATE"
        (let [target-id (-> candidates first :memory-id)]
          (when target-id
            (dh/update-memory! db-conn target-id attrs)
            (when-let [embedding (:embedding mem)]
              (update-embedding! vector-index-atom target-id embedding))
            (when (seq relationships)
              (create-inferred-relationships! db-conn target-id candidates relationships))
            {:type "UPDATE" :memory-id target-id :content content}))

        ("FORGET" "DELETE")
        (let [target-id (or (some-> (:decision mem) :delete-target-id try-parse-uuid)
                            (-> candidates first :memory-id))]
          (when target-id
            (dh/forget-memory! db-conn target-id)
            (swap! vector-index-atom (fn [store] (protocols/remove! store (str target-id))))
            {:type "FORGET" :memory-id target-id :content content}))

        "NOOP"
        {:type "NOOP" :content (:content mem)}

        ;; Default: treat unknown as CREATE
        (do
          (log/warn "Unknown decision action:" action "- treating as CREATE")
          (execute-decision! db-conn vector-index-atom source namespace
                             (assoc-in mem [:decision :action] "CREATE")))))))
