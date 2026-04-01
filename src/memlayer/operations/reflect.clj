(ns memlayer.operations.reflect
  "Reflect operation: additive knowledge organization.
   Composed of 4 phases: organize, summarize, connect, curate.
   Facts are never modified — reflect only creates new higher-level structures."
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.usage :as usage]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.llm.completion :as completion]
            [memlayer.schema :as schema]
            [memlayer.tuning :as tuning])
  (:import [java.util UUID]))

(def ^:private default-batch-size tuning/reflect-default-batch-size)

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- embed-and-store!
  "Embed content and store in vector index. Records usage. Returns embedding."
  [db vector-index embedding-provider content id step namespace]
  (let [{:keys [embedding usage]} (llm-provider/embed embedding-provider content)]
    (when usage
      (usage/record-from-provider-safe! db {:operation "reflect" :step step :namespace namespace}
                                        usage))
    (swap! vector-index (fn [store] (protocols/upsert! store (str id) embedding)))
    embedding))

(defn- create-memory-node!
  "Create a memory node, embed it, store in vector index. Returns the id, or nil
   when content is blank (guards against LLM returning empty concept names)."
  [db vector-index embedding-provider {:keys [content layer namespace source step]}]
  (when (and content (seq (str/trim content)))
    (let [id (UUID/randomUUID)]
      (dh/insert-memory! db {:memory/id         id
                             :memory/content    content
                             :memory/layer      layer
                             :memory/source     (or source "reflect")
                             :memory/namespace  namespace})
      (embed-and-store! db vector-index embedding-provider content id
                        (or step "node-embed") namespace)
      id)))

;; ---------------------------------------------------------------------------
;; Phase 1: Organize — orphan facts → concepts, orphan concepts → domains
;; ---------------------------------------------------------------------------

(defn- link-facts-to-concept!
  "Link facts at given indices in batch to an existing concept id."
  [db batch fact-indices concept-id]
  (let [batch-vec (vec batch)]
    (count
     (keep (fn [fi]
             (when-let [fact (get batch-vec fi)]
               (dh/update-memory! db (:memory/id fact) {:memory/parent-id concept-id})))
           fact-indices))))

(defn- process-organize-batch
  "Process a batch of orphan facts: assign to existing or new concepts."
  [acc {:keys [db vector-index embedding-provider chat-provider prompts namespace
               existing-concepts]}
   batch]
  (try
    (let [{:keys [result usage]} (completion/organize-facts chat-provider prompts
                                                            (vec batch) existing-concepts)
          _ (when usage
              (usage/record-from-provider-safe! db {:operation "reflect" :step "organize"
                                                    :namespace namespace} usage))
          groups (or (:groups result) [])
          {:keys [concepts-created concepts-reused details new-concepts]}
          (reduce
           (fn [gacc group]
             (let [concept-id-str (:concept-id group)
                   fact-indices   (or (:fact-indices group) [])
                   existing?      (and concept-id-str (seq concept-id-str))]
               (if existing?
                 ;; Link to existing concept
                 (let [cid (UUID/fromString concept-id-str)]
                   (link-facts-to-concept! db batch fact-indices cid)
                   (update gacc :concepts-reused inc))
                 ;; Create new concept
                 (let [content (or (:concept-content group) (:concept-name group))
                       cid     (create-memory-node! db vector-index embedding-provider
                                                    {:content   content
                                                     :layer     :layer/concept
                                                     :namespace namespace
                                                     :step      "concept-embed"})]
                   (if cid
                     (do (link-facts-to-concept! db batch fact-indices cid)
                         (-> gacc
                             (update :concepts-created inc)
                             (update :details conj {:concept-id cid :content content
                                                    :children (count fact-indices)})
                             (update :new-concepts conj (dh/get-memory db cid))))
                     (do (log/warn "Skipping concept with blank content from LLM")
                         gacc))))))
           {:concepts-created 0 :concepts-reused 0 :details [] :new-concepts []}
           groups)]
      (-> acc
          (update :concepts-created + concepts-created)
          (update :concepts-reused + concepts-reused)
          (update :details into details)
          (update :existing-concepts into new-concepts)))
    (catch Exception e
      (log/error "Reflect organize batch failed:" (.getMessage e))
      acc)))

