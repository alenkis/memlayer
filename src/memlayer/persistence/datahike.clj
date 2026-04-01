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
   {:db/ident       :memory/parent
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :memory/contradictions
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/many}
   {:db/ident       :memory/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true}
   ;; Relationship attributes
   {:db/ident       :relationship/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/type
    :db/valueType   :db.type/keyword
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/confidence
    :db/valueType   :db.type/float
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/description
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/source
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}
   {:db/ident       :relationship/target
    :db/valueType   :db.type/ref
    :db/cardinality :db.cardinality/one}])

;; -- Pull patterns --

(def ^:private memory-pull
  "Full pull pattern for memory entities."
  [:db/id :memory/id :memory/content :memory/display-title :memory/layer
   :memory/source :memory/namespace
   {:memory/parent [:memory/id]} {:memory/contradictions [:memory/id]}])

(def ^:private memory-pull-minimal
  "Minimal pull pattern for memory entities (used in batch queries)."
  [:memory/id :memory/content :memory/layer
   {:memory/parent [:memory/id]} :memory/source :memory/namespace])

(def ^:private relationship-pull
  "Full pull pattern for relationship entities."
  [:relationship/id {:relationship/source [:memory/id]}
   {:relationship/target [:memory/id]} :relationship/type
   :relationship/confidence :relationship/description])

(defn parent-id
  "Extract parent UUID from a pulled memory with ref structure."
  [m]
  (get-in m [:memory/parent :memory/id]))

(defn- pull-memories
  "Pull multiple memory entities by their datahike entity IDs."
  [db eids]
  (mapv #(d/pull db memory-pull %) eids))

(defn- pull-relationships
  "Pull multiple relationship entities by their datahike entity IDs."
  [db eids]
  (mapv #(d/pull db relationship-pull %) eids))

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
          entity (cond-> (assoc memory-map :memory/id id)
                   (not (:memory/created-at memory-map))
                   (assoc :memory/created-at (java.util.Date.)))]
      (d/transact conn [entity])
      id)))

(defn get-memory
  "Fetch a memory by its UUID."
  [store id]
  (let [conn (:conn store)]
    (try
      (let [result (d/pull @conn memory-pull [:memory/id id])]
        (when (:memory/id result)
          result))
      (catch clojure.lang.ExceptionInfo _
        nil))))

(defn get-memory-at
  "Fetch a memory by its UUID from a point-in-time snapshot.
   If as-of-date is nil, queries the current db value."
  [store id as-of-date]
  (let [conn (:conn store)
        db (if as-of-date
             (d/as-of @conn as-of-date)
             @conn)]
    (try
      (let [result (d/pull db memory-pull [:memory/id id])]
        (when (:memory/id result)
          result))
      (catch clojure.lang.ExceptionInfo _
        nil))))

(defn get-memories-by-namespace
  [store ns-name & {:keys [limit] :or {limit 20}}]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :in $ ?ns
                    :where [?e :memory/namespace ?ns]]
                  @conn ns-name)]
    (->> (pull-memories @conn eids)
         (sort-by :memory/created-at #(compare %2 %1))
         (take limit))))

(defn get-recent-memories
  [store & {:keys [limit] :or {limit 20}}]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :where [?e :memory/id _]]
                  @conn)]
    (->> (pull-memories @conn eids)
         (sort-by :memory/created-at #(compare %2 %1))
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
  "Get the creation timestamp for a memory.
   Returns a java.util.Date or nil if the memory doesn't exist."
  [store id]
  (:memory/created-at (get-memory store id)))

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
        query         {:find  '[?e]
                       :in    in-clause
                       :where where-clauses}
        args          (cond-> [@conn]
                        namespace (conj namespace)
                        layer     (conj layer))
        eids          (mapv first (apply d/q query args))]
    (->> (pull-memories @conn eids)
         (sort-by :memory/created-at #(compare %2 %1))
         (drop offset)
         (take limit))))

(defn get-memories-since
  "Get memories created after the given timestamp, optionally filtered by namespace."
  [store since & {:keys [namespace]}]
  (let [conn (:conn store)
        query (if namespace
                '[:find [?e ...]
                  :in $ ?since ?ns
                  :where
                  [?e :memory/created-at ?t]
                  [(.after ^java.util.Date ?t ?since)]
                  [?e :memory/namespace ?ns]]
                '[:find [?e ...]
                  :in $ ?since
                  :where
                  [?e :memory/created-at ?t]
                  [(.after ^java.util.Date ?t ?since)]])
        args (if namespace
               [@conn since namespace]
               [@conn since])
        eids (apply d/q query args)]
    (pull-memories @conn eids)))

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

(defn- count-by-layer-in-ns [conn ns-name]
  (d/q '[:find ?layer (count ?e)
         :in $ ?ns
         :where
         [?e :memory/layer ?layer]
         [?e :memory/namespace ?ns]]
       @conn ns-name))

