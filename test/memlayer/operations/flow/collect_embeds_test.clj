(ns memlayer.operations.flow.collect-embeds-test
  "Unit tests for the collect-embeds accumulator process."
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.persistence.proximum :as prox]
            [memlayer.test-helpers :as th]))

(defn- make-deps
  ([conn] (make-deps conn {}))
  ([conn {:keys [extract-result decision-result]}]
   (let [prox-config {:dim 64 :capacity 1000}
         vector-idx (atom (prox/->ProximumVectorStore
                           (prox/create-index! prox-config) prox-config))]
     {:db                 conn
      :vector-index       vector-idx
      :embedding-provider (th/mock-embedding-provider {:dim 64})
      :chat-provider      (th/mock-flow-provider
                           (cond-> {}
                             extract-result  (assoc :extract-result extract-result)
                             decision-result (assoc :decision-result decision-result)))
      :prompts            th/mock-prompts
      :tuning             {}})))

(defn- start-flow-with-batch-config! [deps batch-size wait-ms]
  (retention-flow/start-standalone! deps {:flow {:io-threads          8
                                                 :submit-timeout-ms   30000
                                                 :embed-batch-size    batch-size
                                                 :embed-batch-wait-ms wait-ms}}))

(deftest single-retain-works-with-accumulator
  (th/with-datahike
    (fn [conn]
      (testing "single retain goes through accumulator via flush timer"
        (let [deps (make-deps conn)
              flow (start-flow-with-batch-config! deps 64 20)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items     [{:content "User likes Clojure"
                                                               :source  "conversation"}]
                                                  :namespace "default"})]
              (is (some? result) "single retain should complete")
              (is (= 1 (count (:memory-ids result))))
              (is (= "CREATE" (:type (first (:decisions result))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest batch-retain-works-with-accumulator
  (th/with-datahike
    (fn [conn]
      (testing "batch retain with multiple items goes through accumulator"
        (let [deps (make-deps conn
                              {:extract-result
                               [{:content "User likes Clojure" :layer "fact"}
                                {:content "User is a developer" :layer "concept"}
                                {:content "User prefers dark mode" :layer "fact"}]})
              flow (start-flow-with-batch-config! deps 64 20)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "I'm a dev who likes Clojure and dark mode"
                                                           :source  "conversation"}]
                                                  :namespace "default"})]
              (is (some? result) "batch retain should complete")
              (is (= 3 (count (:memory-ids result))))
              (is (every? #(= "CREATE" (:type %)) (:decisions result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest small-batch-size-forces-flush-on-full
  (th/with-datahike
    (fn [conn]
      (testing "with batch-size 2, 3 extracted memories flush in 2 batches"
        (let [deps (make-deps conn
                              {:extract-result
                               [{:content "Fact A" :layer "fact"}
                                {:content "Fact B" :layer "fact"}
                                {:content "Fact C" :layer "fact"}]})
              ;; batch-size=2 means first 2 flush immediately, 3rd waits for timer
              flow (start-flow-with-batch-config! deps 2 20)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "Three facts here"
                                                           :source  "test"}]
                                                  :namespace "default"})]
              (is (some? result) "should complete with small batch size")
              (is (= 3 (count (:memory-ids result)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest concurrent-retains-complete
  (th/with-datahike
    (fn [conn]
      (testing "multiple concurrent single retains all complete"
        (let [deps (make-deps conn)
              flow (start-flow-with-batch-config! deps 64 20)
              n    5]
          (try
            (let [futures (mapv (fn [i]
                                  (future
                                    (retention-flow/submit! flow
                                                            {:items [{:content (str "Memory number " i)
                                                                      :source  "test"}]
                                                             :namespace "default"})))
                                (range n))
                  results (mapv deref futures)]
              (is (every? some? results) "all retains should complete")
              (is (= n (count (mapcat :memory-ids results)))))
            (finally
              (th/stop-test-flow! flow))))))))
