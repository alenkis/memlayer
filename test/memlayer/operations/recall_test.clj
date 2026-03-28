(ns memlayer.operations.recall-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.operations.recall :as recall]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.proximum :as prox]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.test-helpers :as th]))

(defn- make-deps [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx (atom (prox/->ProximumVectorStore
                          (prox/create-index! prox-config) prox-config))]
    {:db                 conn
     :vector-index       vector-idx
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (th/mock-flow-provider)
     :prompts            th/mock-prompts
     :tuning             {}}))

(deftest recall-finds-stored-memory
  (th/with-datahike
    (fn [conn]
      (testing "recall finds a memory after retain stores it"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (retention-flow/submit! flow
                                    {:items     [{:content "User prefers dark mode" :source "conversation"}]
                                     :namespace "prefs"})
            (let [result (recall/recall! deps {:query     "User prefers dark mode"
                                               :namespace "prefs"
                                               :limit     5
                                               :threshold 1.0})]
              (is (= "User prefers dark mode" (:query result)))
              (is (pos? (:count result)))
              (is (seq (:memories result)))
              (let [mem (first (:memories result))]
                (is (string? (:memory-id mem)))
                (is (= "User prefers dark mode" (:content mem)))
                (is (= "fact" (:layer mem)))
                (is (number? (:distance mem)))
                (is (= 0.0 (:distance mem)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-returns-empty-when-no-matches
  (th/with-datahike
    (fn [conn]
      (testing "recall returns empty when vector index is empty"
        (let [deps   (make-deps conn)
              result (recall/recall! deps {:query "anything"
                                           :limit 5
                                           :threshold 0.5})]
          (is (= 0 (:count result)))
          (is (empty? (:memories result))))))))

(deftest recall-respects-namespace-filter
  (th/with-datahike
    (fn [conn]
      (testing "recall filters by namespace"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (retention-flow/submit! flow
                                    {:items     [{:content "User prefers dark mode" :source "conversation"}]
                                     :namespace "prefs"})
            (let [all-result (recall/recall! deps {:query     "User prefers dark mode"
                                                   :namespace "prefs"
                                                   :limit     10
                                                   :threshold 1.0})
                  filtered-result (recall/recall! deps {:query     "User prefers dark mode"
                                                        :namespace "tech"
                                                        :limit     10
                                                        :threshold 1.0})]
              (is (pos? (:count all-result)))
              (is (= 0 (:count filtered-result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-respects-limit
  (th/with-datahike
    (fn [conn]
      (testing "recall limits results"
        (let [deps (assoc (make-deps conn)
                          :chat-provider (th/mock-flow-provider
                                          {:extract-result
                                           [{:content "Fact one" :layer "fact" :importance 0.5}
                                            {:content "Fact two" :layer "fact" :importance 0.6}
                                            {:content "Fact three" :layer "fact" :importance 0.7}]}))
              flow (th/start-test-flow! deps)]
          (try
            (retention-flow/submit! flow
                                    {:items [{:content "Multiple facts" :source "test"}]})
            (let [result (recall/recall! deps {:query "Fact one"
                                               :limit 2
                                               :threshold 2.0})]
              (is (<= (:count result) 2)))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-includes-distance-scores
  (th/with-datahike
    (fn [conn]
      (testing "recall results include distance scores"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (retention-flow/submit! flow
                                    {:items [{:content "User prefers dark mode" :source "test"}]})
            (let [result (recall/recall! deps {:query     "User prefers dark mode"
                                               :threshold 1.0})]
              (is (pos? (:count result)))
              (doseq [mem (:memories result)]
                (is (number? (:distance mem)))
                (is (= 0.0 (:distance mem)))))
            (finally
              (th/stop-test-flow! flow))))))))

;; ---------------------------------------------------------------------------
;; Graph traversal tests
;; ---------------------------------------------------------------------------

(defn- embed-text
  "Get a deterministic embedding for test text."
  [provider text]
  (:embedding (llm-provider/embed provider text)))

(defn- setup-knowledge-graph!
  "Create a domain → concept → facts hierarchy with embeddings in the vector index.
   Returns {:domain-id :concept-id :fact-ids :summary-id :rel-id}."
  [conn vector-index embed-provider]
  (let [domain-id  (dh/insert-memory! conn {:memory/content   "Programming Languages"
                                            :memory/layer     :layer/domain
                                            :memory/namespace "test"})
        concept-id (dh/insert-memory! conn {:memory/content   "Functional Programming"
                                            :memory/layer     :layer/concept
                                            :memory/namespace "test"
                                            :memory/parent-id domain-id})
        fact1-id   (dh/insert-memory! conn {:memory/content    "Clojure uses immutable data structures"
                                            :memory/layer      :layer/fact
                                            :memory/namespace  "test"
                                            :memory/importance (float 0.8)
                                            :memory/source     "docs"
                                            :memory/parent-id  concept-id})
        fact2-id   (dh/insert-memory! conn {:memory/content    "Haskell enforces pure functions"
                                            :memory/layer      :layer/fact
                                            :memory/namespace  "test"
                                            :memory/importance (float 0.7)
                                            :memory/source     "docs"
                                            :memory/parent-id  concept-id})
        summary-id (dh/insert-memory! conn {:memory/content   "FP emphasizes immutability and pure functions"
                                            :memory/layer     :layer/summary
                                            :memory/namespace "test"
                                            :memory/parent-id concept-id})
        rel-id     (dh/insert-relationship! conn {:source-id   fact1-id
                                                  :target-id   fact2-id
                                                  :type        :related-to
                                                  :confidence  0.9
                                                  :description "Both describe FP language features"})]
    ;; Store embeddings for facts (so vector search can find them)
    (swap! vector-index protocols/upsert! (str fact1-id) (embed-text embed-provider "Clojure uses immutable data structures"))
    (swap! vector-index protocols/upsert! (str fact2-id) (embed-text embed-provider "Haskell enforces pure functions"))
    {:domain-id  domain-id
     :concept-id concept-id
     :fact-ids   [fact1-id fact2-id]
     :summary-id summary-id
     :rel-id     rel-id}))

(deftest expand-graph-walks-ancestors
  (th/with-datahike
    (fn [conn]
      (testing "expand-graph returns ancestor chain (concept + domain)"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                           :namespace    "test"
                                           :expand-graph true
                                           :threshold    1.0})
              mem    (first (:memories result))]
          (is (pos? (:count result)))
          (is (= 2 (count (:ancestors mem))))
          (is (= "concept" (:layer (first (:ancestors mem)))))
          (is (= "domain" (:layer (second (:ancestors mem))))))))))

(deftest expand-graph-includes-summaries
  (th/with-datahike
    (fn [conn]
      (testing "expand-graph returns summaries for ancestors"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                           :namespace    "test"
                                           :expand-graph true
                                           :threshold    1.0})
              mem    (first (:memories result))]
          (is (= 1 (count (:summaries mem))))
          (is (= "summary" (:layer (first (:summaries mem))))))))))

(deftest expand-graph-includes-siblings
  (th/with-datahike
    (fn [conn]
      (testing "expand-graph returns sibling facts under same parent"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                           :namespace    "test"
                                           :expand-graph true
                                           :threshold    1.0})
              mem    (first (:memories result))]
          (is (pos? (count (:siblings mem))))
          (is (= "fact" (:layer (first (:siblings mem))))))))))

(deftest expand-graph-includes-relationships-with-descriptions
  (th/with-datahike
    (fn [conn]
      (testing "expand-graph returns related memories with relationship descriptions"
        (let [deps    (make-deps conn)
              _       (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result  (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                            :namespace    "test"
                                            :expand-graph true
                                            :threshold    1.0})
              mem     (first (:memories result))
              related (:related mem)]
          (is (pos? (count related)))
          (let [rel (first related)]
            (is (string? (:id rel)))
            (is (string? (:description rel)))
            (is (= "Both describe FP language features" (:description rel)))
            (is (= "related-to" (:type rel)))))))))

(deftest expand-graph-returns-aggregate-graph-context
  (th/with-datahike
    (fn [conn]
      (testing "expand-graph adds top-level :graph key with deduplicated context"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                           :namespace    "test"
                                           :expand-graph true
                                           :threshold    2.0})]
          (is (contains? result :graph))
          (let [graph (:graph result)]
            (is (vector? (:concepts graph)))
            (is (vector? (:summaries graph)))
            (is (vector? (:relationships graph)))
            (is (pos? (count (:concepts graph))))
            (is (pos? (count (:summaries graph))))
            (is (pos? (count (:relationships graph))))
            (let [rel (first (:relationships graph))]
              (is (string? (:description rel))))))))))

(deftest graph-reranking-boosts-shared-parents
  (testing "memories sharing a parent get a distance reduction"
    (let [parent-id (str (java.util.UUID/randomUUID))
          memories  [{:memory-id "a" :parent-id parent-id :distance 0.5}
                     {:memory-id "b" :parent-id parent-id :distance 0.6}
                     {:memory-id "c" :parent-id nil       :distance 0.4}]
          reranked  (#'recall/apply-graph-reranking memories)]
      ;; a and b share a parent (count=2), each gets -0.05 bonus
      (is (< (abs (- 0.45 (:distance (first reranked)))) 1e-9))
      (is (< (abs (- 0.55 (:distance (second reranked)))) 1e-9))
      ;; c has no parent, unchanged
      (is (= 0.4 (:distance (nth reranked 2)))))))

(deftest expand-graph-absent-without-flag
  (th/with-datahike
    (fn [conn]
      (testing "graph key absent when expand-graph is not set"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query     "Clojure uses immutable data structures"
                                           :namespace "test"
                                           :threshold 1.0})
              mem    (first (:memories result))]
          (is (not (contains? result :graph)))
          (is (not (contains? mem :ancestors))))))))

