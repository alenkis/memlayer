(ns memlayer.operations.flow.embed-batch-bench
  "Benchmark: fires concurrent retains with counting mock providers
   to verify batching effectiveness and correctness under load."
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.persistence.proximum :as prox]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.test-helpers :as th]))

;; ---------------------------------------------------------------------------
;; Counting mock providers — track embed vs embed-batch calls
;; ---------------------------------------------------------------------------

(defn counting-embedding-provider
  "Mock EmbeddingProvider that counts single vs batch calls and adds simulated latency."
  [{:keys [dim latency-ms] :or {dim 64 latency-ms 0}}]
  (let [embed-count      (atom 0)
        embed-batch-count (atom 0)
        total-texts      (atom 0)
        inner            (th/mock-embedding-provider {:dim dim})]
    {:provider
     (reify llm-provider/EmbeddingProvider
       (embed [_ text]
         (swap! embed-count inc)
         (swap! total-texts inc)
         (when (pos? latency-ms)
           (Thread/sleep ^long latency-ms))
         (llm-provider/embed inner text))
       (embed-batch [_ texts]
         (swap! embed-batch-count inc)
         (swap! total-texts + (count texts))
         (when (pos? latency-ms)
           (Thread/sleep ^long latency-ms))
         (llm-provider/embed-batch inner texts)))
     :stats {:embed-count       embed-count
             :embed-batch-count embed-batch-count
             :total-texts       total-texts}}))

(defn- make-bench-deps
  [conn embedding-provider]
  (let [prox-config {:dim 64 :capacity 10000}
        vector-idx  (atom (prox/->ProximumVectorStore
                           (prox/create-index! prox-config) prox-config))]
    {:db                 conn
     :vector-index       vector-idx
     :embedding-provider embedding-provider
     :chat-provider      (th/mock-flow-provider)
     :prompts            th/mock-prompts
     :tuning             {}}))

(defn- run-concurrent-retains!
  "Fire n concurrent single-retain calls and return all results."
  [flow n]
  (let [futures (mapv (fn [i]
                        (future
                          (retention-flow/submit! flow
                                                  {:items [{:content (str "Benchmark memory item " i
                                                                          " with unique content hash " (hash i))
                                                            :source  "bench"}]
                                                   :namespace "bench"})))
                      (range n))]
    (mapv (fn [f] (deref f 60000 nil)) futures)))

(deftest ^:bench bench-1-retain
  (th/with-datahike
    (fn [conn]
      (testing "1 concurrent retain — baseline correctness"
        (let [{:keys [provider stats]} (counting-embedding-provider {:dim 64 :latency-ms 10})
              deps (make-bench-deps conn provider)
              flow (retention-flow/start-standalone! deps
                                                     {:flow {:io-threads 8 :submit-timeout-ms 30000
                                                             :embed-batch-size 64 :embed-batch-wait-ms 30}})]
          (try
            (let [results (run-concurrent-retains! flow 1)]
              (is (every? some? results) "all retains completed")
              (is (= 1 (count (mapcat :memory-ids results)))
                  "expected 1 memory created")
              ;; With only 1 item, it goes through embed-batch via flush timer (batch of 1)
              (is (pos? @(:total-texts stats)) "at least 1 text embedded"))
            (finally
              (retention-flow/stop-standalone! flow))))))))

(deftest ^:bench bench-10-retains
  (th/with-datahike
    (fn [conn]
      (testing "10 concurrent retains — batching should kick in"
        (let [{:keys [provider stats]} (counting-embedding-provider {:dim 64 :latency-ms 10})
              deps (make-bench-deps conn provider)
              flow (retention-flow/start-standalone! deps
                                                     {:flow {:io-threads 8 :submit-timeout-ms 30000
                                                             :embed-batch-size 64 :embed-batch-wait-ms 30}})]
          (try
            (let [results (run-concurrent-retains! flow 10)
                  memory-ids (mapcat :memory-ids results)]
              (is (every? some? results) "all 10 retains completed")
              (is (= 10 (count memory-ids)) "expected 10 memories created")
              (is (= 10 @(:total-texts stats)) "all 10 texts embedded")
              ;; Key assertion: fewer embed-batch calls than total texts
              (let [batch-calls @(:embed-batch-count stats)
                    single-calls @(:embed-count stats)]
                (is (< batch-calls 10)
                    (str "batching should reduce calls: " batch-calls " batch calls for 10 texts"))
                (is (zero? single-calls)
                    (str "no single embed calls expected, got " single-calls))))
            (finally
              (retention-flow/stop-standalone! flow))))))))

(deftest ^:bench bench-100-retains
  (th/with-datahike
    (fn [conn]
      (testing "100 concurrent retains — sustained batching"
        (let [{:keys [provider stats]} (counting-embedding-provider {:dim 64 :latency-ms 10})
              deps (make-bench-deps conn provider)
              flow (retention-flow/start-standalone! deps
                                                     {:flow {:io-threads 16 :submit-timeout-ms 60000
                                                             :embed-batch-size 64 :embed-batch-wait-ms 30}})]
          (try
            (let [start-ms (System/currentTimeMillis)
                  results  (run-concurrent-retains! flow 100)
                  elapsed  (- (System/currentTimeMillis) start-ms)
                  memory-ids (mapcat :memory-ids results)]
              (is (every? some? results) "all 100 retains completed")
              (is (= 100 (count memory-ids)) "expected 100 memories created")
              (is (= 100 @(:total-texts stats)) "all 100 texts embedded")
              (let [batch-calls  @(:embed-batch-count stats)
                    single-calls @(:embed-count stats)]
                (is (< batch-calls 100)
                    (str "batching should reduce calls significantly: " batch-calls " batch calls for 100 texts"))
                (is (zero? single-calls)
                    (str "no single embed calls expected, got " single-calls))
                ;; With 10ms latency per batch call and batching, wall time should be
                ;; much less than 100 * 10ms = 1000ms
                (is (< elapsed 5000)
                    (str "should complete in <5s with batching, took " elapsed "ms"))))
            (finally
              (retention-flow/stop-standalone! flow))))))))

(deftest ^:bench bench-250-retains
  (th/with-datahike
    (fn [conn]
      (testing "250 concurrent retains — high-load batching"
        (let [{:keys [provider stats]} (counting-embedding-provider {:dim 64 :latency-ms 10})
              deps (make-bench-deps conn provider)
              flow (retention-flow/start-standalone! deps
                                                     {:flow {:io-threads 16 :submit-timeout-ms 120000
                                                             :embed-batch-size 64 :embed-batch-wait-ms 30}})]
          (try
            (let [start-ms (System/currentTimeMillis)
                  results  (run-concurrent-retains! flow 250)
                  elapsed  (- (System/currentTimeMillis) start-ms)
                  memory-ids (mapcat :memory-ids results)
                  completed  (count (filter some? results))]
              (is (= 250 completed)
                  (str "all 250 retains should complete, got " completed))
              (is (= 250 (count memory-ids))
                  (str "expected 250 memories, got " (count memory-ids)))
              (is (= 250 @(:total-texts stats)) "all 250 texts embedded")
              (let [batch-calls  @(:embed-batch-count stats)
                    single-calls @(:embed-count stats)]
                (is (< batch-calls 50)
                    (str "batching should be efficient: " batch-calls " batch calls for 250 texts"))
                (is (zero? single-calls)
                    (str "no single embed calls expected, got " single-calls))
                (is (< elapsed 30000)
                    (str "should complete in <30s with batching, took " elapsed "ms"))))
            (finally
              (retention-flow/stop-standalone! flow))))))))
