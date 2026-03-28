(ns memlayer.api.routes-test
  "Tests for route naming and restructuring (MEM-9).
   Verifies renamed routes respond correctly and old routes return 404."
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.router :as router]
            [memlayer.persistence.proximum :as prox]
            [memlayer.persistence.stratum :as strat]
            [memlayer.persistence.datahike :as dh]
            [memlayer.test-helpers :as th]
            [jsonista.core :as j]))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn keyword}))

(defn- make-test-app
  "Create a test app with auth disabled and stratum wired."
  [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx (atom (prox/->ProximumVectorStore
                          (prox/create-index! prox-config) prox-config))
        ds-atom    (strat/install-sync-listener! conn)
        deps       {:db                 conn
                    :vector-index       vector-idx
                    :stratum            ds-atom
                    :embedding-provider (th/mock-embedding-provider {:dim 64})
                    :chat-provider      (th/mock-flow-provider)
                    :prompts            th/mock-prompts
                    :tuning             {}}
        flow       (th/start-test-flow! deps)]
    {:app     (router/create-router (th/make-test-handlers deps flow))
     :flow    flow
     :ds-atom ds-atom}))

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

;; -- /api/v1/account routes --

(deftest account-me-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "GET /api/v1/account/me returns user profile"
            (let [response (json-request app :get "/api/v1/account/me")
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (contains? body :uid))))

          (testing "GET /api/v1/dashboard/me returns 404 (old route removed)"
            (let [response (json-request app :get "/api/v1/dashboard/me")]
              (is (= 404 (:status response)))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest account-tokens-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "GET /api/v1/account/tokens returns token list"
            (let [response (json-request app :get "/api/v1/account/tokens")
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (vector? (:tokens body)))))

          (testing "GET /api/v1/dashboard/tokens returns 404"
            (let [response (json-request app :get "/api/v1/dashboard/tokens")]
              (is (= 404 (:status response)))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest account-settings-route
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          (testing "GET /api/v1/account/settings returns settings"
            (let [response (json-request app :get "/api/v1/account/settings")
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (contains? body :has_groq_key))
              (is (contains? body :has_openai_key))))
          (finally
            (th/stop-test-flow! flow)))))))

;; -- Usage endpoint with stratum --

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
              (is (map? (:memory_stats body)))
              (is (vector? (get-in body [:memory_stats :by_layer])))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest account-usage-with-memories
  (th/with-datahike
    (fn [conn]
      (let [{:keys [app flow]} (make-test-app conn)]
        (try
          ;; Insert test memories
          (dh/insert-memory! conn {:memory/content   "dark mode preference"
                                   :memory/layer     :layer/fact
                                   :memory/importance (float 0.7)
                                   :memory/source    "conversation"
                                   :memory/namespace "default"})
          (dh/insert-memory! conn {:memory/content   "meeting notes"
                                   :memory/layer     :layer/episode
                                   :memory/importance (float 0.5)
                                   :memory/source    "document"
                                   :memory/namespace "work"})
          ;; Wait for stratum sync
          (Thread/sleep 200)

          (testing "usage endpoint returns memory_stats from stratum"
            (let [response (json-request app :get "/api/v1/account/usage")
                  body     (parse-body response)
                  stats    (:memory_stats body)]
              (is (= 200 (:status response)))
              ;; by_layer should have fact and episode
              (let [layers (into {} (map (fn [r] [(:layer r) (:_count r)])
                                         (:by_layer stats)))]
                (is (= 1 (get layers "fact")))
                (is (= 1 (get layers "episode"))))
              ;; by_namespace should have default and work
              (let [namespaces (into {} (map (fn [r] [(:namespace r) (:_count r)])
                                             (:by_namespace stats)))]
                (is (= 1 (get namespaces "default")))
                (is (= 1 (get namespaces "work"))))
              ;; by_source should have conversation and document
              (let [sources (into {} (map (fn [r] [(:source r) (:_count r)])
                                          (:by_source stats)))]
                (is (= 1 (get sources "conversation")))
                (is (= 1 (get sources "document"))))))

          ;; Cleanup listener
          (strat/remove-sync-listener! conn)
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
          (finally
            (th/stop-test-flow! flow)))))))
