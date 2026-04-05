(ns memlayer.bench.adapters.hindsight
  "Benchmark adapter for Hindsight (Vectorize). Uses the reflect endpoint
   for recall since it generates an LLM answer (comparable to memlayer's recall).
   Hindsight's recall endpoint is LLM-free pure retrieval."
  (:require [memlayer.bench.adapter :as adapter]
            [memlayer.bench.http :as http]
            [clojure.tools.logging :as log]))

(defn- bank-path [bank-id]
  (str "/v1/default/banks/" bank-id))

(defn- ok? [status]
  (#{200 201 204} status))

(defrecord HindsightAdapter [base-url]
  adapter/BenchAdapter

  (adapter-name [_] "hindsight")

  (setup! [_]
    (http/wait-healthy! "hindsight" base-url "/health" 30))

  (teardown! [_] nil)

  (create-session! [_ session-id]
    (let [resp (http/put! base-url (bank-path session-id) {:name session-id})]
      (when-not (ok? (:status resp))
        (throw (ex-info "Failed to create bank"
                        {:session session-id :status (:status resp) :body (:body resp)})))))

  (delete-session! [_ session-id]
    (let [resp (http/delete! base-url (bank-path session-id))]
      (when-not (ok? (:status resp))
        (log/warn "Failed to delete bank" session-id (:status resp)))))

  (retain! [_ session-id text opts]
    (let [body (cond-> {:items [{:content text}]}
                 (:timestamp opts) (assoc-in [:items 0 :timestamp] (:timestamp opts)))
          [resp ms] (adapter/timed
                     #(http/post! base-url
                                  (str (bank-path session-id) "/memories")
                                  body))]
      (if (ok? (:status resp))
        {:latency-ms ms
         :usage      (http/extract-usage (:body resp))}
        (do (log/warn "retain! failed" session-id (:status resp))
            {:latency-ms ms :usage nil :error {:status (:status resp)}}))))

  (reflect! [_ _session-id]
    ;; Hindsight's reflect is used in recall!, not as a separate step.
    nil)

  (recall! [_ session-id query]
    ;; Use reflect endpoint — it does retrieval + LLM reasoning,
    ;; comparable to memlayer's recall which also generates an answer.
    (let [[resp ms] (adapter/timed
                     #(http/post! base-url
                                  (str (bank-path session-id) "/reflect")
                                  {:query  query
                                   :budget "low"}))]
      (if (ok? (:status resp))
        {:answer     (get-in resp [:body :text] "")
         :latency-ms ms
         :usage      (http/extract-usage (:body resp))}
        (do (log/warn "recall! (reflect) failed" session-id (:status resp))
            {:answer "" :latency-ms ms :usage nil :error {:status (:status resp)}})))))

(defn make-adapter
  "Create a hindsight benchmark adapter."
  [{:keys [base-url] :or {base-url "http://localhost:8888"}}]
  (->HindsightAdapter base-url))
