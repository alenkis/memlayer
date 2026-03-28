(ns memlayer.api.retain
  "HTTP handler for the retain endpoint."
  (:require [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.api.response :as response]
            [integrant.core :as ig]))

(defn handler
  "POST /api/v1/retain handler.
   Submits a single item to the retention flow (batch-of-1)."
  [flow]
  (fn [request]
    (let [body   (:body-params request)
          result (retention-flow/submit! flow
                                         {:items     [{:content (:content body)
                                                       :source  (:source body)}]
                                          :namespace (:namespace body)
                                          :source    (:source body)})]
      (response/flow-result->response
       result
       (fn [r]
         (cond-> {:memory-ids   (mapv str (:memory-ids r))
                  :operation-id (:operation-id r)
                  :decisions    (mapv (fn [d]
                                        (cond-> {:type    (:type d)
                                                 :content (:content d)}
                                          (:memory-id d) (assoc :memory-id (str (:memory-id d)))))
                                      (:decisions r))}
           (:usage r) (assoc :usage (:usage r))))))))

(defmethod ig/init-key :handler/retain [_ {:keys [flow]}]
  (handler flow))
