(ns memlayer.persistence.datahike
  "Datahike connection management, schema, and memory CRUD queries.
   All query/mutation functions accept a DatahikeEntityStore record."
  (:require [datahike.api :as d]
            [memlayer.persistence.protocols :as protocols]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; -- Schema --

(def schema
  [{:db/ident       :memory/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/content
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/display-title
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/layer
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :memory/source
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/namespace
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :memory/parent-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/contradiction-ids
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/many}
   ;; Relationship attributes
   {:db/ident       :relationship/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/source-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/target-id
    :db/valueType   :db.type/uuid
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/confidence
    :db/valueType   :db.type/float
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}])

;; -- Lifecycle (raw conn — called during system init before wrapping) --

(defn transact-schema!
  "Transact the memory schema into the database."
  [conn]
  (d/transact conn schema))

(defn- store-config
  "Build a datahike store config from backend options."
  [{:keys [backend path]}]
  (case backend
    :memory {:backend :memory :id (UUID/randomUUID)}
    :file   {:backend :file :path path
             :id (UUID/nameUUIDFromBytes (.getBytes (str "memlayer:" path)))}
    ;; S3 backend deferred — see MEM-14
    (throw (ex-info (str "Unsupported datahike backend: " backend) {:backend backend}))))

(defn create-connection!
  "Create database if needed and return a connection."
  [opts]
  (let [cfg {:store (store-config opts)
             :schema-flexibility :write}]
    (when-not (d/database-exists? cfg)
      (log/info "Creating datahike database" cfg)
      (d/create-database cfg))
    (let [conn (d/connect cfg)]
      (transact-schema! conn)
      conn)))

;; -- Memory CRUD --

(defn insert-memory!
  "Insert a memory entity. Returns the memory id."
  [store memory-map]
  (let [conn (:conn store)]
    (when (and (contains? memory-map :memory/namespace)
               (nil? (:memory/namespace memory-map)))
      (throw (ex-info "Cannot insert memory with nil namespace — normalize upstream"
                      {:memory-map (select-keys memory-map [:memory/id :memory/layer :memory/namespace])})))
    (let [id (or (:memory/id memory-map) (UUID/randomUUID))
          entity (assoc memory-map :memory/id id)]
      (d/transact conn [entity])
      id)))

(defn get-memory
  "Fetch a memory by its UUID."
  [store id]
  (let [conn (:conn store)]
    (first (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?id
                  :where [?e :memory/id ?id]]
                @conn id))))

(defn get-memory-at
  "Fetch a memory by its UUID from a point-in-time snapshot.
   If as-of-date is nil, queries the current db value."
  [store id as-of-date]
  (let [conn (:conn store)
        db (if as-of-date
             (d/as-of @conn as-of-date)
             @conn)]
    (d/q '[:find (pull ?e [*]) .
           :in $ ?id
           :where [?e :memory/id ?id]]
         db id)))

(defn get-memories-by-namespace
  [store ns-name & {:keys [limit] :or {limit 20}}]
  (let [conn (:conn store)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :in $ ?ns
                :where [?e :memory/namespace ?ns]]
              @conn ns-name)
         (sort-by :db/id #(compare %2 %1))
         (take limit))))

(defn get-recent-memories
  [store & {:keys [limit] :or {limit 20}}]
  (let [conn (:conn store)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :where [?e :memory/id _]]
              @conn)
         (sort-by :db/id #(compare %2 %1))
         (take limit))))

(defn update-memory!
  "Update a memory's content (and optionally other fields)."
  [store id updates]
  (let [conn (:conn store)]
    (when (and (contains? updates :memory/namespace)
               (nil? (:memory/namespace updates)))
      (throw (ex-info "Cannot update memory with nil namespace — normalize upstream"
                      {:memory-id id :updates (select-keys updates [:memory/namespace])})))
    (let [existing (get-memory store id)]
      (when existing
        (d/transact conn [(merge {:memory/id id} updates)])))))

