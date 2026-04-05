(ns memlayer.api.routes-test
  "Tests for route naming and restructuring (MEM-9).
   Verifies renamed routes respond correctly and old routes return 404."
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.router :as router]
            [memlayer.persistence.proximum :as prox]
            [memlayer.test-helpers :as th]
            [jsonista.core :as j]))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- make-test-app
  "Create a test app with auth disabled."
  [conn]
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
    {:app  (router/create-router (th/make-test-handlers deps flow))
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
      (instance? java.io.InputStream body) (j/read-value body json-mapper)
      (map? body) body
      :else body)))

;; -- /retain/batch route --

(deftest retain-batch-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "POST /api/v1/retain/batch succeeds"
            (let [response (json-request app :post "/api/v1/retain/batch"
                                         {:namespace "default"
                                          :items     [{:content "test memory"}]})
                  body     (parse-body response)]
              (is (= 201 (:status response)))
              (is (vector? (:memory_ids body)))))

          (testing "POST /api/v1/batch-retain returns 404 (old route removed)"
            (let [response (json-request app :post "/api/v1/batch-retain"
                                         {:namespace "default"
                                          :items     [{:content "test memory"}]})]
              (is (= 404 (:status response)))))
          (finally
            (th/stop-test-flow! flow)))))))

;; -- Usage endpoint --

(deftest account-usage-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "GET /api/v1/account/usage returns empty stats for empty DB"
            (let [response (json-request app :get "/api/v1/account/usage")
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (= 0 (get-in body [:summary :total_tokens])))
              (is (vector? (get-in body [:summary :by_provider])))
              (is (vector? (:timeseries body)))
              (is (map? (:memory_stats body)))))
          (finally
            (th/stop-test-flow! flow)))))))

;; -- Namespace routes under /account --

(deftest account-namespaces-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "GET /api/v1/account/namespaces returns namespace list"
            (let [response (json-request app :get "/api/v1/account/namespaces")
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (vector? (:namespaces body)))
              (is (number? (:total body)))))

          (testing "POST /api/v1/account/namespaces creates namespace"
            (let [response (json-request app :post "/api/v1/account/namespaces"
                                         {:name "test-ns"})
                  body     (parse-body response)]
              (is (= 201 (:status response)))
              (is (= "test-ns" (get-in body [:namespace :name])))))

          (testing "DELETE /api/v1/account/namespaces/:id deletes namespace"
            ;; Retain a memory into the namespace so it actually exists in the DB
            (let [response (json-request app :post "/api/v1/retain/batch"
                                         {:namespace "test-ns"
                                          :items     [{:content "ephemeral memory"}]})]
              (is (= 201 (:status response))))
            (let [response (json-request app :delete "/api/v1/account/namespaces/test-ns")]
              (is (= 204 (:status response))))
            ;; Namespace should no longer appear in the list
            (let [response (json-request app :get "/api/v1/account/namespaces")
                  body     (parse-body response)
                  names    (set (map :name (:namespaces body)))]
              (is (not (contains? names "test-ns")))))

          (testing "DELETE /api/v1/account/namespaces/:id returns 404 for unknown namespace"
            (let [response (json-request app :delete "/api/v1/account/namespaces/no-such-ns")]
              (is (= 404 (:status response)))))
          (finally
            (th/stop-test-flow! flow)))))))
