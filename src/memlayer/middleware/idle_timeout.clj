(ns memlayer.middleware.idle-timeout
  "Middleware that tracks API activity and shuts down the server
   after a configurable idle period."
  (:require [clojure.tools.logging :as log]))

(def ^:private default-idle-timeout-ms
  (* 30 60 1000)) ;; 30 minutes

(defonce ^:private last-activity-ms (atom (System/currentTimeMillis)))

(defn touch!
  "Record that activity just happened."
  []
  (reset! last-activity-ms (System/currentTimeMillis)))

(defn wrap-activity-tracking
  "Ring middleware that records the timestamp of each request."
  [handler]
  (fn [request]
    (touch!)
    (handler request)))

(defn start-idle-watcher!
  "Start a background thread that checks for idle timeout.
   Calls shutdown-fn when idle timeout is reached.
   Returns a function that stops the watcher."
  [shutdown-fn timeout-ms]
  (let [timeout (or timeout-ms default-idle-timeout-ms)
        running (atom true)
        thread  (Thread.
                 (fn []
                   (log/info "Idle watcher started, timeout:" (/ timeout 60000.0) "minutes")
                   (while @running
                     (Thread/sleep 60000) ;; check every minute
                     (let [idle-ms (- (System/currentTimeMillis) @last-activity-ms)]
                       (when (and @running (> idle-ms timeout))
                         (log/info "Server idle for" (/ idle-ms 60000.0)
                                   "minutes, shutting down")
                         (reset! running false)
                         (shutdown-fn)))))
                 "memlayer-idle-watcher")]
    (.setDaemon thread true)
    (.start thread)
    (fn [] (reset! running false))))
