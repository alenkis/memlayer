(ns memlayer.mcp.protocol
  "JSON-RPC 2.0 message types for MCP protocol."
  (:require [jsonista.core :as j]
            [memlayer.json :as json]))

;; -- Parsing --

(defn parse-message
  "Parse a JSON-RPC 2.0 message string into a map.
   Returns {:jsonrpc \"2.0\" :method \"...\" :params {...} :id ...}"
  [text]
  (try
    (j/read-value text json/mapper)
    (catch Exception e
      {:error {:code -32700 :message (str "Parse error: " (.getMessage e))}})))

;; -- Response builders --

(defn success-response
  "Build a JSON-RPC 2.0 success response."
  [id result]
  {:jsonrpc "2.0"
   :id      id
   :result  result})

(defn error-response
  "Build a JSON-RPC 2.0 error response."
  ([id code message]
   {:jsonrpc "2.0"
    :id      id
    :error   {:code code :message message}})
  ([id code message data]
   {:jsonrpc "2.0"
    :id      id
    :error   {:code code :message message :data data}}))

(defn notification-response
  "Build a JSON-RPC 2.0 notification (no id)."
  [method params]
  {:jsonrpc "2.0"
   :method  method
   :params  params})

;; -- Serialization --

(defn encode
  "Encode a response map to a JSON string."
  [response]
  (j/write-value-as-string response json/mapper))

;; -- Standard error codes --

(def parse-error      -32700)
(def invalid-request  -32600)
(def method-not-found -32601)
(def invalid-params   -32602)
(def internal-error   -32603)
