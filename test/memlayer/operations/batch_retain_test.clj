(ns memlayer.operations.batch-retain-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.proximum :as prox]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.test-helpers :as th]))

(defn- make-deps [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx  (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config)]
    {:db                 conn
     :vector-index       (atom vector-idx)
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (th/mock-flow-provider)
     :prompts            th/mock-prompts
     :tuning             {}}))

(deftest batch-retain-creates-memories-from-multiple-items
  (th/with-datahike
    (fn [conn]
      (testing "batch retain processes items, creates memories, stores in datahike + vector index"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (let [items  [{:content "User likes Clojure" :source "test"}
                          {:content "User prefers dark mode" :source "test"}
                          {:content "User works at Acme Corp" :source "test"}]
                  result (retention-flow/submit! flow
                                                 {:items items :namespace "default"})]
              (is (seq (:memory-ids result)))
              (is (vector? (:decisions result)))
              (is (every? #(= "CREATE" (:type %)) (:decisions result)))
              ;; Verify memories actually exist in datahike
              (doseq [mid (:memory-ids result)]
                (let [mem (dh/get-memory conn mid)]
                  (is (some? mem) (str "Memory " mid " should exist in datahike"))
                  (is (= :layer/fact (:memory/layer mem)))))
              ;; Verify embeddings are searchable in vector index
              (let [store @(:vector-index deps)
                    {:keys [embedding]} (llm-provider/embed (:embedding-provider deps) "User prefers dark mode")
                    results (protocols/search store embedding 5)]
                (is (seq results) "Should find stored embeddings")))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-with-empty-extraction
  (th/with-datahike
    (fn [conn]
      (testing "batch retain handles LLM returning no memories"
        (let [deps (assoc (make-deps conn)
                          :chat-provider (th/mock-flow-provider {:extract-result []}))
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "nothing" :source "test"}]
                                                  :namespace "default"})]
              (is (empty? (:memory-ids result)))
              (is (empty? (:decisions result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-with-noop-decision
  (th/with-datahike
    (fn [conn]
      (testing "batch retain with NOOP produces no memory-ids"
        (let [deps (assoc (make-deps conn)
                          :chat-provider (th/mock-flow-provider
                                          {:decision-result {:action "NOOP"
                                                             :reasoning "Already known"}}))
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "something" :source "test"}]
                                                  :namespace "default"})]
              (is (empty? (:memory-ids result)))
              (is (= 1 (count (:decisions result))))
              (is (= "NOOP" (:type (first (:decisions result))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-with-multiple-extractions
  (th/with-datahike
    (fn [conn]
      (testing "batch extraction returning multiple facts creates multiple memories"
        (let [deps (assoc (make-deps conn)
                          :chat-provider (th/mock-flow-provider
                                          {:extract-result
                                           [{:content "User likes Clojure" :layer "fact"}
                                            {:content "User is a developer" :layer "concept"}
                                            {:content "Programming" :layer "domain"}]}))
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "item 1" :source "test"}
                                                          {:content "item 2" :source "test"}]
                                                  :namespace "default"})]
              (is (= 3 (count (:memory-ids result))))
              (is (= 3 (count (:decisions result))))
              ;; Verify layers are set correctly
              (let [mems (mapv #(dh/get-memory conn %) (:memory-ids result))
                    layers (set (map :memory/layer mems))]
                (is (contains? layers :layer/fact))
                (is (contains? layers :layer/concept))
                (is (contains? layers :layer/domain))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-uses-single-extraction-call
  (th/with-datahike
    (fn [conn]
      (testing "batch extraction is called once, not per-item"
        (let [extract-count (atom 0)
              deps (assoc (make-deps conn)
                          :chat-provider
                          (th/mock-chat-provider
                           (fn [msgs _opts]
                             (let [system-content (:content (first msgs))]
                               (if (= system-content (:batch-extraction th/mock-prompts))
                                 (do (swap! extract-count inc)
                                     {:memories [{:content "fact 1" :layer "fact"}
                                                 {:content "fact 2" :layer "fact"}]})
                                 {:action "CREATE" :reasoning "New info"})))))
              flow (th/start-test-flow! deps)]
          (try
            (let [items  [{:content "item 1" :source "test"}
                          {:content "item 2" :source "test"}
                          {:content "item 3" :source "test"}]
                  _result (retention-flow/submit! flow {:items items :namespace "default"})]
              (is (= 1 @extract-count)))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-tracks-usage
  (th/with-datahike
    (fn [conn]
      (testing "usage includes token counts from pipeline stages"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "test" :source "test"}]
                                                  :namespace "default"})]
              (is (some? (:usage result)))
              (is (pos? (:total-tokens (:usage result)))))
            (finally
              (th/stop-test-flow! flow))))))))
