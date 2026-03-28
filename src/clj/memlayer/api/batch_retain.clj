(ns memlayer.api.batch-retain
  "HTTP handler for batch retain."
  (:require [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.operations.reflect :as reflect]
            [memlayer.api.response :as response]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn handler
  "POST /api/v1/retain/batch handler.
   Expects body with :namespace and :items (array of {content, source?})."
  [flow deps]
  (fn [request]
    (let [body   (:body-params request)
          result (retention-flow/submit! flow
                                         {:items     (:items body)
                                          :namespace (:namespace body)})]
      (response/flow-result->response
       result
       (fn [r]
         (let [reflect-result (when (seq (:decisions r))
                                (try
                                  (reflect/reflect! deps {:dry-run false :namespace (:namespace body)})
                                  (catch Exception e
                                    (log/warn "Post-batch reflect failed:" (.getMessage e))
                                    nil)))]
           (cond-> {:memory-ids (mapv str (:memory-ids r))
                    :decisions  (:decisions r)
                    :usage      (:usage r)}
             reflect-result (assoc :reflect reflect-result))))))))

(defmethod ig/init-key :handler/batch-retain [_ {:keys [flow deps]}]
  (handler flow deps))