;; ---------------------------------------------------------------------------
;; Answer generation tests
;; ---------------------------------------------------------------------------

(deftest recall-always-generates-answer
  (th/with-datahike
    (fn [conn]
      (testing "recall returns a generated :answer when memories match"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (retention-flow/submit! flow
                                    {:items     [{:content "User prefers dark mode" :source "conversation"}]
                                     :namespace "prefs"})
            (let [result (recall/recall! deps {:query     "User prefers dark mode"
                                               :namespace "prefs"
                                               :threshold 1.0})]
              (is (pos? (:count result)))
              (is (contains? result :answer))
              (is (string? (:answer result)))
              (is (seq (:answer result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest recall-no-answer-when-no-memories
  (th/with-datahike
    (fn [conn]
      (testing "no :answer when there are no matching memories"
        (let [deps   (make-deps conn)
              result (recall/recall! deps {:query     "anything"
                                           :limit     5
                                           :threshold 0.5})]
          (is (= 0 (:count result)))
          (is (not (contains? result :answer))))))))

(deftest recall-generates-answer-with-graph-expansion
  (th/with-datahike
    (fn [conn]
      (testing "recall with expand-graph returns both :answer and :graph"
        (let [deps   (make-deps conn)
              _      (setup-knowledge-graph! conn (:vector-index deps) (:embedding-provider deps))
              result (recall/recall! deps {:query        "Clojure uses immutable data structures"
                                           :namespace    "test"
                                           :expand-graph true
                                           :threshold    1.0})]
          (is (pos? (:count result)))
          (is (contains? result :answer))
          (is (string? (:answer result)))
          (is (contains? result :graph))
          (is (seq (:concepts (:graph result)))))))))

;; ---------------------------------------------------------------------------
;; Generative / property-based tests
;; ---------------------------------------------------------------------------

(def gen-uuid-str
  (gen/fmap str gen/uuid))

(def gen-reranking-memory
  (gen/let [id gen-uuid-str
            has-parent gen/boolean
            parent-id gen-uuid-str
            distance (gen/double* {:min 0.0 :max 2.0 :NaN? false :infinite? false})]
    {:memory-id id
     :parent-id (when has-parent parent-id)
     :distance  distance}))

(defspec reranking-preserves-count 100
  (prop/for-all [memories (gen/vector gen-reranking-memory 0 30)]
                (= (count memories)
                   (count (#'recall/apply-graph-reranking memories)))))

(defspec reranking-no-parent-unchanged 100
  (prop/for-all [memories (gen/vector gen-reranking-memory 1 30)]
                (let [reranked (#'recall/apply-graph-reranking memories)]
                  (every? true?
                          (map (fn [orig ranked]
                                 (if (nil? (:parent-id orig))
                                   (= (:distance orig) (:distance ranked))
                                   true))
                               memories reranked)))))

(defspec reranking-never-increases-distance 100
  (prop/for-all [memories (gen/vector gen-reranking-memory 1 30)]
                (let [reranked (#'recall/apply-graph-reranking memories)]
                  (every? true?
                          (map (fn [orig ranked]
                                 (<= (:distance ranked) (:distance orig)))
                               memories reranked)))))
