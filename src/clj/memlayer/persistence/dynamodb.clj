(ns memlayer.persistence.dynamodb
  "DynamoDB client for distributed rate limiting."
  (:require [cognitect.aws.client.api :as aws]
            [clojure.tools.logging :as log]))

(defn create-client
  "Create a DynamoDB client. When :endpoint is set, connects to that URL
   (e.g. DynamoDB Local at http://localhost:8000). Otherwise uses default
   AWS credential resolution (IAM role, env vars, etc.)."
  [{:keys [endpoint region]}]
  (let [opts (cond-> {:api :dynamodb}
               region   (assoc :region region)
               endpoint (assoc :endpoint-override
                               (let [uri (java.net.URI. endpoint)]
                                 {:protocol (.getScheme uri)
                                  :hostname (.getHost uri)
                                  :port     (.getPort uri)})))]
    (log/info "Creating DynamoDB client"
              (if endpoint (str "endpoint=" endpoint) "AWS default"))
    (aws/client opts)))

(defn ensure-table!
  "Create the rate limits table if it doesn't exist.
   Intended for local development with DynamoDB Local."
  [client table-name]
  (let [result (aws/invoke client
                           {:op      :CreateTable
                            :request {:TableName             table-name
                                      :KeySchema             [{:AttributeName "pk"
                                                               :KeyType       "HASH"}]
                                      :AttributeDefinitions  [{:AttributeName "pk"
                                                               :AttributeType "S"}]
                                      :BillingMode           "PAY_PER_REQUEST"}})]
    (if (:__type result)
      (when-not (re-find #"ResourceInUseException" (str (:__type result)))
        (log/warn "Failed to create DynamoDB table:" result))
      (do
        (log/info "Created DynamoDB table:" table-name)
        (aws/invoke client
                    {:op      :UpdateTimeToLive
                     :request {:TableName                table-name
                               :TimeToLiveSpecification  {:Enabled       true
                                                          :AttributeName "ttl"}}})))))

(defn stop-client [client]
  (aws/stop client))
