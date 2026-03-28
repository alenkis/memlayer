(ns memlayer.middleware.trace
  "Ring middleware that assigns a trace ID to each request and sets it in
   logback MDC so every log line within that request includes the trace ID."
  (:require [clojure.tools.logging :as log])
  (:import (org.slf4j MDC)
           (java.util UUID)))

(defn wrap-trace-id
  "Ring middleware that:
   1. Reads or generates a trace-id for the request
   2. Sets traceId, method, uri, and userId in logback MDC
   3. Logs the request/response summary
   4. Returns the trace-id in the X-Trace-Id response header
   5. Clears MDC after the request"
  [handler]
  (fn [request]
    (let [trace-id (or (get-in request [:headers "x-trace-id"])
                       (str (UUID/randomUUID)))
          method   (some-> (:request-method request) name .toUpperCase)
          uri      (:uri request)
          user-id  (get-in request [:user-context :user-id] "anonymous")]
      (try
        (MDC/put "traceId" trace-id)
        (MDC/put "method" (or method ""))
        (MDC/put "uri" (or uri ""))
        (MDC/put "userId" user-id)
        (log/info "Request started")
        (let [start    (System/nanoTime)
              request  (assoc-in request [:headers "x-trace-id"] trace-id)
              response (handler request)
              elapsed  (/ (- (System/nanoTime) start) 1e6)]
          (log/info (str "Request completed"
                         " status=" (:status response)
                         " elapsed=" (format "%.1fms" elapsed)))
          (assoc-in response [:headers "X-Trace-Id"] trace-id))
        (finally
          (MDC/clear))))))
