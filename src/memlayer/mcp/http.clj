(ns memlayer.mcp.http
  "MCP Streamable HTTP transport adapter.
   Translates HTTP requests into MCP dispatch calls over JSON-RPC 2.0.

   When mounted inside the reitit router, muuntaja parses the JSON body into
   :body-params and serializes the response body back to JSON. The handler
   works with Clojure maps in both directions.

   For direct use (tests, non-muuntaja contexts), the handler falls back to
   reading and writing raw JSON via memlayer.mcp.protocol."
  (:require [integrant.core :as ig]
            [memlayer.mcp.server :as mcp-server]
            [memlayer.mcp.protocol :as proto]
            [clojure.string :as str]
            [clojure.tools.logging :as log])
  (:import [java.util UUID]))

;; -- Session management --

(defn- create-session! [sessions]
  (let [sid (str (UUID/randomUUID))]
    (swap! sessions assoc sid {:created-at (System/currentTimeMillis)})
    sid))

(defn- valid-session? [sessions sid]
  (contains? @sessions sid))

(defn- destroy-session! [sessions sid]
  (swap! sessions dissoc sid))

;; -- Request helpers --

(defn- extract-message
  "Extract the JSON-RPC message from a Ring request.
   Prefers :body-params (muuntaja-parsed), falls back to raw :body."
  [request]
  (or (:body-params request)
      (when-let [body (:body request)]
        (let [raw (if (string? body) body (slurp body))]
          (when-not (str/blank? raw)
            (proto/parse-message raw))))))

(defn- wants-sse? [request]
  (when-let [accept (get-in request [:headers "accept"])]
    (.contains ^String accept "text/event-stream")))

;; -- Response helpers --

(defn- mcp-response
  "Build a Ring response from an MCP dispatch result.
   For SSE clients, wraps in event-stream format.
   For JSON clients, returns the map body (muuntaja serializes)."
  [request status result & {:keys [headers]}]
  (if (wants-sse? request)
    {:status  status
     :headers (merge {"Content-Type"  "text/event-stream"
                      "Cache-Control" "no-cache"} headers)
     :body    (str "event: message\ndata: " (proto/encode result) "\n\n")}
    {:status  status
     :headers (or headers {})
     :body    result}))

(defn- error-ring-response [status error-map]
  {:status  status
   :headers {}
   :body    error-map})

;; -- Handlers --

(defn- handle-post [sessions flow deps request]
  (let [message (extract-message request)
        ctx     {:flow flow :deps deps :active-namespace nil}]
    (cond
      ;; Parse error
      (or (nil? message) (:error message))
      (error-ring-response 400
                           (proto/error-response nil proto/parse-error
                                                 (or (get-in message [:error :message])
                                                     "Could not parse request body")))

      ;; Initialize — no session required
      (= "initialize" (:method message))
      (let [response (mcp-server/dispatch message ctx)
            sid      (create-session! sessions)]
        (log/info "MCP session created:" sid)
        (mcp-response request 200 response
                      :headers {"Mcp-Session-Id" sid}))

      ;; Notification — no response body
      (nil? (:id message))
      (let [sid (get-in request [:headers "mcp-session-id"])]
        (when (and sid (valid-session? sessions sid))
          (mcp-server/dispatch message ctx))
        {:status 202 :headers {} :body ""})

      ;; Regular request — session required
      :else
      (let [sid (get-in request [:headers "mcp-session-id"])]
        (if-not (valid-session? sessions sid)
          (error-ring-response 400
                               (proto/error-response (:id message) proto/invalid-request
                                                     "Missing or invalid Mcp-Session-Id header"))
          (let [response (mcp-server/dispatch message ctx)]
            (mcp-response request 200 response)))))))

(defn- handle-delete [sessions request]
  (let [sid (get-in request [:headers "mcp-session-id"])]
    (if (and sid (valid-session? sessions sid))
      (do (destroy-session! sessions sid)
          (log/info "MCP session destroyed:" sid)
          {:status 200 :headers {} :body ""})
      (error-ring-response 404
                           (proto/error-response nil proto/invalid-request
                                                 "Session not found")))))

;; -- Public API --

(defn create-handler
  "Create MCP HTTP transport handlers.
   Returns {:post ring-handler :delete ring-handler}."
  [flow deps]
  (let [sessions (atom {})]
    {:post   (fn [request] (handle-post sessions flow deps request))
     :delete (fn [request] (handle-delete sessions request))}))

;; -- Integrant --

(defmethod ig/init-key :handler/mcp [_ {:keys [flow deps]}]
  (log/info "Creating MCP HTTP handler")
  (let [sessions (atom {})]
    {:post     (fn [request] (handle-post sessions flow deps request))
     :delete   (fn [request] (handle-delete sessions request))
     :sessions sessions}))

(defmethod ig/halt-key! :handler/mcp [_ {:keys [sessions]}]
  (let [n (count @sessions)]
    (when (pos? n)
      (log/info "Clearing" n "MCP sessions"))
    (reset! sessions {})))
