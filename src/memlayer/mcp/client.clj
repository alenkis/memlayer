(ns memlayer.mcp.client
  "HTTP client for forwarding MCP tool calls to the memlayer HTTP server.
   The MCP process is a thin client — all state lives in the server."
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [memlayer.json :as json]))

(def ^:private http-client
  (delay (hc/build-http-client {:connect-timeout 5000})))

(defn- api-url [base-url path]
  (str base-url path))

(defn- post! [base-url path body]
  (let [url  (api-url base-url path)
        resp (hc/request {:method       :post
                          :url          url
                          :body         (j/write-value-as-string body json/mapper)
                          :headers      {"content-type" "application/json"
                                         "accept"       "application/json"}
                          :http-client  @http-client
                          :as           :string})]
    (when (:body resp)
      (j/read-value (:body resp) json/mapper))))

(defn health-check
  "Check if the memlayer server is running. Returns true if healthy."
  [base-url]
  (try
    (let [resp (hc/request {:method       :get
                            :url          (api-url base-url "/health")
                            :headers      {"accept" "application/json"}
                            :http-client  @http-client
                            :as           :string})]
      (= 200 (:status resp)))
    (catch Exception _
      false)))

;; -- Tool call forwarders --

(defn retain! [base-url {:keys [content source namespace]}]
  (post! base-url "/api/v1/retain"
         {:content content :source source :namespace namespace}))

(defn batch-retain! [base-url {:keys [namespace items]}]
  (post! base-url "/api/v1/retain/batch"
         {:namespace namespace :items items}))

(defn recall! [base-url {:keys [query namespace limit as-of layer expand-graph]}]
  (post! base-url "/api/v1/recall"
         (cond-> {:query query}
           namespace    (assoc :namespace namespace)
           limit        (assoc :limit limit)
           as-of        (assoc :as-of as-of)
           layer        (assoc :layer layer)
           expand-graph (assoc :expand-graph expand-graph))))

(defn forget! [base-url {:keys [memory-id]}]
  (post! base-url "/api/v1/forget" {:memory-id memory-id}))

(defn reflect! [base-url {:keys [dry-run namespace phases]}]
  (post! base-url "/api/v1/reflect"
         (cond-> {}
           (some? dry-run) (assoc :dry-run dry-run)
           namespace       (assoc :namespace namespace)
           phases          (assoc :phases phases))))
