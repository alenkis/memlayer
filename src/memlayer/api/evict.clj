(ns memlayer.api.evict
  "HTTP handler for the evict endpoint (GDPR purge)."
  (:require [memlayer.operations.forget :as forget]
            [integrant.core :as ig]))

(defn handler
  "POST /api/v1/evict handler.
   Expects body with :memory-id (string UUID)."
  [deps]
  (fn [request]
    (if-let [memory-id (some-> (:body-params request) :memory-id parse-uuid)]
      {:status 200 :body (forget/evict! deps {:memory-id memory-id})}
      {:status 400 :body {:error "Missing or invalid memory_id"}})))

(defmethod ig/init-key :handler/evict [_ {:keys [deps]}]
  (handler deps))
