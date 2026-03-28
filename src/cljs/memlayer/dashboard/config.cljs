(ns memlayer.dashboard.config)

(goog-define dev-api-port "8080")

(def server-base
  (if ^boolean goog.DEBUG
    (str "http://localhost:" dev-api-port)
    ""))

(def api-base (str server-base "/api/v1"))

(def api-ws-base
  (if ^boolean goog.DEBUG
    (str "ws://localhost:" dev-api-port "/api/v1")
    "wss://api.memlayer.dev/api/v1"))

(defn api-url [path]
  (str api-base path))

(defn server-url [path]
  (str server-base path))
