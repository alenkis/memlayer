(ns memlayer.operations.forget
  "Forget operation: retract memories and remove from search index.
   Retracted data is preserved in datahike history. Use evict! for GDPR removal."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [clojure.tools.logging :as log]))

(defn forget!
  "Retract a memory by its memory-id: remove from current DB and vector index.
   The memory is preserved in datahike history.

   Args:
     deps   - map with :db, :vector-index
     params - map with :memory-id (UUID)

   Returns:
     {:memories-removed N :relationships-removed N}"
  [{:keys [db vector-index]} {:keys [memory-id]}]
  (log/info "Forget: retracting memory" memory-id)
  (let [{:keys [memories-retracted relationships-deleted]} (dh/forget-memory! db memory-id)]
    (when (pos? memories-retracted)
      (swap! vector-index (fn [store] (protocols/remove! store (str memory-id))))
      (log/info "Forget complete" {:memory-id memory-id}))
    {:memories-removed      memories-retracted
     :relationships-removed relationships-deleted}))

(defn evict!
  "Evict a memory permanently: remove from current DB, history, and vector index.
   For GDPR compliance — data cannot be recovered.

   Args:
     deps   - map with :db, :vector-index
     params - map with :memory-id (UUID)

   Returns:
     {:memories-evicted N :relationships-removed N}"
  [{:keys [db vector-index]} {:keys [memory-id]}]
  (log/info "Evict: purging memory" memory-id)
  (if-let [_mem (dh/get-memory db memory-id)]
    (let [rel-count (dh/delete-relationships-for-memory! db memory-id)
          _         (dh/evict-memory! db memory-id)
          _         (swap! vector-index
                           (fn [store] (protocols/remove! store (str memory-id))))]
      (log/info "Evict complete" {:memory-id memory-id})
      {:memories-evicted     1
       :relationships-removed rel-count})
    (do
      (log/info "Evict: memory not found" {:memory-id memory-id})
      {:memories-evicted     0
       :relationships-removed 0})))