(defn get-memory-history
  "Get all historical changes for a memory using datahike's temporal features."
  [store id]
  (let [conn (:conn store)]
    (d/q '[:find ?attr ?val ?tx ?added
           :in $ ?id
           :where
           [?e :memory/id ?id]
           [?e ?attr ?val ?tx ?added]]
         (d/history @conn) id)))

(defn get-memory-created-at
  "Get the creation timestamp for a memory from datahike's transaction log.
   Returns a java.util.Date or nil if the memory doesn't exist."
  [store id]
  (let [conn (:conn store)]
    (when-let [tx-id (d/q '[:find (min ?tx) .
                            :in $ ?id
                            :where
                            [?e :memory/id ?id ?tx true]]
                          (d/history @conn) id)]
      (d/q '[:find ?inst .
             :in $ ?tx
             :where [?tx :db/txInstant ?inst]]
           @conn tx-id))))

;; -- Dashboard queries --

(defn get-all-memories
  "Fetch all memories with optional filters and pagination."
  [store & {:keys [namespace layer limit offset]
            :or   {limit 100 offset 0}}]
  (let [conn (:conn store)
        where-clauses (cond-> '[[?e :memory/id _]]
                        namespace (conj '[?e :memory/namespace ?ns])
                        layer     (conj '[?e :memory/layer ?layer]))
        in-clause     (cond-> '[$]
                        namespace (conj '?ns)
                        layer     (conj '?layer))
        query         {:find  '[(pull ?e [*])]
                       :in    in-clause
                       :where where-clauses}
        args          (cond-> [@conn]
                        namespace (conj namespace)
                        layer     (conj layer))
        results       (mapv first (apply d/q query args))]
    (->> results
         (sort-by :db/id #(compare %2 %1))
         (drop offset)
         (take limit))))

(defn get-memories-since
  "Get memories created after the given timestamp, optionally filtered by namespace.
   Uses datahike's history DB to find entities whose :memory/id was first asserted
   in a transaction after `since`. Pulls full entities from the current DB."
  [store since & {:keys [namespace]}]
  (let [conn (:conn store)
        recent-txs (d/q '[:find [?tx ...]
                          :in $ ?since
                          :where
                          [?tx :db/txInstant ?t]
                          [(.after ^java.util.Date ?t ?since)]]
                        @conn since)
        history-db (d/history @conn)
        mem-ids    (when (seq recent-txs)
                     (d/q '[:find [?id ...]
                            :in $ [?tx ...]
                            :where
                            [?e :memory/id ?id ?tx true]]
                          history-db (vec recent-txs)))
        memories    (if (seq mem-ids)
                      (let [all (d/q '[:find [(pull ?e [*]) ...]
                                       :in $ [?id ...]
                                       :where [?e :memory/id ?id]]
                                     @conn (vec mem-ids))]
                        (filterv (fn [m]
                                   (if namespace
                                     (= namespace (:memory/namespace m))
                                     true))
                                 all))
                      [])]
    memories))

(defn count-all-memories
  "Count total memories, optionally filtered by namespace and/or layer."
  [store & {:keys [namespace layer]}]
  (let [conn (:conn store)
        where-clauses (cond-> '[[?e :memory/id _]]
                        namespace (conj '[?e :memory/namespace ?ns])
                        layer     (conj '[?e :memory/layer ?layer]))
        in-clause     (cond-> '[$]
                        namespace (conj '?ns)
                        layer     (conj '?layer))
        query         {:find  '[?e]
                       :in    in-clause
                       :where where-clauses}
        args          (cond-> [@conn]
                        namespace (conj namespace)
                        layer     (conj layer))]
    (count (apply d/q query args))))

(defn count-memories-by-namespace
  "Count memories in a specific namespace without materializing entities."
  [store ns-name]
  (let [conn (:conn store)]
    (count (d/q '[:find ?e
                  :in $ ?ns
                  :where
                  [?e :memory/namespace ?ns]]
                @conn ns-name))))

(defn count-active-memories
  [store]
  (count-all-memories store))

(defn count-by-layer
  "Count memories grouped by layer, optionally filtered by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        results (if namespace
                  (d/q '[:find ?layer (count ?e)
                         :in $ ?ns
                         :where
                         [?e :memory/layer ?layer]
                         [?e :memory/namespace ?ns]]
                       @conn namespace)
                  (d/q '[:find ?layer (count ?e)
                         :where
                         [?e :memory/layer ?layer]]
                       @conn))]
    (into {} (map (fn [[layer cnt]] [(name layer) cnt])) results)))

(defn get-children
  "Fetch memories whose parent-id matches the given id, with pagination."
  [store parent-id & {:keys [limit offset] :or {limit 100 offset 0}}]
  (let [conn (:conn store)]
    (->> (d/q '[:find [(pull ?e [*]) ...]
                :in $ ?pid
                :where [?e :memory/parent-id ?pid]]
              @conn parent-id)
         (sort-by :db/id #(compare %2 %1))
         (drop offset)
         (take limit))))

(defn count-children
  "Count children for a given parent-id."
  [store parent-id]
  (let [conn (:conn store)]
    (count (d/q '[:find ?e
                  :in $ ?pid
                  :where [?e :memory/parent-id ?pid]]
                @conn parent-id))))

(defn get-relationships
  "Fetch relationships where any of the given memory IDs is source or target."
  [store memory-ids]
  (let [conn (:conn store)]
    (when (seq memory-ids)
      (let [id-set (set memory-ids)
            as-source (d/q '[:find [(pull ?e [*]) ...]
                             :in $ [?mid ...]
                             :where [?e :relationship/source-id ?mid]]
                           @conn (vec id-set))
            as-target (d/q '[:find [(pull ?e [*]) ...]
                             :in $ [?mid ...]
                             :where [?e :relationship/target-id ?mid]]
                           @conn (vec id-set))
            all (into (vec as-source) as-target)]
        (vals (into {} (map (fn [r] [(:relationship/id r) r])) all))))))

(defn get-relationships-for-memory
  "Fetch relationships where a single memory ID is source or target."
  [store memory-id]
  (get-relationships store [memory-id]))

(defn get-distinct-relationship-types
  "Return all distinct relationship type keywords in the database."
  [store]
  (let [conn (:conn store)]
    (d/q '[:find [?t ...] :where [_ :relationship/type ?t]] @conn)))

(defn get-all-memory-ids
  "Return all memory UUIDs without pulling full entities."
  [store]
  (let [conn (:conn store)]
    (d/q '[:find [?id ...]
           :where [?e :memory/id ?id]]
         @conn)))

(defn get-distinct-namespaces
  "Return distinct namespace strings from all memories."
  [store]
  (let [conn (:conn store)]
    (->> (d/q '[:find [?ns ...]
                :where [?e :memory/namespace ?ns]]
              @conn)
         sort
         vec)))

;; -- Relationships --

;; -- Retract / Evict --

(defn retract-memory!
  "Retract a memory by its UUID. Preserved in d/history."
  [store id]
  (let [conn (:conn store)]
    (if (get-memory store id)
      (do (d/transact conn [[:db/retractEntity [:memory/id id]]])
          true)
      false)))

(defn evict-memory!
  "Purge a memory from current DB and history (GDPR)."
  [store id]
  (let [conn (:conn store)]
    (if (get-memory store id)
      (do (d/transact conn [[:db.purge/entity [:memory/id id]]])
          true)
      false)))

;; -- Relationships --

(defn delete-relationships-for-memory!
  "Delete all relationships where the given memory is source or target.
   Returns the count of deleted relationships."
  [store memory-id]
  (let [conn (:conn store)
        eids (concat
              (d/q '[:find [?e ...]
                     :in $ ?mid
                     :where [?e :relationship/source-id ?mid]]
                   @conn memory-id)
              (d/q '[:find [?e ...]
                     :in $ ?mid
                     :where [?e :relationship/target-id ?mid]]
                   @conn memory-id))]
    (when (seq eids)
      (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) eids)))
    (count eids)))

