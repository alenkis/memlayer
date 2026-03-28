(ns memlayer.util.retry
  "Retry with exponential backoff and full jitter for transient failures."
  (:require [clojure.tools.logging :as log]))

(def ^:private retryable-status? #{429 500 502 503 504})

(defn- retryable-exception?
  "Check if an exception represents a retryable failure."
  [e]
  (or
   (instance? java.net.SocketTimeoutException e)
   (instance? java.net.ConnectException e)
   (and (instance? clojure.lang.ExceptionInfo e)
        (some-> (ex-data e) :status retryable-status?))))

(defn with-retry
  "Execute f with exponential backoff + full jitter on retryable failures.
   Options:
     :max-retries   - max retry attempts (default 3)
     :base-delay-ms - base delay in ms (default 500)
     :max-delay-ms  - maximum delay cap in ms (default 10000)
     :label         - descriptive label for logging (default \"operation\")"
  ([f] (with-retry f {}))
  ([f {:keys [max-retries base-delay-ms max-delay-ms label]
       :or   {max-retries 3 base-delay-ms 500 max-delay-ms 10000 label "operation"}}]
   (loop [attempt 0]
     (let [result (try
                    {:value (f)}
                    (catch Exception e
                      (if (and (< attempt max-retries) (retryable-exception? e))
                        (let [exp-delay (min (* base-delay-ms (long (Math/pow 2 attempt))) max-delay-ms)
                              jittered  (long (* (Math/random) exp-delay))]
                          (log/warn (str "Retryable error in " label
                                         " (attempt " (inc attempt) "/" (inc max-retries) ")"
                                         ", retrying in " jittered "ms")
                                    {:status (:status (ex-data e))
                                     :error  (.getMessage e)})
                          (Thread/sleep jittered)
                          ::retry)
                        (throw e))))]
       (if (= result ::retry)
         (recur (inc attempt))
         (:value result))))))
