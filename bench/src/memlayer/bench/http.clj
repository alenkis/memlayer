(ns memlayer.bench.http
  "Shared HTTP utilities for benchmark adapters."
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def mapper
  (j/object-mapper {:decode-key-fn (fn [s] (-> s (str/replace "_" "-") keyword))}))

(defn parse-body [resp]
  (when (string? (:body resp))
    (try (j/read-value (:body resp) mapper)
         (catch Exception _ (:body resp)))))

(defn post! [base-url path body]
  (let [resp (hc/post (str base-url path)
                      {:as               :string
                       :content-type     :json
                       :body             (j/write-value-as-string body)
                       :throw-exceptions false
                       :timeout          120000})]
    {:status (:status resp)
     :body   (parse-body resp)}))

(defn get! [base-url path]
  (let [resp (hc/get (str base-url path)
                     {:as               :string
                      :throw-exceptions false
                      :timeout          10000})]
    {:status (:status resp)
     :body   (parse-body resp)}))

(defn put! [base-url path body]
  (let [resp (hc/put (str base-url path)
                     {:as               :string
                      :content-type     :json
                      :body             (j/write-value-as-string body)
                      :throw-exceptions false
                      :timeout          30000})]
    {:status (:status resp)
     :body   (parse-body resp)}))

(defn delete! [base-url path]
  (let [resp (hc/delete (str base-url path)
                        {:as               :string
                         :throw-exceptions false
                         :timeout          30000})]
    {:status (:status resp)
     :body   (parse-body resp)}))

(defn wait-healthy!
  "Poll a health endpoint until it returns 200. Throws after max-attempts."
  [system-name base-url path max-attempts]
  (loop [n 0]
    (when (>= n max-attempts)
      (throw (ex-info (str system-name " health check failed")
                      {:url base-url :path path :attempts max-attempts})))
    (let [resp (try (get! base-url path)
                    (catch Exception _ nil))]
      (if (= 200 (:status resp))
        (log/info system-name "is healthy at" base-url)
        (do (Thread/sleep 2000)
            (recur (inc n)))))))

(defn extract-usage
  "Extract token usage from a response body, normalizing key names."
  [body]
  (when-let [u (:usage body)]
    {:prompt-tokens     (or (:input-tokens u) (:prompt-tokens u) 0)
     :completion-tokens (or (:output-tokens u) (:completion-tokens u) 0)
     :total-tokens      (or (:total-tokens u) 0)}))