(defn forget-memory!
  "Atomically retract a memory and all its relationships in a single transaction.
   Returns {:memories-retracted N :relationships-deleted N}."
  [store id]
  (if (get-memory store id)
    (let [conn (:conn store)
          rel-eids (d/q '[:find [?e ...]
                          :in $ ?mid
                          :where (or [?e :relationship/source-id ?mid]
                                     [?e :relationship/target-id ?mid])]
                        @conn id)
          rel-txs (mapv (fn [eid] [:db/retractEntity eid]) rel-eids)
          all-txs (conj rel-txs [:db/retractEntity [:memory/id id]])]
      (d/transact conn all-txs)
      {:memories-retracted 1 :relationships-deleted (count rel-eids)})
    {:memories-retracted 0 :relationships-deleted 0}))

(defn delete-all-memories!
  "Delete all memories from the database."
  [store]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :where [?e :memory/id _]]
                  @conn)]
    (when (seq eids)
      (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) eids)))
    (count eids)))

(defn delete-all-relationships!
  "Delete all relationships from the database."
  [store]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :where [?e :relationship/id _]]
                  @conn)]
    (when (seq eids)
      (d/transact conn (mapv (fn [eid] [:db/retractEntity eid]) eids)))
    (count eids)))

(defn get-orphan-facts
  "Find fact-layer memories with no parent-id set.
   When namespace is provided, only returns facts in that namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        all-facts (if namespace
                    (d/q '[:find [(pull ?e [*]) ...]
                           :in $ ?ns
                           :where
                           [?e :memory/layer :layer/fact]
                           [?e :memory/namespace ?ns]]
                         @conn namespace)
                    (d/q '[:find [(pull ?e [*]) ...]
                           :where [?e :memory/layer :layer/fact]]
                         @conn))]
    (filterv #(nil? (:memory/parent-id %)) all-facts)))

(defn get-concepts
  "Fetch concept-layer memories, optionally scoped by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)]
    (if namespace
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?ns
             :where
             [?e :memory/layer :layer/concept]
             [?e :memory/namespace ?ns]]
           @conn namespace)
      (d/q '[:find [(pull ?e [*]) ...]
             :where [?e :memory/layer :layer/concept]]
           @conn))))

