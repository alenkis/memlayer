(ns memlayer.api.response
  "Shared HTTP response builders for retention flow results.")

(defn flow-result->response
  "Convert a retention flow result to an HTTP response.
   body-fn is called with the successful result to build the 201 body."
  [result body-fn]
  (if (nil? result)
    {:status 504
     :body   {:error "Processing timeout"}}
    {:status 201
     :body   (body-fn result)}))