(defn- count-by-layer-all [conn]
  (d/q '[:find ?layer (count ?e)
         :where
         [?e :memory/layer ?layer]]
       @conn))

(defn count-by-layer
  "Count memories grouped by layer, optionally filtered by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        results (if namespace
                  (count-by-layer-in-ns conn namespace)
                  (count-by-layer-all conn))]
    (into {} (map (fn [[layer cnt]] [(name layer) cnt])) results)))

(defn get-children
  "Fetch memories whose parent matches the given id, with pagination."
  [store pid & {:keys [limit offset] :or {limit 100 offset 0}}]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :in $ ?pid
                    :where
                    [?p :memory/id ?pid]
                    [?e :memory/parent ?p]]
                  @conn pid)]
    (->> (pull-memories @conn eids)
         (sort-by :memory/created-at #(compare %2 %1))
         (drop offset)
         (take limit))))

(defn count-children
  "Count children for a given parent id."
  [store pid]
  (let [conn (:conn store)]
    (count (d/q '[:find ?e
                  :in $ ?pid
                  :where
                  [?p :memory/id ?pid]
                  [?e :memory/parent ?p]]
                @conn pid))))

(defn get-relationships
  "Fetch relationships where any of the given memory IDs is source or target."
  [store memory-ids]
  (let [conn (:conn store)]
    (when (seq memory-ids)
      (let [id-set (set memory-ids)
            as-source (d/q '[:find [?e ...]
                             :in $ [?mid ...]
                             :where
                             [?m :memory/id ?mid]
                             [?e :relationship/source ?m]]
                           @conn (vec id-set))
            as-target (d/q '[:find [?e ...]
                             :in $ [?mid ...]
                             :where
                             [?m :memory/id ?mid]
                             [?e :relationship/target ?m]]
                           @conn (vec id-set))
            all-eids (distinct (concat as-source as-target))
            all (pull-relationships @conn all-eids)]
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
                     :where
                     [?m :memory/id ?mid]
                     [?e :relationship/source ?m]]
                   @conn memory-id)
              (d/q '[:find [?e ...]
                     :in $ ?mid
                     :where
                     [?m :memory/id ?mid]
                     [?e :relationship/target ?m]]
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
          src-eids (d/q '[:find [?e ...]
                          :in $ ?mid
                          :where
                          [?m :memory/id ?mid]
                          [?e :relationship/source ?m]]
                        @conn id)
          tgt-eids (d/q '[:find [?e ...]
                          :in $ ?mid
                          :where
                          [?m :memory/id ?mid]
                          [?e :relationship/target ?m]]
                        @conn id)
          rel-eids (distinct (concat src-eids tgt-eids))
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
  "Find fact-layer memories with no parent set.
   When namespace is provided, only returns facts in that namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        eids (if namespace
               (d/q '[:find [?e ...]
                      :in $ ?ns
                      :where
                      [?e :memory/layer :layer/fact]
                      [?e :memory/namespace ?ns]]
                    @conn namespace)
               (d/q '[:find [?e ...]
                      :where [?e :memory/layer :layer/fact]]
                    @conn))
        all-facts (pull-memories @conn eids)]
    (filterv #(nil? (:memory/parent %)) all-facts)))

(defn get-concepts
  "Fetch concept-layer memories, optionally scoped by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        eids (if namespace
               (d/q '[:find [?e ...]
                      :in $ ?ns
                      :where
                      [?e :memory/layer :layer/concept]
                      [?e :memory/namespace ?ns]]
                    @conn namespace)
               (d/q '[:find [?e ...]
                      :where [?e :memory/layer :layer/concept]]
                    @conn))]
    (pull-memories @conn eids)))

(defn get-domains
  "Fetch domain-layer memories, optionally scoped by namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        eids (if namespace
               (d/q '[:find [?e ...]
                      :in $ ?ns
                      :where
                      [?e :memory/layer :layer/domain]
                      [?e :memory/namespace ?ns]]
                    @conn namespace)
               (d/q '[:find [?e ...]
                      :where [?e :memory/layer :layer/domain]]
                    @conn))]
    (pull-memories @conn eids)))

(defn get-orphan-concepts
  "Find concept-layer memories with no parent set.
   When namespace is provided, only returns concepts in that namespace."
  [store & {:keys [namespace]}]
  (let [conn (:conn store)
        eids (if namespace
               (d/q '[:find [?e ...]
                      :in $ ?ns
                      :where
                      [?e :memory/layer :layer/concept]
                      [?e :memory/namespace ?ns]]
                    @conn namespace)
               (d/q '[:find [?e ...]
                      :where [?e :memory/layer :layer/concept]]
                    @conn))
        all-concepts (pull-memories @conn eids)]
    (filterv #(nil? (:memory/parent %)) all-concepts)))

(defn get-siblings
  "Fetch other children of the same parent, excluding the given memory."
  [store pid & {:keys [exclude-id limit] :or {limit 10}}]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :in $ ?pid
                    :where
                    [?p :memory/id ?pid]
                    [?e :memory/parent ?p]]
                  @conn pid)]
    (->> (pull-memories @conn eids)
         (remove #(= exclude-id (:memory/id %)))
         (take limit)
         vec)))

