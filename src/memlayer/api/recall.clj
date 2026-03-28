(ns memlayer.api.recall
  "HTTP handler for the recall endpoint."
  (:require [memlayer.operations.recall :as recall]
            [integrant.core :as ig]))

(defn handler
  "POST /api/v1/recall handler.
   Expects deps injected via closure."
  [deps]
  (fn [request]
    (let [body   (:body-params request)
          params {:query        (:query body)
                  :namespace    (:namespace body)
                  :limit        (:limit body)
                  :as-of        (:as-of body)
                  :expand-graph (:expand-graph body)
                  :layer        (:layer body)}
          result (recall/recall! deps params)]
      {:status 200
       :body   result})))

(defmethod ig/init-key :handler/recall [_ {:keys [deps]}]
  (handler deps))
