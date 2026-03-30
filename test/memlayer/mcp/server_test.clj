(ns memlayer.mcp.server-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.mcp.server :as mcp]
            [memlayer.mcp.client :as client]
            [memlayer.mcp.lifecycle :as lifecycle]))

(defn- make-test-ctx
  ([] (make-test-ctx "default"))
  ([namespace]
   {:base-url         "http://localhost:8090"
    :port             8090
    :active-namespace (atom namespace)}))

(deftest initialize-method
  (testing "initialize returns server info and capabilities"
    (let [msg  {:jsonrpc "2.0" :id 1 :method "initialize" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (is (= "memlayer" (get-in resp [:result :serverInfo :name])))
      (is (string? (get-in resp [:result :serverInfo :version])))
      (is (some? (get-in resp [:result :capabilities])))
      (is (string? (get-in resp [:result :instructions])))
      (is (.contains ^String (get-in resp [:result :instructions]) "memlayer")))))

(deftest tools-list-method
  (testing "tools/list returns available tools"
    (let [msg  {:jsonrpc "2.0" :id 2 :method "tools/list" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= 2 (:id resp)))
      (let [tool-list (get-in resp [:result :tools])
            names     (set (map :name tool-list))]
        (is (= 6 (count tool-list)))
        (is (contains? names "memlayer_retain"))
        (is (contains? names "memlayer_batch_retain"))
        (is (contains? names "memlayer_recall"))
        (is (contains? names "memlayer_forget"))
        (is (contains? names "memlayer_reflect"))
        (is (contains? names "memlayer_set_namespace"))
        (is (every? :inputSchema tool-list))))))

(deftest tools-call-retain
  (testing "tools/call memlayer_retain forwards to HTTP API with active namespace"
    (let [captured (atom nil)]
      (with-redefs [client/retain! (fn [base-url params]
                                     (reset! captured {:base-url base-url :params params})
                                     {:memory-ids ["abc-123"]
                                      :decisions  [{:type "CREATE" :content "test"}]})]
        (let [msg  {:jsonrpc "2.0"
                    :id      3
                    :method  "tools/call"
                    :params  {:name      "memlayer_retain"
                              :arguments {:content "User likes FP"
                                          :source  "conversation"}}}
              resp (mcp/dispatch msg (make-test-ctx "work"))]
          (is (= 3 (:id resp)))
          (is (nil? (:error resp)))
          (is (= "http://localhost:8090" (:base-url @captured)))
          (is (= "User likes FP" (get-in @captured [:params :content])))
          (is (= "work" (get-in @captured [:params :namespace]))
              "namespace should be injected from active-namespace")
          (let [content (get-in resp [:result :content])]
            (is (= 1 (count content)))
            (is (= "text" (:type (first content))))))))))

(deftest tools-call-recall
  (testing "tools/call memlayer_recall forwards to HTTP API"
    (with-redefs [client/recall! (fn [_base-url _params]
                                   {:answer   "User prefers dark mode"
                                    :memories [{:id "abc" :content "test"}]})]
      (let [resp (mcp/dispatch {:jsonrpc "2.0" :id 11 :method "tools/call"
                                :params {:name "memlayer_recall"
                                         :arguments {:query "preferences"}}}
                               (make-test-ctx))]
        (is (= 11 (:id resp)))
        (is (nil? (:error resp)))
        (let [content (get-in resp [:result :content])]
          (is (= 1 (count content)))
          (is (= "text" (:type (first content)))))))))

(deftest tools-call-forget
  (testing "tools/call memlayer_forget forwards to HTTP API"
    (with-redefs [client/forget! (fn [_base-url _params]
                                   {:memories-removed 1 :relationships-removed 0})]
      (let [resp (mcp/dispatch {:jsonrpc "2.0" :id 12 :method "tools/call"
                                :params {:name "memlayer_forget"
                                         :arguments {:memory-id "some-uuid"}}}
                               (make-test-ctx))]
        (is (= 12 (:id resp)))
        (is (nil? (:error resp)))))))

(deftest tools-call-reflect
  (testing "tools/call memlayer_reflect forwards to HTTP API"
    (with-redefs [client/reflect! (fn [_base-url _params]
                                    {:organized {} :summarized {} :connected {} :curated {}})]
      (let [resp (mcp/dispatch {:jsonrpc "2.0" :id 13 :method "tools/call"
                                :params {:name "memlayer_reflect"
                                         :arguments {:dry-run true}}}
                               (make-test-ctx))]
        (is (= 13 (:id resp)))
        (is (nil? (:error resp)))))))

(deftest unknown-method
  (testing "unknown method returns error"
    (let [msg  {:jsonrpc "2.0" :id 4 :method "unknown/method" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= 4 (:id resp)))
      (is (some? (:error resp)))
      (is (= -32601 (get-in resp [:error :code]))))))

(deftest resources-list-method
  (testing "resources/list returns available resources"
    (let [msg  {:jsonrpc "2.0" :id 20 :method "resources/list" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= 20 (:id resp)))
      (is (nil? (:error resp)))
      (let [resources (get-in resp [:result :resources])]
        (is (= 1 (count resources)))
        (is (= "memlayer://skill" (:uri (first resources))))
        (is (= "text/markdown" (:mimeType (first resources))))))))

(deftest resources-read-skill
  (testing "resources/read returns skill content"
    (let [msg  {:jsonrpc "2.0" :id 21 :method "resources/read"
                :params {:uri "memlayer://skill"}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= 21 (:id resp)))
      (is (nil? (:error resp)))
      (let [contents (get-in resp [:result :contents])]
        (is (= 1 (count contents)))
        (is (= "memlayer://skill" (:uri (first contents))))
        (is (= "text/markdown" (:mimeType (first contents))))
        (is (string? (:text (first contents))))
        (is (.contains (:text (first contents)) "memlayer_retain"))))))

(deftest resources-read-unknown
  (testing "resources/read with unknown URI returns error"
    (let [msg  {:jsonrpc "2.0" :id 22 :method "resources/read"
                :params {:uri "memlayer://nonexistent"}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (= 22 (:id resp)))
      (is (some? (:error resp))))))

(deftest initialize-includes-resources-capability
  (testing "initialize announces resources capability"
    (let [msg  {:jsonrpc "2.0" :id 23 :method "initialize" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (some? (get-in resp [:result :capabilities :resources]))))))

(deftest notification-no-response
  (testing "notifications (no id) return nil"
    (let [msg  {:jsonrpc "2.0" :method "notifications/initialized" :params {}}
          resp (mcp/dispatch msg (make-test-ctx))]
      (is (nil? resp)))))

(deftest tools-call-retry-on-failure
  (testing "retries tool call once if server goes away"
    (let [call-count (atom 0)]
      (with-redefs [client/recall!              (fn [_base-url _params]
                                                  (swap! call-count inc)
                                                  (if (= 1 @call-count)
                                                    (throw (ex-info "Connection refused" {}))
                                                    {:answer "ok" :memories []}))
                    lifecycle/try-restart-server! (fn [_port] "http://localhost:8090")]
        (let [resp (mcp/dispatch {:jsonrpc "2.0" :id 30 :method "tools/call"
                                  :params {:name "memlayer_recall"
                                           :arguments {:query "test"}}}
                                 (make-test-ctx))]
          (is (nil? (:error resp)))
          (is (= 2 @call-count)))))))

(deftest tools-call-set-namespace
  (testing "memlayer_set_namespace changes the active namespace"
    (let [ctx  (make-test-ctx "default")
          resp (mcp/dispatch {:jsonrpc "2.0" :id 40 :method "tools/call"
                              :params {:name "memlayer_set_namespace"
                                       :arguments {:namespace "personal"}}}
                             ctx)]
      (is (= 40 (:id resp)))
      (is (nil? (:error resp)))
      (is (= "personal" @(:active-namespace ctx)))))

  (testing "subsequent calls use the new namespace"
    (let [ctx      (make-test-ctx "default")
          captured (atom nil)]
      ;; Switch namespace
      (mcp/dispatch {:jsonrpc "2.0" :id 41 :method "tools/call"
                     :params {:name "memlayer_set_namespace"
                              :arguments {:namespace "work"}}}
                    ctx)
      ;; Retain should use "work"
      (with-redefs [client/retain! (fn [_base-url params]
                                     (reset! captured params)
                                     {:memory-ids ["x"] :decisions []})]
        (mcp/dispatch {:jsonrpc "2.0" :id 42 :method "tools/call"
                       :params {:name "memlayer_retain"
                                :arguments {:content "test" :source "conversation"}}}
                      ctx))
      (is (= "work" (:namespace @captured))))))

(deftest initialize-includes-active-namespace
  (testing "initialize instructions mention the active namespace"
    (let [ctx  (make-test-ctx "my-project")
          resp (mcp/dispatch {:jsonrpc "2.0" :id 50 :method "initialize" :params {}}
                             ctx)]
      (is (.contains ^String (get-in resp [:result :instructions]) "my-project")))))