(defn get-summaries-for
  "Fetch summary-layer memories whose parent matches the given memory id."
  [store memory-id]
  (let [conn (:conn store)
        eids (d/q '[:find [?e ...]
                    :in $ ?pid
                    :where
                    [?p :memory/id ?pid]
                    [?e :memory/layer :layer/summary]
                    [?e :memory/parent ?p]]
                  @conn memory-id)]
    (pull-memories @conn eids)))

(defn get-memories-batch
  "Fetch multiple memories by UUID in a single query, with a minimal pull pattern."
  [store ids]
  (let [conn (:conn store)]
    (when (seq ids)
      (let [eids (d/q '[:find [?e ...]
                        :in $ [?id ...]
                        :where [?e :memory/id ?id]]
                      @conn (vec (set ids)))]
        (mapv #(d/pull @conn memory-pull-minimal %) eids)))))

(defn get-memories-batch-full
  "Fetch multiple memories by UUID in a single query, with full pull pattern."
  [store ids]
  (let [conn (:conn store)]
    (when (seq ids)
      (let [eids (d/q '[:find [?e ...]
                        :in $ [?id ...]
                        :where [?e :memory/id ?id]]
                      @conn (vec (set ids)))]
        (pull-memories @conn eids)))))

(defn get-memories-batch-at
  "Fetch multiple memories by UUID from a point-in-time snapshot.
   If as-of-date is nil, queries the current db value. Full pull pattern."
  [store ids as-of-date]
  (let [conn (:conn store)
        db (if as-of-date
             (d/as-of @conn as-of-date)
             @conn)]
    (when (seq ids)
      (let [eids (d/q '[:find [?e ...]
                        :in $ [?id ...]
                        :where [?e :memory/id ?id]]
                      db (vec (set ids)))]
        (pull-memories db eids)))))

(defn get-summaries-for-batch
  "Fetch summary-layer memories for multiple parent IDs in a single query."
  [store parent-ids]
  (let [conn (:conn store)]
    (when (seq parent-ids)
      (let [eids (d/q '[:find [?e ...]
                        :in $ [?pid ...]
                        :where
                        [?p :memory/id ?pid]
                        [?e :memory/layer :layer/summary]
                        [?e :memory/parent ?p]]
                      @conn (vec (set parent-ids)))]
        (mapv #(d/pull @conn memory-pull-minimal %) eids)))))

(defn get-children-of-parents-batch
  "Fetch children for multiple parent IDs in a single query, with a minimal pull pattern."
  [store parent-ids]
  (let [conn (:conn store)]
    (when (seq parent-ids)
      (let [eids (d/q '[:find [?e ...]
                        :in $ [?pid ...]
                        :where
                        [?p :memory/id ?pid]
                        [?e :memory/parent ?p]]
                      @conn (vec (set parent-ids)))]
        (mapv #(d/pull @conn memory-pull-minimal %) eids)))))

(defn- relationship-exists?
  "Check if a relationship already exists between two memories (in either direction)
   with the same type. Uses two simple queries to avoid Datalog or-clause issues."
  [conn source-id target-id type]
  (let [forward (d/q '[:find ?e
                       :in $ ?s ?t ?type
                       :where
                       [?sm :memory/id ?s]
                       [?tm :memory/id ?t]
                       [?e :relationship/source ?sm]
                       [?e :relationship/target ?tm]
                       [?e :relationship/type ?type]]
                     @conn source-id target-id type)
        reverse (d/q '[:find ?e
                       :in $ ?s ?t ?type
                       :where
                       [?sm :memory/id ?s]
                       [?tm :memory/id ?t]
                       [?e :relationship/source ?sm]
                       [?e :relationship/target ?tm]
                       [?e :relationship/type ?type]]
                     @conn target-id source-id type)]
    (or (seq forward) (seq reverse))))

(defn insert-relationship!
  "Insert a relationship between two memories.
   Accepts source-id/target-id as UUIDs; stores as refs via lookup refs.
   Rejects self-references and duplicate pairs (same type, either direction).
   Returns the relationship id, or nil if skipped."
  [store {:keys [source-id target-id type confidence description]
          :or   {confidence 1.0}}]
  (let [conn (:conn store)]
    (when (and (not= source-id target-id)
               (not (relationship-exists? conn source-id target-id type)))
      (let [id (java.util.UUID/randomUUID)]
        (d/transact conn [(cond-> {:relationship/id         id
                                   :relationship/source     [:memory/id source-id]
                                   :relationship/target     [:memory/id target-id]
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
