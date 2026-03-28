(ns memlayer.api.admin
  "API handler for admin operations (reset, etc.)."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn reset-handler
  "POST /api/v1/admin/reset - Clear all data from the system."
  [{:keys [db vector-index]}]
  (fn [_request]
    (log/info "Admin reset: clearing all data")
    (let [mem-count (dh/delete-all-memories! db)
          rel-count (dh/delete-all-relationships! db)]
      (swap! vector-index protocols/clear!)
      (log/info "Admin reset complete" {:memories mem-count :relationships rel-count})
      {:status 200
       :body   {:status "reset"
                :memories-removed mem-count
                :relationships-removed rel-count}})))

(defmethod ig/init-key :handler/admin [_ {:keys [db vector-index]}]
  {:reset (reset-handler {:db           db
                          :vector-index vector-index})})
