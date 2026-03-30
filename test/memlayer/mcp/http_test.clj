(ns memlayer.mcp.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.mcp.http :as mcp-http]
            [memlayer.persistence.proximum :as prox]
            [memlayer.test-helpers :as th]))

(defn- make-deps [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx  (atom (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config))]
    {:db                 conn
     :vector-index       vector-idx
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (th/mock-flow-provider)
     :prompts            th/mock-prompts
     :tuning             {}}))

(defn- make-handler [deps]
  (let [flow (th/start-test-flow! deps)]
    {:handler (mcp-http/create-handler flow deps)
     :flow    flow}))

(defn- post-request
  "Build a Ring request for testing the MCP handler directly.
   Uses :body-params (simulating muuntaja-parsed body)."
  [body-params & {:keys [headers]}]
  {:request-method :post
   :uri            "/mcp"
   :headers        (merge {"content-type" "application/json"} headers)
   :body-params    body-params})

(defn- delete-request [& {:keys [headers]}]
  {:request-method :delete
   :uri            "/mcp"
   :headers        (or headers {})})

(deftest initialize-creates-session
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "POST initialize returns session ID and server info"
            (let [req  (post-request {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
                  resp ((:post handler) req)]
              (is (= 200 (:status resp)))
              (is (some? (get-in resp [:headers "Mcp-Session-Id"])))
              (let [body (:body resp)]
                (is (= "2.0" (:jsonrpc body)))
                (is (= 1 (:id body)))
                (is (= "2025-03-26" (get-in body [:result :protocolVersion])))
                (is (= "memlayer" (get-in body [:result :serverInfo :name])))
                (is (string? (get-in body [:result :instructions]))))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest notification-returns-202
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "POST notification returns 202 with empty body"
            (let [req  (post-request {:jsonrpc "2.0" :method "notifications/initialized" :params {}})
                  resp ((:post handler) req)]
              (is (= 202 (:status resp)))
              (is (= "" (:body resp)))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest tools-list-requires-session
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "POST tools/list without session returns 400"
            (let [req  (post-request {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}})
                  resp ((:post handler) req)]
              (is (= 400 (:status resp)))))

          (testing "POST tools/list with valid session returns tools"
            (let [init-req  (post-request {:jsonrpc "2.0" :id 1 :method "initialize" :params {}})
                  init-resp ((:post handler) init-req)
                  sid       (get-in init-resp [:headers "Mcp-Session-Id"])
                  req       (post-request {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
                                          :headers {"mcp-session-id" sid})
                  resp      ((:post handler) req)]
              (is (= 200 (:status resp)))
              (let [tools (get-in resp [:body :result :tools])]
                (is (= 6 (count tools))))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest sse-response-format
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "POST with Accept: text/event-stream returns SSE format"
            (let [req  (post-request {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                                     :headers {"accept" "text/event-stream"})
                  resp ((:post handler) req)]
              (is (= 200 (:status resp)))
              (is (= "text/event-stream" (get-in resp [:headers "Content-Type"])))
              (is (.startsWith ^String (:body resp) "event: message\ndata: "))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest delete-removes-session
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "DELETE with valid session returns 200"
            (let [init-resp ((:post handler) (post-request {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}))
                  sid       (get-in init-resp [:headers "Mcp-Session-Id"])
                  del-resp  ((:delete handler) (delete-request :headers {"mcp-session-id" sid}))]
              (is (= 200 (:status del-resp)))
              ;; Subsequent request with same session should fail
              (let [req  (post-request {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
                                       :headers {"mcp-session-id" sid})
                    resp ((:post handler) req)]
                (is (= 400 (:status resp))))))

          (testing "DELETE without valid session returns 404"
            (let [resp ((:delete handler) (delete-request :headers {"mcp-session-id" "nonexistent"}))]
              (is (= 404 (:status resp)))))
          (finally
            (th/stop-test-flow! flow)))))))

(deftest parse-error-returns-400
  (th/with-datahike
    (fn [conn]
      (let [deps (make-deps conn)
            {:keys [handler flow]} (make-handler deps)]
        (try
          (testing "nil body-params returns 400 with parse error"
            (let [req  {:request-method :post :uri "/mcp" :headers {} :body-params nil}
                  resp ((:post handler) req)]
              (is (= 400 (:status resp)))
              (is (= -32700 (get-in resp [:body :error :code])))))

          (testing "Malformed JSON in raw body returns 400 with parse error"
            (let [req  {:request-method :post :uri "/mcp" :headers {} :body "not valid json"}
                  resp ((:post handler) req)]
              (is (= 400 (:status resp)))
              (is (= -32700 (get-in resp [:body :error :code])))))
          (finally
            (th/stop-test-flow! flow)))))))
