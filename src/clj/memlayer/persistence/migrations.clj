(ns memlayer.persistence.migrations
  "Database migration framework for Datahike.
   Migrations are numbered, idempotent, and tracked in the database."
  (:require [datahike.api :as d]
            [memlayer.schema :as schema]
            [clojure.tools.logging :as log])
  (:import [java.util Date]))

(defn- ->conn
  "Extract raw datahike connection from a store or passthrough if already raw."
  [store-or-conn]
  (or (:conn store-or-conn) store-or-conn))

;; -- Migration tracking schema --

(def ^:private migration-schema
  [{:db/ident       :migration/id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :migration/applied-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn- applied-migration-ids
  "Return the set of migration IDs that have already been applied."
  [conn]
  (let [conn (->conn conn)]
    (set (d/q '[:find [?id ...]
                :where [?e :migration/id ?id]]
              @conn))))

(defn- record-migration!
  "Record that a migration has been applied."
  [conn migration-id]
  (let [conn (->conn conn)]
    (d/transact conn [{:migration/id         migration-id
                       :migration/applied-at (Date.)}])))

;; -- Migration 001: Backfill namespace --

(defn- migrate-001-backfill-namespace!
  "Backfill :memory/namespace \"default\" on all memories that lack the attribute."
  [conn]
  (let [conn (->conn conn)
        eids-without-ns (d/q '[:find [?e ...]
                               :where
                               [?e :memory/id _]
                               (not [?e :memory/namespace _])]
                             @conn)]
    (when (seq eids-without-ns)
      (log/info (str "Migration 001: Backfilling " (count eids-without-ns)
                     " memories with namespace \"" schema/default-namespace "\""))
      (d/transact conn (mapv (fn [eid]
                               {:db/id            eid
                                :memory/namespace schema/default-namespace})
                             eids-without-ns)))
    (count eids-without-ns)))

;; -- Migration registry --

(def ^:private migrations
  [{:id "001-backfill-namespace" :fn migrate-001-backfill-namespace!}])

;; -- Public API --

(defn run-migrations!
  "Run all pending migrations. Idempotent — already-applied migrations are skipped."
  [conn]
  (let [conn (->conn conn)]
    (d/transact conn migration-schema)
    (let [applied (applied-migration-ids conn)]
      (doseq [{:keys [id fn]} migrations]
        (when-not (applied id)
          (log/info "Running migration:" id)
          (fn conn)
          (record-migration! conn id)
          (log/info "Migration complete:" id))))))
