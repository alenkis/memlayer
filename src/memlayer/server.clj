(ns memlayer.server
  "HTTP server using http-kit."
  (:require [org.httpkit.server :as http-kit]
            [clojure.tools.logging :as log]))

(defn start!
  "Start the HTTP server. Returns the stop function."
  [handler port]
  (log/info "Starting HTTP server on port" port)
  (http-kit/run-server handler {:port port :join? false}))

(defn stop!
  "Stop the HTTP server."
  [server]
  (when server
    (log/debug "Stopping HTTP server")
    (server :timeout 100)))