(defn- organize-concepts-into-domains!
  "Group orphan concepts into existing or new domains."
  [{:keys [db vector-index embedding-provider chat-provider prompts namespace]}]
  (let [orphan-concepts (dh/get-orphan-concepts db :namespace namespace)]
    (if (< (count orphan-concepts) 2)
      {:domains-created 0}
      (try
        (let [existing-domains (dh/get-domains db :namespace namespace)
              {:keys [result usage]} (completion/organize-concepts chat-provider prompts
                                                                   orphan-concepts existing-domains)
              _ (when usage
                  (usage/record-from-provider-safe! db {:operation "reflect" :step "organize-domains"
                                                        :namespace namespace} usage))
              groups         (or (:groups result) [])
              concepts-vec   (vec orphan-concepts)
              domains-created
              (reduce
               (fn [cnt group]
                 (let [domain-id-str   (:domain-id group)
                       concept-indices (or (:concept-indices group) [])
                       existing?       (and domain-id-str (seq domain-id-str))]
                   (if existing?
                     (let [did (UUID/fromString domain-id-str)]
                       (doseq [ci concept-indices]
                         (when-let [c (get concepts-vec ci)]
                           (dh/update-memory! db (:memory/id c) {:memory/parent-id did})))
                       cnt)
                     (let [content (or (:domain-content group) (:domain-name group))
                           did     (create-memory-node! db vector-index embedding-provider
                                                        {:content    content
                                                         :layer      :layer/domain
                                                         :namespace  namespace
                                                         :step       "domain-embed"})]
                       (if did
                         (do (doseq [ci concept-indices]
                               (when-let [c (get concepts-vec ci)]
                                 (dh/update-memory! db (:memory/id c) {:memory/parent-id did})))
                             (inc cnt))
                         (do (log/warn "Skipping domain with blank content from LLM")
                             cnt))))))
               0
               groups)]
          {:domains-created domains-created})
        (catch Exception e
          (log/error "Reflect organize-domains failed:" (.getMessage e))
          {:domains-created 0})))))

