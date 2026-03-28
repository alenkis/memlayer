(ns memlayer.integration.client
  "HTTP client for integration tests — wraps all API endpoints."
  (:refer-clojure :exclude [reset!])
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [memlayer.integration.test-system :as ts]))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- url [path]
  (str (ts/base-url) path))

(defn- parse-json-body [resp]
  (when (:body resp)
    (try
      (if (string? (:body resp))
        (j/read-value (:body resp) mapper)
        (:body resp))
      (catch Exception _
        (:body resp)))))

(defn- get-request [path & {:keys [query-params]}]
  (let [resp (hc/get (url path)
                     {:as           :string
                      :query-params query-params
                      :throw-exceptions false})]
    {:status (:status resp)
     :body   (parse-json-body resp)}))

(defn- post-request [path body]
  (let [resp (hc/post (url path)
                      {:as           :string
                       :content-type :json
                       :body         (j/write-value-as-string body)
                       :throw-exceptions false})]
    {:status (:status resp)
     :body   (parse-json-body resp)}))

(defn- delete-request [path]
  (let [resp (hc/delete (url path)
                        {:as :string
                         :throw-exceptions false})]
    {:status (:status resp)
     :body   (parse-json-body resp)}))

;; -- API methods --

(defn health []
  (get-request "/health"))

(defn reset! []
  (post-request "/api/v1/admin/reset" {}))

(defn retain!
  ([content source]
   (retain! content source nil))
  ([content source opts]
   (post-request "/api/v1/retain"
                 (merge {:content content :source source} opts))))

(defn recall!
  ([query]
   (recall! query nil))
  ([query opts]
   (post-request "/api/v1/recall"
                 (merge {:query query} opts))))

(defn forget! [memory-id]
  (post-request "/api/v1/forget"
                {:memory_id (str memory-id)}))

(defn reflect!
  ([] (reflect! nil))
  ([opts]
   (post-request "/api/v1/reflect" (or opts {}))))

(defn ingest! [items]
  (post-request "/api/v1/ingest" {:items items}))

(defn get-memory [id]
  (get-request (str "/api/v1/memories/" id)))

(defn list-memories
  ([] (list-memories nil))
  ([opts]
   (get-request "/api/v1/memories"
                :query-params (when opts
                                (cond-> {}
                                  (:limit opts) (assoc "limit" (:limit opts))
                                  (:layer opts) (assoc "layer" (:layer opts))
                                  (:namespace opts) (assoc "namespace" (:namespace opts)))))))

(defn delete-memory! [id]
  (delete-request (str "/api/v1/memories/" id)))

(defn get-children [id]
  (get-request (str "/api/v1/memories/" id "/children")))

(defn get-relationships
  "Fetch relationships for one or more memory IDs."
  ([id]
   (post-request "/api/v1/relationships" {:memory-ids [(str id)]}))
  ([id & more-ids]
   (post-request "/api/v1/relationships"
                 {:memory-ids (mapv str (cons id more-ids))})))

(defn get-memory-history [memory-id]
  (get-request (str "/api/v1/memories/" memory-id "/history")))

(defn get-memory-stats []
  (get-request "/api/v1/stats/memories"))

(defn get-consistency []
  (get-request "/api/v1/stats/consistency"))

(defn batch-retain!
  ([namespace items]
   (batch-retain! namespace items nil))
  ([namespace items opts]
   (post-request "/api/v1/retain/batch"
                 (merge {:namespace namespace :items items} opts))))

(defn list-namespaces []
  (get-request "/api/v1/namespaces"))