(defn get-domains
  "Fetch domain-layer memories, optionally scoped by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)]
    (if namespace
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ ?ns
             :where
             [?e :memory/layer :layer/domain]
             [?e :memory/namespace ?ns]]
           @conn namespace)
      (d/q '[:find [(pull ?e [*]) ...]
             :where [?e :memory/layer :layer/domain]]
           @conn))))

(defn get-orphan-concepts
  "Find concept-layer memories with no parent-id set.
   When namespace is provided, only returns concepts in that namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        all-concepts (if namespace
                       (d/q '[:find [(pull ?e [*]) ...]
                              :in $ ?ns
                              :where
                              [?e :memory/layer :layer/concept]
                              [?e :memory/namespace ?ns]]
                            @conn namespace)
                       (d/q '[:find [(pull ?e [*]) ...]
                              :where [?e :memory/layer :layer/concept]]
                            @conn))]
    (filterv #(nil? (:memory/parent-id %)) all-concepts)))

(defn get-siblings
  "Fetch other children of the same parent, excluding the given memory."
  [store parent-id & {:keys [exclude-id limit] :or {limit 10}}]
  (let [conn (:conn store)
        children (d/q '[:find [(pull ?e [*]) ...]
                        :in $ ?pid
                        :where [?e :memory/parent-id ?pid]]
                      @conn parent-id)]
    (->> children
         (remove #(= exclude-id (:memory/id %)))
         (take limit)
         vec)))

(defn get-summaries-for
  "Fetch summary-layer memories whose parent-id matches the given memory id."
  [store memory-id]
  (let [conn (:conn store)]
    (d/q '[:find [(pull ?e [*]) ...]
           :in $ ?pid
           :where
           [?e :memory/layer :layer/summary]
           [?e :memory/parent-id ?pid]]
         @conn memory-id)))

