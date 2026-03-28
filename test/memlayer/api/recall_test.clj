(ns memlayer.api.recall-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.router :as router]
            [memlayer.persistence.proximum :as prox]
            [memlayer.test-helpers :as th]
            [jsonista.core :as j]))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- make-test-app [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx (atom (prox/->ProximumVectorStore
                          (prox/create-index! prox-config) prox-config))
        deps       {:db                 conn
                    :vector-index       vector-idx
                    :embedding-provider (th/mock-embedding-provider {:dim 64})
                    :chat-provider      (th/mock-flow-provider)
                    :prompts            th/mock-prompts
                    :tuning             {}}
        flow       (th/start-test-flow! deps)]
    {:app  (router/create-router (merge (th/make-test-handlers deps flow)
                                        {:db conn :auth-config {:auth-enabled false}
                                         :rate-limit {:enabled false}}))
     :flow flow
     :deps deps}))

(defn- json-request [app method uri & [body]]
  (let [request (cond-> {:request-method method
                         :uri            uri
                         :headers        {"content-type" "application/json"
                                          "accept"       "application/json"}}
                  body (assoc :body (java.io.ByteArrayInputStream.
                                     (.getBytes (j/write-value-as-string body) "UTF-8"))))]
    (app request)))

(defn- parse-body [response]
  (let [body (:body response)]
    (cond
      (string? body) (j/read-value body json-mapper)
      (instance? java.io.InputStream body) (j/read-value body json-mapper)
      (map? body) body
      :else body)))

(deftest recall-endpoint-success
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/recall returns search results"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            ;; First retain a memory
            (json-request app :post "/api/v1/retain"
                          {:content "User prefers dark mode"
                           :source  "conversation"})
            ;; Recall with exact extracted content text (mock embeddings are hash-based)
            (let [response (json-request app :post "/api/v1/recall"
                                         {:query     "User prefers dark mode"
                                          :threshold 1.0})
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (= "User prefers dark mode" (:query body)))
              (is (vector? (:memories body)))
              (is (number? (:count body))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-endpoint-empty-results
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/recall returns empty when no matches"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [response (json-request app :post "/api/v1/recall"
                                         {:query "something random"})
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (= 0 (:count body)))
              (is (empty? (:memories body))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-endpoint-missing-query
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/recall with missing query returns error"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [response (json-request app :post "/api/v1/recall"
                                         {:namespace "test"})]
              (is (contains? #{400 500} (:status response))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-endpoint-returns-generated-answer
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/recall returns :answer when memories match"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (json-request app :post "/api/v1/retain"
                          {:content "User prefers dark mode"
                           :source  "conversation"})
            (let [response (json-request app :post "/api/v1/recall"
                                         {:query     "User prefers dark mode"
                                          :threshold 1.0})
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (string? (:answer body)))
              (is (seq (:answer body)))
              (is (vector? (:memories body))))
            (finally
              (th/stop-test-flow! flow))))))))
