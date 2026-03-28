(ns memlayer.persistence.stratum
  "Stratum columnar analytics layer.
   Materializes datahike memory entities into columnar format for fast aggregate queries.

   Pattern: datahike (entities) → materialization → stratum (columns)
   Use datahike for entity lookups, stratum for analytics (counts, aggregations, etc)."
  (:require [stratum.api :as st]
            [datahike.api :as d]
            [clojure.tools.logging :as log]))

;; -- Materialization --

(defn- memories->columns
  "Denormalize datahike memory entities into stratum-compatible column maps."
  [memories]
  (when (seq memories)
    (let [_n (count memories)]
      {:id         (into-array String (map (comp str :memory/id) memories))
       :content    (into-array String (map :memory/content memories))
       :layer      (into-array String (map (comp name :memory/layer) memories))
       :importance (double-array (map (comp double :memory/importance) memories))
       :source     (into-array String (map #(or (:memory/source %) "") memories))
       :namespace  (into-array String (map #(or (:memory/namespace %) "") memories))})))

(defn materialize
  "Query all memories from datahike and materialize into a stratum dataset.
   Returns a stratum dataset or nil if no memories exist.
   Accepts a DatahikeEntityStore — extracts raw conn internally."
  [store]
  (log/debug "Materializing datahike memories into stratum")
  (let [conn (:conn store)
        memories (d/q '[:find [(pull ?e [*]) ...]
                        :where [?e :memory/id _]]
                      @conn)]
    (when (seq memories)
      (let [cols (memories->columns memories)]
        (st/make-dataset cols {:name "memories"})))))

;; -- Analytics queries --

(defn count-by-layer
  "Count memories grouped by semantic layer."
  [dataset]
  (when dataset
    (st/q {:from  dataset
           :group [:layer]
           :agg   [[:count]]})))

(defn count-by-namespace
  "Count memories grouped by namespace."
  [dataset]
  (when dataset
    (st/q {:from  dataset
           :group [:namespace]
           :agg   [[:count]]})))

(defn avg-importance-by-layer
  "Average importance score grouped by layer."
  [dataset]
  (when dataset
    (st/q {:from  dataset
           :group [:layer]
           :agg   [[:avg :importance]
                   [:count]]})))

(defn count-by-source
  "Count memories grouped by source."
  [dataset]
  (when dataset
    (st/q {:from  dataset
           :group [:source]
           :agg   [[:count]]})))

(defn query-sql
  "Run an arbitrary SQL query against the memories dataset."
  [dataset sql]
  (when dataset
    (st/q sql {"memories" dataset})))

;; -- Auto-sync listener --

(defn install-sync-listener!
  "Install a datahike transaction listener that auto-materializes
   memories into stratum after each transaction.

   Returns an atom holding the current stratum dataset.
   Deref the atom to get the latest dataset for queries.
   Accepts a DatahikeEntityStore — extracts raw conn internally."
  [store]
  (let [conn (:conn store)
        dataset-atom (atom (materialize store))]
    (d/listen conn :stratum-sync
              (fn [{:keys [db-after]}]
                (try
                  (let [memories (d/q '[:find [(pull ?e [*]) ...]
                                        :where [?e :memory/id _]]
                                      db-after)
                        cols     (memories->columns memories)]
                    (when cols
                      (reset! dataset-atom (st/make-dataset cols {:name "memories"}))))
                  (catch Exception e
                    (log/error e "Failed to sync stratum dataset")))))
    dataset-atom))

(defn remove-sync-listener!
  "Remove the stratum auto-sync listener from datahike.
   Accepts a DatahikeEntityStore — extracts raw conn internally."
  [store]
  (d/unlisten (:conn store) :stratum-sync))
