(ns memlayer.api.memories-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.router :as router]
            [memlayer.persistence.datahike :as dh]
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

(defn- retain-memory! [app content]
  (let [response (json-request app :post "/api/v1/retain"
                               {:content content :source "test"})
        body     (parse-body response)]
    (first (:memory_ids body))))

;; -- DELETE /api/v1/memories/:id --

(deftest delete-memory-success
  (th/with-datahike
    (fn [conn]
      (testing "DELETE /api/v1/memories/:id returns 204 for existing memory"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [memory-id (retain-memory! app "test memory for deletion")]
              (is (some? memory-id))
              (let [response (json-request app :delete (str "/api/v1/memories/" memory-id))]
                (is (= 204 (:status response)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest delete-memory-not-found
  (th/with-datahike
    (fn [conn]
      (testing "DELETE /api/v1/memories/:id returns 404 for missing memory"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [fake-id  (java.util.UUID/randomUUID)
                  response (json-request app :delete (str "/api/v1/memories/" fake-id))]
              (is (= 404 (:status response))))
            (finally
              (th/stop-test-flow! flow))))))))

;; -- POST /api/v1/forget --

(deftest forget-memory-success
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/forget returns 200 with removal counts"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [memory-id (retain-memory! app "test memory for forget")
                  response  (json-request app :post "/api/v1/forget"
                                          {:memory_id memory-id})
                  body      (parse-body response)]
              (is (= 200 (:status response)))
              (is (= 1 (:memories_removed body)))
              (is (number? (:relationships_removed body))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest forget-memory-invalid-id
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/forget returns 400 for missing memory_id"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [response (json-request app :post "/api/v1/forget" {})]
              (is (contains? #{400 500} (:status response))))
            (finally
              (th/stop-test-flow! flow))))))))

;; -- POST /api/v1/relationships --

(deftest relationships-endpoint-returns-relationships
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/relationships returns relationships for given memory IDs"
        (let [{:keys [app flow deps]} (make-test-app conn)
              db (:db deps)]
          (try
            (let [mem-id-str (retain-memory! app "test memory for relationships")
                  mem-id     (java.util.UUID/fromString mem-id-str)
                  other-id   (dh/insert-memory! db {:memory/content   "related memory"
                                                    :memory/layer     :layer/fact
                                                    :memory/importance (float 0.5)
                                                    :memory/source    "test"
                                                    :memory/namespace "default"})
                  _          (dh/insert-relationship! db {:source-id mem-id
                                                          :target-id other-id
                                                          :type :elaborates})
                  response   (json-request app :post "/api/v1/relationships"
                                           {:memory_ids [mem-id-str]})
                  body       (parse-body response)]
              (is (= 200 (:status response)))
              (is (= 1 (count (:relationships body))))
              (let [rel (first (:relationships body))]
                (is (= mem-id-str (:source_id rel)))
                (is (= (str other-id) (:target_id rel)))
                (is (= "elaborates" (:type rel)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest relationships-endpoint-empty-result
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/relationships returns empty array when no relationships exist"
        (let [{:keys [app flow]} (make-test-app conn)]
          (try
            (let [mem-id   (retain-memory! app "lonely memory")
                  response (json-request app :post "/api/v1/relationships"
                                         {:memory_ids [mem-id]})
                  body     (parse-body response)]
              (is (= 200 (:status response)))
              (is (empty? (:relationships body))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest relationships-endpoint-no-confidence-field
  (th/with-datahike
    (fn [conn]
      (testing "POST /api/v1/relationships does not include confidence/strength fields"
        (let [{:keys [app flow deps]} (make-test-app conn)
              db (:db deps)]
          (try
            (let [mem-id-str (retain-memory! app "memory A")
                  mem-id     (java.util.UUID/fromString mem-id-str)
                  other-id   (dh/insert-memory! db {:memory/content   "memory B"
                                                    :memory/layer     :layer/fact
                                                    :memory/importance (float 0.5)
                                                    :memory/source    "test"
                                                    :memory/namespace "default"})
                  _          (dh/insert-relationship! db {:source-id mem-id
                                                          :target-id other-id
                                                          :type :supports})
                  response   (json-request app :post "/api/v1/relationships"
                                           {:memory_ids [mem-id-str]})
                  body       (parse-body response)
                  rel        (first (:relationships body))]
              (is (some? rel))
              (is (nil? (:confidence rel)))
              (is (nil? (:strength rel)))
              (is (some? (:id rel)))
              (is (some? (:source_id rel)))
              (is (some? (:target_id rel)))
              (is (some? (:type rel))))
            (finally
              (th/stop-test-flow! flow))))))))