(defn organize!
  "Phase 1: Group unorganized facts/episodes into concepts, then orphan concepts into domains.
   When `since` is provided, processes facts/episodes created after that time.
   Otherwise processes all orphan facts."
  [{:keys [db vector-index embedding-provider chat-provider prompts tuning]}
   {:keys [namespace since]}]
  (let [batch-size  (or (:reflect-batch-size tuning) default-batch-size)
        candidates  (if since
                      (->> (dh/get-memories-since db since :namespace namespace)
                           (filterv (fn [m]
                                      (and (#{:layer/fact :layer/episode} (:memory/layer m))
                                           (nil? (:memory/parent-id m))))))
                      (dh/get-orphan-facts db :namespace namespace))
        existing    (dh/get-concepts db :namespace namespace)]
    (if (empty? candidates)
      {:facts-processed 0 :concepts-created 0 :concepts-reused 0 :domains-created 0}
      (let [batches (partition-all batch-size candidates)
            ctx     {:db                 db
                     :vector-index       vector-index
                     :embedding-provider embedding-provider
                     :chat-provider      chat-provider
                     :prompts            prompts
                     :namespace          namespace
                     :existing-concepts  (vec existing)}
            result  (reduce #(process-organize-batch %1 ctx %2)
                            {:concepts-created 0 :concepts-reused 0
                             :details [] :existing-concepts (vec existing)}
                            batches)
            domain-result (organize-concepts-into-domains!
                           {:db db :vector-index vector-index
                            :embedding-provider embedding-provider
                            :chat-provider chat-provider :prompts prompts
                            :namespace namespace})]
        {:facts-processed  (count candidates)
         :concepts-created (:concepts-created result)
         :concepts-reused  (:concepts-reused result)
         :domains-created  (:domains-created domain-result)
         :details          (:details result)}))))

;; ---------------------------------------------------------------------------
;; Phase 2: Summarize — create summary nodes for concepts/domains
;; ---------------------------------------------------------------------------

(defn- needs-summary?
  "True if a memory node has no summary child yet."
  [db memory-id]
  (empty? (dh/get-summaries-for db memory-id)))

(defn- summarize-node!
  "Generate and store a summary for a single concept/domain node.
   Returns summary id or nil on failure."
  [{:keys [db vector-index embedding-provider chat-provider prompts]} node namespace]
  (let [children (dh/get-children db (:memory/id node))]
    (when (seq children)
      (try
        (let [{:keys [result usage]} (completion/summarize-memories chat-provider prompts
                                                                    node children)
              _ (when usage
                  (usage/record-from-provider-safe! db {:operation "reflect" :step "summarize"
                                                        :namespace namespace} usage))
              summary-text (:summary result)]
          (when (and summary-text (seq summary-text))
            (let [id (UUID/randomUUID)]
              (dh/insert-memory! db {:memory/id         id
                                     :memory/content    summary-text
                                     :memory/layer      :layer/summary
                                     :memory/source     "reflect"
                                     :memory/parent-id  (:memory/id node)
                                     :memory/namespace  namespace})
              (embed-and-store! db vector-index embedding-provider summary-text id
                                "summary-embed" namespace)
              id)))
        (catch Exception e
          (log/error "Summarize failed for" (:memory/id node)
                     (.getMessage e) (ex-data e))
          nil)))))

(defn summarize!
  "Phase 2: Create or refresh summaries for concepts/domains with recent changes.
   When `since` is provided, summarizes nodes that have children created after `since`
   or that still need a summary. Otherwise only summarizes unsummarized nodes."
  [{:keys [db] :as deps} {:keys [namespace since]}]
  (let [concepts (dh/get-concepts db :namespace namespace)
        domains  (dh/get-domains db :namespace namespace)
        all-nodes (into concepts domains)
        nodes-to-summarize
        (if since
          (filterv (fn [node]
                     (let [children (dh/get-children db (:memory/id node))
                           has-recent? (some (fn [c]
                                               (let [created (dh/get-memory-created-at db (:memory/id c))]
                                                 (and created (.after created since))))
                                             children)]
                       (or has-recent? (needs-summary? db (:memory/id node)))))
                   all-nodes)
          (filterv #(needs-summary? db (:memory/id %)) all-nodes))
        results (keep #(summarize-node! deps % namespace) nodes-to-summarize)]
    {:summaries-created (count results)}))

;; ---------------------------------------------------------------------------
;; Phase 3: Connect — discover cross-layer relationships
;; ---------------------------------------------------------------------------

(def ^:private connect-top-k tuning/reflect-connect-top-k)
(def ^:private connect-batch-size tuning/reflect-connect-batch-size)
(def ^:private connect-max-pairs tuning/reflect-connect-max-pairs)

(defn dedupe-pairs
  "Remove duplicate pairs from a seq of [mem-a mem-b] pairs.
   Two pairs are duplicates if they involve the same two memory IDs (order-independent)."
  [pairs]
  (->> pairs
       (reduce (fn [{:keys [seen result]} [a b]]
                 (let [k (vec (sort [(str (:memory/id a)) (str (:memory/id b))]))]
                   (if (contains? seen k)
                     {:seen seen :result result}
                     {:seen (conj seen k) :result (conj result [a b])})))
               {:seen #{} :result []})
       :result))

(defn- find-neighbor-pairs
  "For each dirty memory, find K nearest neighbors from the full vector index.
   Returns deduplicated pairs of [memory-a memory-b].
   Neighbors can be any layer — not restricted to same type."
  [db vector-index embedding-provider dirty-memories namespace]
  (->> dirty-memories
       (mapcat (fn [m]
                 (try
                   (let [{:keys [embedding]} (llm-provider/embed embedding-provider
                                                                 (:memory/content m))
                         results (protocols/search @vector-index embedding connect-top-k)
                         result-ids (into [] (keep (fn [{:keys [id]}]
                                                     (let [uid (UUID/fromString id)]
                                                       (when (not= uid (:memory/id m)) uid))))
                                          results)
                         result-mems (when (seq result-ids)
                                       (dh/get-memories-batch-full db result-ids))
                         mems-by-id (into {} (map (fn [mem] [(:memory/id mem) mem]))
                                          (or result-mems []))]
                     (->> result-ids
                          (keep (fn [uid]
                                  (let [other (get mems-by-id uid)]
                                    (when (and other
                                               (= (:memory/namespace other) namespace))
                                      [m other]))))
                          vec))
                   (catch Exception e
                     (log/warn "Vector search failed for" (:memory/id m) (.getMessage e))
                     []))))
       dedupe-pairs
       (take connect-max-pairs)
       vec))

(defn- process-connect-batch!
  "Send a batch of concept pairs to LLM and create discovered relationships."
  [{:keys [db chat-provider prompts]} pairs namespace]
  (try
    (let [{:keys [result usage]} (completion/discover-relationships chat-provider prompts pairs)
          _ (when usage
              (usage/record-from-provider-safe! db {:operation "reflect" :step "connect"
                                                    :namespace namespace} usage))
          relationships (or (:relationships result) [])
          pairs-vec     (vec pairs)]
      (count
       (keep (fn [rel]
               (let [idx  (:pair-index rel)
                     pair (get pairs-vec idx)]
                 (when pair
                   (let [[a b]       pair
                         rel-type    (keyword (or (:type rel) "related-to"))
                         confidence  (or (:confidence rel) 0.8)
                         description (:description rel)]
                     (dh/insert-relationship! db {:source-id   (:memory/id a)
                                                  :target-id   (:memory/id b)
                                                  :type        rel-type
                                                  :confidence  confidence
                                                  :description description})))))
             relationships)))
    (catch Exception e
      (log/error "Connect batch failed:" (.getMessage e) (ex-data e))
      0)))

(defn connect!
  "Phase 3: Discover relationships across all layers using vector similarity + LLM.
   Only processes memories created since `since` as the 'dirty set'.
   Neighbors from the full index (any layer, any age) are eligible."
  [{:keys [db vector-index embedding-provider] :as deps}
   {:keys [namespace since]}]
  (let [dirty (if since
                (dh/get-memories-since db since :namespace namespace)
                (dh/get-all-memories db :namespace namespace))
        dirty (filterv #(some? (:memory/content %)) dirty)]
    (if (empty? dirty)
      {:relationships-created 0 :dirty-count 0 :pairs-evaluated 0}
      (let [pairs     (find-neighbor-pairs db vector-index embedding-provider dirty namespace)
            ;; Pre-fetch all relationships for all IDs in pairs (single batch)
            all-pair-ids (into #{} (mapcat (fn [[a b]] [(:memory/id a) (:memory/id b)])) pairs)
            all-rels (or (dh/get-relationships db (vec all-pair-ids)) [])
            existing-rel-pairs (into #{}
                                     (map (fn [r]
                                            (vec (sort [(str (:relationship/source-id r))
                                                        (str (:relationship/target-id r))]))))
                                     all-rels)
            new-pairs (filterv (fn [[a b]]
                                 (let [pair-key (vec (sort [(str (:memory/id a))
                                                            (str (:memory/id b))]))]
                                   (not (contains? existing-rel-pairs pair-key))))
                               pairs)
            batches   (partition-all connect-batch-size new-pairs)
            created   (reduce + 0 (map #(process-connect-batch! deps % namespace) batches))]
        {:relationships-created created
         :dirty-count           (count dirty)
         :pairs-evaluated       (count new-pairs)}))))

;; ---------------------------------------------------------------------------
;; Phase 4: Curate — identify contradictions between facts
;; ---------------------------------------------------------------------------

(def ^:private curate-batch-size tuning/reflect-curate-batch-size)

(defn- process-curate-batch!
  "Send fact pairs to LLM for contradiction detection. Returns count of new contradictions."
  [{:keys [db chat-provider prompts]} pairs namespace]
  (try
    (let [{:keys [result usage]} (completion/detect-contradictions chat-provider prompts pairs)
          _ (when usage
              (usage/record-from-provider-safe! db {:operation "reflect" :step "curate"
                                                    :namespace namespace} usage))
          contradictions (or (:contradictions result) [])
          pairs-vec      (vec pairs)]
      (count
       (keep (fn [c]
               (let [idx  (:pair-index c)
                     pair (get pairs-vec idx)]
                 (when pair
                   (let [[a b] pair]
                     ;; Add bidirectional contradiction references
                     (dh/update-memory! db (:memory/id a)
                                        {:memory/contradiction-ids (:memory/id b)})
                     (dh/update-memory! db (:memory/id b)
                                        {:memory/contradiction-ids (:memory/id a)})
                     true))))
             contradictions)))
    (catch Exception e
      (log/error "Curate batch failed:" (.getMessage e) (ex-data e))
      0)))

(defn curate!
  "Phase 4: Check for contradictions among recent facts.
   When `since` is provided, only checks facts created after that time against their
   K=5 nearest neighbor facts (any age, same namespace) via vector search.
   Otherwise falls back to checking all fact pairs within each concept."
  [{:keys [db vector-index embedding-provider] :as deps} {:keys [namespace since]}]
  (let [dirty-facts (if since
                      (->> (dh/get-memories-since db since :namespace namespace)
                           (filterv #(= :layer/fact (:memory/layer %))))
                      ;; Fallback: all facts in all concepts
                      (->> (dh/get-concepts db :namespace namespace)
                           (mapcat (fn [c]
                                     (let [children (dh/get-children db (:memory/id c))]
                                       (filterv #(= :layer/fact (:memory/layer %)) children))))
                           vec))]
    (if (< (count dirty-facts) 2)
      {:contradictions-found 0}
      ;; For each dirty fact, find K nearest neighbor facts and check contradictions
      (let [all-pairs
            (->> dirty-facts
                 (mapcat (fn [f]
                           (try
                             (let [{:keys [embedding]} (llm-provider/embed embedding-provider
                                                                           (:memory/content f))
                                   results (protocols/search @vector-index embedding 5)
                                   result-ids (mapv (fn [{:keys [id]}] (UUID/fromString id)) results)
                                   result-mems (when (seq result-ids)
                                                 (dh/get-memories-batch-full db result-ids))
                                   mems-by-id (into {} (map (fn [m] [(:memory/id m) m]))
                                                    (or result-mems []))]
                               (->> result-ids
                                    (keep (fn [uid]
                                            (let [other (get mems-by-id uid)]
                                              (when (and other
                                                         (= :layer/fact (:memory/layer other))
                                                         (not= uid (:memory/id f))
                                                         (= (:memory/namespace other) namespace))
                                                (let [pair-key (vec (sort [(str (:memory/id f)) (str uid)]))]
                                                  {:pair-key pair-key :a f :b other})))))
                                    vec))
                             (catch Exception e
                               (log/warn "Curate search failed:" (.getMessage e))
                               []))))
                 (into {} (map (fn [{:keys [pair-key] :as e}] [pair-key e])))
                 vals
                 (mapv (fn [{:keys [a b]}] [a b])))
            ;; Pre-fetch all memories to check contradiction-ids
            all-curate-ids (into #{} (mapcat (fn [[a b]] [(:memory/id a) (:memory/id b)])) all-pairs)
            all-mems (dh/get-memories-batch-full db (vec all-curate-ids))
            mems-by-id (into {} (map (fn [m] [(:memory/id m) m])) (or all-mems []))
            new-pairs (filterv (fn [[a b]]
                                 (let [mem-a (get mems-by-id (:memory/id a))
                                       cids (set (:memory/contradiction-ids mem-a))]
                                   (not (contains? cids (:memory/id b)))))
                               all-pairs)
            batches   (partition-all curate-batch-size new-pairs)
            found     (reduce + 0 (map #(process-curate-batch! deps % namespace) batches))]
        {:contradictions-found found}))))

;; ---------------------------------------------------------------------------
;; Orchestrator
;; ---------------------------------------------------------------------------

(def ^:private phase-registry
  "Ordered phase definitions: [name, function, default-when-skipped]."
  [["organize"  organize!  {:facts-processed 0 :concepts-created 0
                            :concepts-reused 0 :domains-created 0}]
   ["summarize" summarize! {:summaries-created 0}]
   ["connect"   connect!   {:relationships-created 0}]
   ["curate"    curate!    {:contradictions-found 0}]])

(def ^:private all-phases (into #{} (map first) phase-registry))

(defn- run-phases
  "Run active phases from the registry, returning a map of phase-name → result."
  [deps params active-phases]
  (reduce (fn [results [phase-name phase-fn default]]
            (assoc results (keyword phase-name)
                   (if (contains? active-phases phase-name)
                     (phase-fn deps params)
                     default)))
          {} phase-registry))

(defn reflect!
  "Consolidate and organize the knowledge graph.

   Runs 4 phases in sequence (or a subset via :phases parameter):
     1. organize  — orphan facts → concepts, orphan concepts → domains
     2. summarize — create summary nodes for concepts/domains
     3. connect   — discover cross-concept relationships
     4. curate    — identify contradictions

   Args:
     deps   - map with :db, :vector-index, :embedding-provider, :chat-provider, :prompts, :tuning
     params - map with optional :dry-run, :query, :threshold, :namespace, :phases

   Returns per-phase results plus backward-compatible top-level counts."
  [deps {:keys [dry-run namespace phases] :as params}]
  (let [namespace     (or namespace schema/default-namespace)
        params        (assoc params :namespace namespace)
        active-phases (if (seq phases) (set phases) all-phases)]
    (log/info "Starting reflect operation" {:dry-run dry-run :namespace namespace :phases phases})
    (if dry-run
      (let [orphans (dh/get-orphan-facts (:db deps) :namespace namespace)]
        {:facts-processed  (count orphans)
         :concepts-created 0
         :domains-created  0
         :dry-run          true})
      (let [results   (run-phases deps params active-phases)
            org-result (:organize results)]
        {:facts-processed  (:facts-processed org-result)
         :concepts-created (:concepts-created org-result)
         :domains-created  (:domains-created org-result)
         :organize         (dissoc org-result :details)
         :summarize        (:summarize results)
         :connect          (:connect results)
         :curate           (:curate results)
         :details          (:details org-result)}))))
