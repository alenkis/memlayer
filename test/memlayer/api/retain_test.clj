(ns memlayer.api.retain-test
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
     :flow flow}))

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
      (instance? java.io.InputStream body)
      (j/read-value body json-mapper)
      (map? body) body
      :else body)))

(deftest health-endpoint
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (let [response (json-request app :get "/health")]
            (is (= 200 (:status response)))
            (let [body (parse-body response)]
              (is (= "ok" (:status body)))
              (is (string? (:version body)))
              (is (string? (:git_sha body)))
              (is (string? (:built_at body)))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest retain-endpoint-success
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/retain creates a memory"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [response (json-request app :post "/api/v1/retain"
                                         {:content   "User prefers dark mode"
                                          :source    "conversation"
                                          :namespace "default"})
                  body     (parse-body response)]
              (is (= 201 (:status response)))
              (is (vector? (:memory_ids body)))
              (is (vector? (:decisions body)))
              (is (= "CREATE" (:type (first (:decisions body))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-endpoint-missing-fields
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/retain with missing required fields returns error"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [response (json-request app :post "/api/v1/retain"
                                         {:content "only content"})]
              ;; Coercion should catch missing :source
              (is (contains? #{400 500} (:status response))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest not-found-endpoint
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (let [response (json-request app :get "/nonexistent")]
            (is (= 404 (:status response))))
          (finally
            (th/stop-test-flow! flow)))))))
