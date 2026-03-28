(ns memlayer.middleware.rate-limit
  "Ring middleware for per-user request rate limiting.
   Supports DynamoDB backend for distributed rate limiting across servers,
   with in-memory fallback for single-server and development use."
  (:require [cognitect.aws.client.api :as aws]
            [clojure.tools.logging :as log]))

(defn- create-memory-limiter
  "In-memory fixed-window counter. Suitable for single-server deployments."
  [{:keys [max-requests window-ms]}]
  (let [buckets    (atom {})
        call-count (atom 0)]
    (fn [user-id]
      (let [now (System/currentTimeMillis)]
        (when (zero? (mod (swap! call-count inc) 1000))
          (swap! buckets
                 (fn [m]
                   (into {} (filter (fn [[_ v]]
                                      (< (- now (:window-start v)) (* 2 window-ms)))
                                    m)))))
        (let [updated (swap! buckets
                             (fn [m]
                               (let [{:keys [count window-start]
                                      :or   {count 0 window-start now}}
                                     (get m user-id)
                                     new-window? (> (- now window-start) window-ms)
                                     ws (if new-window? now window-start)
                                     c  (if new-window? 1 (inc count))]
                                 (assoc m user-id {:count c :window-start ws}))))
              {:keys [count window-start]} (get updated user-id)]
          {:allowed?  (<= count max-requests)
           :remaining (max 0 (- max-requests count))
           :reset-at  (+ window-start window-ms)})))))

(defn- create-dynamodb-limiter
  "DynamoDB-backed fixed-window counter using atomic ADD.
   Each window creates a key {user-id}#{window-number}, auto-expired by TTL.
   Falls open (allows request) if DynamoDB is unreachable."
  [{:keys [max-requests window-ms ddb-client table-name]}]
  (fn [user-id]
    (let [now      (System/currentTimeMillis)
          window   (quot now window-ms)
          pk       (str user-id "#" window)
          ttl-secs (+ (quot now 1000) (quot (* 2 window-ms) 1000))
          reset-at (* (inc window) window-ms)]
      (try
        (let [result (aws/invoke ddb-client
                                 {:op      :UpdateItem
                                  :request {:TableName                 table-name
                                            :Key                       {"pk" {:S pk}}
                                            :UpdateExpression          "ADD #c :inc SET #t = if_not_exists(#t, :ttl)"
                                            :ExpressionAttributeNames  {"#c" "request_count"
                                                                        "#t" "ttl"}
                                            :ExpressionAttributeValues {":inc" {:N "1"}
                                                                        ":ttl" {:N (str ttl-secs)}}
                                            :ReturnValues              "ALL_NEW"}})]
          (if (contains? result :cognitect.anomalies/category)
            (do (log/warn "DynamoDB rate limit failed, allowing request:" result)
                {:allowed? true :remaining max-requests :reset-at reset-at})
            (let [cnt (Long/parseLong (get-in result [:Attributes "request_count" :N]))]
              {:allowed?  (<= cnt max-requests)
               :remaining (max 0 (- max-requests cnt))
               :reset-at  reset-at})))
        (catch Exception e
          (log/warn e "DynamoDB rate limit failed, allowing request")
          {:allowed? true :remaining max-requests :reset-at reset-at})))))

(defn create-limiter
  "Create a rate limiter function (fn [user-id] -> {:allowed? :remaining :reset-at}).
   Uses DynamoDB if :ddb-client is provided, otherwise in-memory."
  [opts]
  (if (:ddb-client opts)
    (do (log/info "Using DynamoDB rate limiter" {:table (:table-name opts)})
        (create-dynamodb-limiter opts))
    (do (log/info "Using in-memory rate limiter (single-server mode)")
        (create-memory-limiter opts))))

(defn wrap-rate-limit
  "Reitit middleware that rate-limits requests per authenticated user.
   Expects :user-context to be set on the request (run after auth middleware).
   Returns 429 Too Many Requests when the limit is exceeded.
   Adds X-RateLimit-Remaining and X-RateLimit-Reset headers to all responses."
  [handler {:keys [limiter enabled?] :or {enabled? true}}]
  (if-not enabled?
    handler
    (fn [request]
      (let [user-id (get-in request [:user-context :user-id] "anonymous")
            {:keys [allowed? remaining reset-at]} (limiter user-id)
            reset-secs (str (quot reset-at 1000))]
        (if allowed?
          (-> (handler request)
              (assoc-in [:headers "X-RateLimit-Remaining"] (str remaining))
              (assoc-in [:headers "X-RateLimit-Reset"] reset-secs))
          (do
            (log/warn "Rate limit exceeded for user" user-id)
            {:status  429
             :headers {"X-RateLimit-Remaining" "0"
                       "X-RateLimit-Reset"     reset-secs
                       "Retry-After"           (str (max 1 (quot (- reset-at (System/currentTimeMillis)) 1000)))}
             :body    {:error   "Too Many Requests"
                       :message "Rate limit exceeded. Please retry later."}}))))))
