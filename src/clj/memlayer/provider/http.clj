(ns memlayer.provider.http
  "Shared HTTP transport for LLM provider APIs."
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [memlayer.json :as json]
            [memlayer.tuning :as tuning]
            [memlayer.util.retry :as retry]))

(def request-timeout-ms tuning/provider-request-timeout-ms)
(def connect-timeout-ms tuning/provider-connect-timeout-ms)

(defn provider-request!
  "Send a POST request to an LLM provider with retry, auth, and JSON parsing.
   Returns the parsed response body."
  [{:keys [url api-key http-client body label]}]
  (retry/with-retry
    (fn []
      (let [resp (hc/request {:method      :post
                              :url         url
                              :headers     {"Authorization" (str "Bearer " api-key)
                                            "Content-Type"  "application/json"}
                              :body        (j/write-value-as-string body json/mapper)
                              :http-client http-client
                              :as          :string
                              :timeout     request-timeout-ms})]
        (when (not= 200 (:status resp))
          (throw (ex-info (str label " API error")
                          {:status (:status resp) :body (:body resp)})))
        (j/read-value (:body resp) json/mapper)))
    {:label label}))

(defn build-client
  "Build an HTTP client with the standard connect timeout."
  []
  (hc/build-http-client {:connect-timeout connect-timeout-ms}))