(defn get-memories-batch
  "Fetch multiple memories by UUID in a single query, with a minimal pull pattern."
  [store ids]
  (let [conn (:conn store)]
    (when (seq ids)
      (d/q '[:find [(pull ?e [:memory/id :memory/content :memory/layer :memory/parent-id
                              :memory/source :memory/namespace]) ...]
             :in $ [?id ...]
             :where [?e :memory/id ?id]]
           @conn (vec (set ids))))))

(defn get-memories-batch-full
  "Fetch multiple memories by UUID in a single query, with full pull pattern."
  [store ids]
  (let [conn (:conn store)]
    (when (seq ids)
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ [?id ...]
             :where [?e :memory/id ?id]]
           @conn (vec (set ids))))))

(defn get-memories-batch-at
  "Fetch multiple memories by UUID from a point-in-time snapshot.
   If as-of-date is nil, queries the current db value. Full pull pattern."
  [store ids as-of-date]
  (let [conn (:conn store)
        db (if as-of-date
             (d/as-of @conn as-of-date)
             @conn)]
    (when (seq ids)
      (d/q '[:find [(pull ?e [*]) ...]
             :in $ [?id ...]
             :where [?e :memory/id ?id]]
           db (vec (set ids))))))

(defn get-summaries-for-batch
  "Fetch summary-layer memories for multiple parent IDs in a single query."
  [store parent-ids]
  (let [conn (:conn store)]
    (when (seq parent-ids)
      (d/q '[:find [(pull ?e [:memory/id :memory/content :memory/layer :memory/parent-id]) ...]
             :in $ [?pid ...]
             :where
             [?e :memory/layer :layer/summary]
             [?e :memory/parent-id ?pid]]
           @conn (vec (set parent-ids))))))

(defn get-children-of-parents-batch
  "Fetch children for multiple parent IDs in a single query, with a minimal pull pattern."
  [store parent-ids]
  (let [conn (:conn store)]
    (when (seq parent-ids)
      (d/q '[:find [(pull ?e [:memory/id :memory/content :memory/layer :memory/parent-id]) ...]
             :in $ [?pid ...]
             :where [?e :memory/parent-id ?pid]]
           @conn (vec (set parent-ids))))))

(defn- relationship-exists?
  "Check if a relationship already exists between two memories (in either direction)
   with the same type. Uses two simple queries to avoid Datalog or-clause issues."
  [conn source-id target-id type]
  (let [forward (d/q '[:find ?e
                       :in $ ?s ?t ?type
                       :where
                       [?e :relationship/source-id ?s]
                       [?e :relationship/target-id ?t]
                       [?e :relationship/type ?type]]
                     @conn source-id target-id type)
        reverse (d/q '[:find ?e
                       :in $ ?s ?t ?type
                       :where
                       [?e :relationship/source-id ?s]
                       [?e :relationship/target-id ?t]
                       [?e :relationship/type ?type]]
                     @conn target-id source-id type)]
    (or (seq forward) (seq reverse))))

(defn insert-relationship!
  "Insert a relationship between two memories.
   Rejects self-references and duplicate pairs (same type, either direction).
   Returns the relationship id, or nil if skipped."
  [store {:keys [source-id target-id type confidence description]
          :or   {confidence 1.0}}]
  (let [conn (:conn store)]
    (when (and (not= source-id target-id)
               (not (relationship-exists? conn source-id target-id type)))
      (let [id (java.util.UUID/randomUUID)]
        (d/transact conn [(cond-> {:relationship/id         id
                                   :relationship/source-id  source-id
                                   :relationship/target-id  target-id
                                   :relationship/type       type
                                   :relationship/confidence (float confidence)}
                            description (assoc :relationship/description description))])
        id))))

;; -- EntityStore protocol implementation --

(defrecord DatahikeEntityStore [conn]
  protocols/EntityStore
  (q [_ query args] (apply d/q query @conn args))
  (pull [_ selector eid] (d/pull @conn selector eid))
  (transact! [_ tx-data] (d/transact conn tx-data))
  (history [_] (d/history @conn))
  (db-value [_] @conn))
