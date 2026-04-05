(ns memlayer.bench.adapters.memlayer
  "Benchmark adapter for memlayer. Talks to the REST API."
  (:require [memlayer.bench.adapter :as adapter]
            [memlayer.bench.http :as http]
            [clojure.tools.logging :as log]))

(defn- ok? [status]
  (#{200 201 204} status))

(defrecord MemlayerAdapter [base-url]
  adapter/BenchAdapter

  (adapter-name [_] "memlayer")

  (setup! [_]
    (http/wait-healthy! "memlayer" base-url "/health" 30))

  (teardown! [_] nil)

  (create-session! [_ session-id]
    (let [resp (http/post! base-url "/api/v1/account/namespaces" {:name session-id})]
      (when-not (ok? (:status resp))
        (throw (ex-info "Failed to create namespace"
                        {:session session-id :status (:status resp) :body (:body resp)})))))

  (delete-session! [_ session-id]
    (let [resp (http/delete! base-url (str "/api/v1/account/namespaces/" session-id))]
      (when-not (ok? (:status resp))
        (log/warn "Failed to delete namespace" session-id (:status resp)))))

  (retain! [_ session-id text opts]
    (let [text' (if-let [ts (:timestamp opts)]
                  (str "[Date: " ts "]\n" text)
                  text)
          [resp ms] (adapter/timed
                     #(http/post! base-url "/api/v1/retain"
                                  {:content   text'
                                   :namespace session-id
                                   :source    "longmemeval"}))]
      (if (ok? (:status resp))
        {:latency-ms ms
         :usage      (http/extract-usage (:body resp))}
        (do (log/warn "retain! failed" session-id (:status resp))
            {:latency-ms ms :usage nil :error {:status (:status resp)}}))))

  (reflect! [_ session-id]
    (let [[resp ms] (adapter/timed
                     #(http/post! base-url "/api/v1/reflect"
                                  {:namespace session-id}))]
      (when-not (ok? (:status resp))
        (log/warn "reflect! failed" session-id (:status resp)))
      {:latency-ms ms}))

  (recall! [_ session-id query]
    (let [[resp ms] (adapter/timed
                     #(http/post! base-url "/api/v1/recall"
                                  {:query        query
                                   :namespace    session-id
                                   :expand-graph true}))]
      (if (ok? (:status resp))
        {:answer     (get-in resp [:body :answer] "")
         :latency-ms ms
         :usage      (http/extract-usage (:body resp))}
        (do (log/warn "recall! failed" session-id (:status resp))
            {:answer "" :latency-ms ms :usage nil :error {:status (:status resp)}})))))

(defn make-adapter
  "Create a memlayer benchmark adapter."
  [{:keys [base-url] :or {base-url "http://localhost:8090"}}]
  (->MemlayerAdapter base-url))
