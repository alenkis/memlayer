(ns memlayer.persistence.protocols
  "Persistence protocols for entity storage and vector search.

   These define the storage contract — implementations handle connection
   management, indexing, and durability. Domain logic (memory CRUD,
   relationship queries, graph traversal) lives in higher-level namespaces.")

(defprotocol EntityStore
  (q [store query args]
    "Execute a Datalog query against the current database value.
     query: a Datalog query (map or vector form).
     args: additional query inputs beyond the implicit database.
     Returns the query result — shape depends on the :find clause
     (set of tuples, scalar, collection, or single tuple).")
  (pull [store selector eid]
    "Pull an entity by its entity id using a pull pattern.
     selector: pull pattern (e.g. '[*] or '[:memory/id :memory/content]).
     eid: entity id (numeric) or lookup ref (e.g. [:memory/id uuid]).
     Returns an entity map, or nil if the entity does not exist.")
  (transact! [store tx-data]
    "Apply a transaction to the store.
     tx-data: a collection of transaction forms (entity maps, add/retract
     vectors, or transaction functions).
     Returns a transaction report with :db-before, :db-after, :tx-data.")
  (history [store]
    "Return the history database — an immutable value containing all past
     assertions and retractions. Used for temporal queries (e.g. finding
     when an entity was created or what changed over time).")
  (db-value [store]
    "Return the current immutable database snapshot.
     This is the point-in-time value used for consistent reads."))

(defprotocol VectorStore
  (search [store query-vec k]
    "Search for the k nearest neighbors of query-vec.
     query-vec: a float array or seq of floats (the query embedding).
     k: maximum number of results to return.
     Returns a seq of {:id <string>, :distance <double>}.")
  (upsert! [store key embedding]
    "Insert or replace a vector for the given key.
     key: a string identifier (typically a stringified memory UUID).
     embedding: a float array or seq of floats.
     Returns the updated store value (for use with swap!).")
  (remove! [store key]
    "Remove a vector by its key.
     key: a string identifier.
     Returns the updated store value (for use with swap!).")
  (clear! [store]
    "Remove all vectors, returning a fresh empty store."))
