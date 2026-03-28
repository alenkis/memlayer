(ns memlayer.mcp.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.mcp.server :as mcp]
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

(defn- make-ctx
  "Build MCP dispatch context with a running flow."
  [deps]
  (let [flow (th/start-test-flow! deps)]
    {:flow flow :deps deps}))

(deftest initialize-method
  (th/with-datahike
    (fn [conn]
      (testing "initialize returns server info and capabilities"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (= "2.0" (:jsonrpc resp)))
              (is (= 1 (:id resp)))
              (is (= "memlayer" (get-in resp [:result :serverInfo :name])))
              (is (string? (get-in resp [:result :serverInfo :version])))
              (is (some? (get-in resp [:result :capabilities])))
              (is (string? (get-in resp [:result :instructions])))
              (is (.contains ^String (get-in resp [:result :instructions]) "memlayer")))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest tools-list-method
  (th/with-datahike
    (fn [conn]
      (testing "tools/list returns available tools"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 2 (:id resp)))
              (let [tool-list (get-in resp [:result :tools])
                    names     (set (map :name tool-list))]
                (is (= 5 (count tool-list)))
                (is (contains? names "memlayer_retain"))
                (is (contains? names "memlayer_batch_retain"))
                (is (contains? names "memlayer_recall"))
                (is (contains? names "memlayer_forget"))
                (is (contains? names "memlayer_reflect"))
                (is (every? :inputSchema tool-list))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest tools-call-retain
  (th/with-datahike
    (fn [conn]
      (testing "tools/call memlayer_retain creates a memory"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0"
                        :id      3
                        :method  "tools/call"
                        :params  {:name      "memlayer_retain"
                                  :arguments {:content "User likes functional programming"
                                              :source  "conversation"}}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 3 (:id resp)))
              (is (nil? (:error resp)))
              (let [content (get-in resp [:result :content])]
                (is (= 1 (count content)))
                (is (= "text" (:type (first content))))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest tools-call-recall
  (th/with-datahike
    (fn [conn]
      (testing "tools/call memlayer_recall searches memories"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            ;; First retain a memory
            (mcp/dispatch {:jsonrpc "2.0" :id 10 :method "tools/call"
                           :params {:name "memlayer_retain"
                                    :arguments {:content "User likes Clojure"
                                                :source "conversation"}}}
                          ctx)
            ;; Now recall it
            (let [resp (mcp/dispatch {:jsonrpc "2.0" :id 11 :method "tools/call"
                                      :params {:name "memlayer_recall"
                                               :arguments {:query "User prefers dark mode"
                                                           :threshold 1.0}}}
                                     ctx)]
              (is (= 11 (:id resp)))
              (is (nil? (:error resp)))
              (let [content (get-in resp [:result :content])]
                (is (= 1 (count content)))
                (is (= "text" (:type (first content))))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest unknown-method
  (th/with-datahike
    (fn [conn]
      (testing "unknown method returns error"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 4 :method "unknown/method" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 4 (:id resp)))
              (is (some? (:error resp)))
              (is (= -32601 (get-in resp [:error :code]))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest resources-list-method
  (th/with-datahike
    (fn [conn]
      (testing "resources/list returns available resources"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 20 :method "resources/list" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 20 (:id resp)))
              (is (nil? (:error resp)))
              (let [resources (get-in resp [:result :resources])]
                (is (= 1 (count resources)))
                (is (= "memlayer://skill" (:uri (first resources))))
                (is (= "text/markdown" (:mimeType (first resources))))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest resources-read-skill
  (th/with-datahike
    (fn [conn]
      (testing "resources/read returns skill content"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 21 :method "resources/read"
                        :params {:uri "memlayer://skill"}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 21 (:id resp)))
              (is (nil? (:error resp)))
              (let [contents (get-in resp [:result :contents])]
                (is (= 1 (count contents)))
                (is (= "memlayer://skill" (:uri (first contents))))
                (is (= "text/markdown" (:mimeType (first contents))))
                (is (string? (:text (first contents))))
                (is (.contains (:text (first contents)) "memlayer_retain"))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest resources-read-unknown
  (th/with-datahike
    (fn [conn]
      (testing "resources/read with unknown URI returns error"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 22 :method "resources/read"
                        :params {:uri "memlayer://nonexistent"}}
                  resp (mcp/dispatch msg ctx)]
              (is (= 22 (:id resp)))
              (is (some? (:error resp))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest initialize-includes-resources-capability
  (th/with-datahike
    (fn [conn]
      (testing "initialize announces resources capability"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :id 23 :method "initialize" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (some? (get-in resp [:result :capabilities :resources]))))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))

(deftest notification-no-response
  (th/with-datahike
    (fn [conn]
      (testing "notifications (no id) return nil"
        (let [deps (make-deps conn)
              ctx  (make-ctx deps)]
          (try
            (let [msg  {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
                  resp (mcp/dispatch msg ctx)]
              (is (nil? resp)))
            (finally
              (th/stop-test-flow! (:flow ctx)))))))))
