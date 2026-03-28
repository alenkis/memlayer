(ns memlayer.api.ingest
  "HTTP handler for bulk ingestion."
  (:require [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.api.response :as response]
            [integrant.core :as ig]))

(defn handler
  "POST /api/v1/ingest handler.
   Expects body with :items (array of {content, source, namespace?}).
   Submits all items as a single batch through the retention flow."
  [flow]
  (fn [request]
    (let [body   (:body-params request)
          items  (:items body)
          result (retention-flow/submit! flow
                                         {:items     items
                                          :namespace (:namespace (first items))
                                          :source    (:source (first items))})]
      (response/flow-result->response
       result
       (fn [r]
         {:ingested   (count items)
          :memory-ids (mapv str (:memory-ids r))
          :decisions  (:decisions r)
          :usage      (:usage r)})))))

(defmethod ig/init-key :handler/ingest [_ {:keys [flow]}]
  (handler flow))
