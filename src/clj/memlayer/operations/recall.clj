(ns memlayer.operations.recall
  "Recall pipeline: embed query → search vectors → fetch memories → return ranked results."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.usage :as usage]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.llm.completion :as completion]
            [memlayer.schema :as schema]
            [memlayer.tuning :as tuning]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

(def ^:private default-recall-limit tuning/recall-default-limit)

(defn- parse-as-of
  "Parse an ISO-8601 string to a Date, or nil."
  [s]
  (when s
    (try
      (java.util.Date/from (java.time.Instant/parse s))
      (catch Exception _ nil))))

(def ^:private max-rels-per-memory tuning/recall-max-rels-per-memory)
(def ^:private max-siblings-per-parent tuning/recall-max-siblings-per-parent)

(defn- walk-ancestor-ids
  "Walk parent chains for all memories, returning a map of memory-id → [ancestor-ids]."
  [db memories]
  (let [id->parent (into {} (keep (fn [m] (when-let [pid (:memory/parent-id m)]
                                            [(:memory/id m) pid])))
                         memories)
        all-parent-ids (set (vals id->parent))]
    ;; Fetch all parents in one batch, then walk chains
    (if (empty? all-parent-ids)
      {}
      (let [parent-entities (dh/get-memories-batch db (vec all-parent-ids))
            parent-by-id    (into {} (map (fn [m] [(:memory/id m) m])) parent-entities)
            ;; Now walk full chains using the fetched data, fetching deeper levels as needed
            walk-full (fn [start-pid]
                        (loop [pid  start-pid
                               acc  []
                               seen #{}
                               by-id parent-by-id]
                          (if (or (nil? pid) (contains? seen pid))
                            {:ancestors acc :by-id by-id}
                            (if-let [parent (get by-id pid)]
                              (recur (:memory/parent-id parent) (conj acc parent) (conj seen pid) by-id)
                              ;; Not yet fetched — fetch and continue
                              (let [fetched (dh/get-memories-batch db [pid])
                                    by-id'  (into by-id (map (fn [m] [(:memory/id m) m])) fetched)]
                                (if-let [parent (get by-id' pid)]
                                  (recur (:memory/parent-id parent) (conj acc parent) (conj seen pid) by-id')
                                  {:ancestors acc :by-id by-id'}))))))]
        (reduce (fn [result mem]
                  (if-let [pid (get id->parent (:memory/id mem))]
                    (let [{:keys [ancestors]} (walk-full pid)]
                      (assoc result (:memory/id mem) ancestors))
                    (assoc result (:memory/id mem) [])))
                {} memories)))))

(defn- expand-graph-batch
  "Batch graph expansion for all matched memories. Returns expand-entries (one per memory)
   using batch queries instead of per-memory queries."
  [db matched]
  (let [memories       (mapv :mem matched)
        memory-ids     (mapv :mem-id matched)
        ;; 1. Walk ancestor chains (batched parent fetching)
        ancestors-by-id (walk-ancestor-ids db memories)
        ;; 2. Batch fetch summaries for all ancestor IDs
        all-ancestor-ids (->> (vals ancestors-by-id) (mapcat #(keep :memory/id %)) distinct vec)
        all-summaries    (if (seq all-ancestor-ids)
                           (dh/get-summaries-for-batch db all-ancestor-ids)
                           [])
        summaries-by-parent (group-by :memory/parent-id all-summaries)
        ;; 3. Batch fetch siblings for all parent IDs
        parent-ids      (->> memories (keep :memory/parent-id) distinct vec)
        all-children    (if (seq parent-ids)
                          (dh/get-children-of-parents-batch db parent-ids)
                          [])
        children-by-parent (group-by :memory/parent-id all-children)
        mem-id-set      (set memory-ids)
        ;; 4. Batch fetch relationships for all matched memories
        all-rels        (or (dh/get-relationships db memory-ids) [])
        rels-by-memory  (reduce (fn [m r]
                                  (let [src (:relationship/source-id r)
                                        tgt (:relationship/target-id r)]
                                    (cond-> m
                                      (contains? mem-id-set src) (update src (fnil conj []) r)
                                      (contains? mem-id-set tgt) (update tgt (fnil conj []) r))))
                                {} all-rels)
        ;; 5. Batch fetch all relationship endpoints
        all-endpoint-ids (->> all-rels
                              (mapcat (fn [r] [(:relationship/source-id r) (:relationship/target-id r)]))
                              (remove mem-id-set)
                              distinct vec)
        endpoint-entities (if (seq all-endpoint-ids)
                            (dh/get-memories-batch db all-endpoint-ids)
                            [])
        endpoint-by-id   (into {} (map (fn [m] [(:memory/id m) m])) endpoint-entities)]
    ;; Assemble per-memory expand entries
    (mapv (fn [{:keys [mem mem-id]}]
            (let [ancestors  (get ancestors-by-id mem-id [])
                  summaries  (->> ancestors
                                  (mapcat #(get summaries-by-parent (:memory/id %) []))
                                  vec)
                  siblings   (when-let [pid (:memory/parent-id mem)]
                               (->> (get children-by-parent pid [])
                                    (remove #(= mem-id (:memory/id %)))
                                    (remove #(= :layer/summary (:memory/layer %)))
                                    (take max-siblings-per-parent)
                                    vec))
                  rels       (take max-rels-per-memory (get rels-by-memory mem-id []))
                  related    (keep (fn [r]
                                     (let [other-id (if (= mem-id (:relationship/source-id r))
                                                      (:relationship/target-id r)
                                                      (:relationship/source-id r))]
                                       (or (get endpoint-by-id other-id)
                                          ;; endpoint might be another matched memory
                                           (first (filter #(= other-id (:memory/id (:mem %))) matched)))))
                                   rels)]
              {:ancestors ancestors
               :summaries summaries
               :siblings  (vec (or siblings []))
               :related   (vec related)
               :rels      rels}))
          matched)))

(defn- serialize-ancestor [a]
  {:id      (str (:memory/id a))
   :content (:memory/content a)
   :layer   (some-> (:memory/layer a) name)})

(defn- serialize-relationship
  "Serialize a relationship + its endpoint memory for per-memory graph data."
  [rel endpoint]
  (cond-> {:id      (str (:memory/id endpoint))
           :content (:memory/content endpoint)
           :layer   (some-> (:memory/layer endpoint) name)}
    (:relationship/description rel) (assoc :description (:relationship/description rel))
    (:relationship/type rel)        (assoc :type (some-> (:relationship/type rel) name))))

(defn- serialize-recall-memory
  [mem distance expand-data]
  (let [memory-id (:memory/id mem)]
    (cond-> {:memory-id  (str memory-id)
             :content    (:memory/content mem)
             :layer      (some-> (:memory/layer mem) name)
             :importance (double (or (:memory/importance mem) 0.0))
             :source     (or (:memory/source mem) "")
             :namespace  (:memory/namespace mem)
             :parent-id  (some-> (:memory/parent-id mem) str)
             :distance   (double distance)}
      expand-data (assoc :ancestors (mapv serialize-ancestor (:ancestors expand-data))
                         :summaries (mapv serialize-ancestor (:summaries expand-data))
                         :siblings  (mapv serialize-ancestor (:siblings expand-data))
                         :related   (mapv (fn [[rel endpoint]]
                                            (serialize-relationship rel endpoint))
                                          (map vector (:rels expand-data) (:related expand-data)))))))

(def ^:private vector-search-oversample
  "Fetch N× the requested limit to allow for namespace/layer/as-of filtering."
  tuning/recall-vector-oversample)

(def ^:private graph-proximity-bonus tuning/recall-graph-proximity-bonus)

(defn- apply-graph-reranking
  "Memories sharing a parent with other matched memories get a distance bonus."
  [memories]
  (let [parent-counts (->> memories
                           (keep :parent-id)
                           frequencies)]
    (mapv (fn [mem]
            (if-let [pid (:parent-id mem)]
              (let [sibling-count (get parent-counts pid 0)]
                (if (> sibling-count 1)
                  (update mem :distance - (* graph-proximity-bonus (dec sibling-count)))
                  mem))
              mem))
          memories)))

(defn- extract-activation
  "Collect all memory IDs and relationship IDs traversed during graph expansion."
  [matched expand-entries]
  (let [match-ids   (map #(str (:mem-id %)) matched)
        ancestor-ids (mapcat #(keep (comp str :memory/id) (:ancestors %)) expand-entries)
        summary-ids  (mapcat #(keep (comp str :memory/id) (:summaries %)) expand-entries)
        sibling-ids  (mapcat #(keep (comp str :memory/id) (:siblings %)) expand-entries)
        related-ids  (mapcat #(keep (comp str :memory/id) (:related %)) expand-entries)
        rel-ids      (mapcat #(keep (comp str :relationship/id) (:rels %)) expand-entries)]
    {:memory-ids       (vec (distinct (concat match-ids ancestor-ids summary-ids sibling-ids related-ids)))
     :relationship-ids (vec (distinct rel-ids))}))

(defn- aggregate-graph-context
  "Build deduplicated graph summary from per-memory expansion data."
  [expand-entries]
  (let [concepts-map  (into {}
                            (comp (mapcat :ancestors)
                                  (filter #(#{:layer/concept :layer/domain} (:memory/layer %)))
                                  (map (fn [c] [(:memory/id c) (serialize-ancestor c)])))
                            expand-entries)
        summaries-map (into {}
                            (comp (mapcat :summaries)
                                  (map (fn [s] [(:memory/id s)
                                                {:id        (str (:memory/id s))
                                                 :content   (:memory/content s)
                                                 :parent-id (some-> (:memory/parent-id s) str)}])))
                            expand-entries)
        rels-map      (into {}
                            (comp (mapcat :rels)
                                  (map (fn [r] [(:relationship/id r)
                                                (cond-> {:source-id (str (:relationship/source-id r))
                                                         :target-id (str (:relationship/target-id r))
                                                         :type      (some-> (:relationship/type r) name)}
                                                  (:relationship/description r)
                                                  (assoc :description (:relationship/description r)))])))
                            expand-entries)]
    {:concepts      (vec (vals concepts-map))
     :summaries     (vec (vals summaries-map))
     :relationships (vec (vals rels-map))}))

(defn recall!
  "Semantic search over stored memories, with LLM-generated answer.

   Args:
     deps   - map with :db, :vector-index, :embedding-provider, :chat-provider, :prompts, :tuning
     params - map with :query, and optionally :namespace, :limit, :as-of, :expand-graph, :layer

   Returns:
     {:query string :answer string :memories [...] :count int}"
  [{:keys [db vector-index embedding-provider chat-provider prompts tuning]}
   {:keys [query namespace limit as-of expand-graph layer]}]
  (let [namespace       (or namespace schema/default-namespace)
        limit           (or limit (:recall-default-limit tuning) default-recall-limit)
        as-of-date      (parse-as-of as-of)
        layer-filter    (when layer (keyword "layer" layer))
        idx             @vector-index
        {:keys [embedding usage]} (llm-provider/embed embedding-provider query)]
    (when usage
      (usage/record-from-provider-safe! db {:operation "recall" :step "query-embed" :namespace namespace}
                                        usage))
    (log/info "Starting recall" {:query-length (count query) :namespace namespace :limit limit})

    (let [raw-results (try
                        (protocols/search idx embedding (* limit vector-search-oversample))
                        (catch Exception e
                          (log/warn "Vector search failed:" (.getMessage e))
                          []))
          matched     (->> raw-results
                           (keep (fn [{:keys [id distance]}]
                                   (let [mem-id (UUID/fromString id)
                                         mem    (dh/get-memory-at db mem-id as-of-date)]
                                     (when (and mem
                                                (= namespace (:memory/namespace mem))
                                                (or (nil? layer-filter)
                                                    (= layer-filter (:memory/layer mem))))
                                       {:mem mem :mem-id mem-id :distance distance}))))
                           vec)
          ;; Graph expansion — batched across all matched memories
          expand-entries (when expand-graph
                           (expand-graph-batch db matched))
          ;; Serialize with per-memory graph data
          serialized (->> (map-indexed
                           (fn [i {:keys [mem distance]}]
                             (serialize-recall-memory mem distance
                                                      (when expand-graph (nth expand-entries i))))
                           matched)
                          vec)
          ;; Re-rank with graph proximity bonus, then sort and limit
          memories (cond->> serialized
                     expand-graph (apply-graph-reranking)
                     true         (sort-by :distance)
                     true         (take limit)
                     true         vec)
          ;; Aggregate graph context
          graph (when (and expand-graph (seq expand-entries))
                  (aggregate-graph-context expand-entries))
          ;; Activation: which nodes/edges were traversed
          activation (when (and expand-graph (seq expand-entries))
                       (extract-activation matched expand-entries))]

      (log/info "Recall complete" {:results (count memories)})
      (let [base-result (cond-> {:query    query
                                 :memories memories
                                 :count    (count memories)}
                          usage      (assoc :usage usage)
                          graph      (assoc :graph graph)
                          activation (assoc :activation activation))
            ;; Always generate an answer when there are matching memories
            {answer-result :result answer-usage :usage}
            (when (seq memories)
              (completion/generate-answer chat-provider prompts query memories graph))]
        (when answer-usage
          (usage/record-from-provider-safe! db
                                            {:operation "recall" :step "generate-answer" :namespace namespace}
                                            answer-usage))
        (cond-> base-result
          answer-result (assoc :answer answer-result))))))
