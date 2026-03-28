(ns memlayer.api.stats
  "API handlers for system statistics (dashboard)."
  (:require [clojure.set :as set]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.proximum :as prox]
            [integrant.core :as ig]))

(defn memory-stats-handler
  "GET /api/v1/stats/memories - Memory counts and layer distribution.
   Optional query param: ?namespace=foo to scope stats to a single namespace."
  [{:keys [db]}]
  (fn [request]
    (let [namespace    (get-in request [:query-params "namespace"])
          ns-total     (dh/count-all-memories db :namespace namespace)
          global-total (dh/count-all-memories db)
          namespaces   (dh/get-distinct-namespaces db)
          by-layer     (dh/count-by-layer db :namespace namespace)]
      {:status 200
       :body   {:namespace-total ns-total
                :global-total    global-total
                :total-count     ns-total
                :active-count    ns-total
                :by-layer        (or by-layer {})
                :namespace-count (count namespaces)}})))

(defn consistency-handler
  "GET /api/v1/stats/consistency - Vector index integrity check."
  [{:keys [db vector-index]}]
  (fn [_request]
    (let [all-ids    (set (map str (dh/get-all-memory-ids db)))
          vector-ids (when vector-index
                       (set (prox/stored-keys @vector-index)))
          missing-v  (when vector-ids
                       (set/difference all-ids vector-ids))
          orphan-v   (when vector-ids
                       (set/difference vector-ids all-ids))]
      {:status 200
       :body   {:missing-vectors (count missing-v)
                :orphan-vectors  (count orphan-v)
                :vector-count    (if vector-index (count (prox/stored-keys @vector-index)) 0)}})))

(defmethod ig/init-key :handler/stats [_ {:keys [deps]}]
  {:memory-stats (memory-stats-handler (select-keys deps [:db]))
   :consistency  (consistency-handler (select-keys deps [:db :vector-index]))})
