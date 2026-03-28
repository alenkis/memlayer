(ns memlayer.api.namespaces
  "API handler for namespace management (dashboard)."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.util.pagination :as pg]
            [integrant.core :as ig]))

(defn list-handler
  "GET /api/v1/namespaces - List distinct namespaces with memory counts."
  [{:keys [db]}]
  (fn [request]
    (let [{:keys [limit offset]} (pg/parse-pagination (:query-params request))
          all-ns   (dh/get-distinct-namespaces db)
          total    (count all-ns)
          page     (->> all-ns (drop offset) (take limit))
          ns-infos (mapv (fn [ns-name]
                           {:name         ns-name
                            :memory-count (dh/count-memories-by-namespace db ns-name)})
                         page)]
      {:status 200
       :body   {:namespaces ns-infos
                :total      total
                :limit      limit
                :offset     offset}})))

(defmethod ig/init-key :handler/namespaces [_ {:keys [db]}]
  {:list (list-handler {:db db})})
