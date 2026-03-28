(ns memlayer.operations.reflect-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.operations.reflect :as reflect]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.proximum :as prox]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.test-helpers :as th]))

;; ---------------------------------------------------------------------------
;; Mock providers
;; ---------------------------------------------------------------------------

(defn- mock-organize-provider
  "Mock chat provider that handles both organize-facts and organize-concepts calls.
   Distinguishes by checking the system prompt content."
  []
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (if (and system-content (.contains ^String system-content "domain"))
         {:groups [{:domain-id       nil
                    :domain-name     "Test Domain"
                    :domain-content  "A test domain grouping concepts"
                    :concept-indices [0]}]}
         {:groups [{:concept-id      nil
                    :concept-name    "Test Concept"
                    :concept-content "A test concept grouping facts"
                    :fact-indices    [0 1]}]})))))

(defn- mock-organize-provider-with-existing
  "Mock that assigns facts to an existing concept by ID."
  [existing-concept-id]
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (if (and system-content (.contains ^String system-content "domain"))
         {:groups []}
         {:groups [{:concept-id   (str existing-concept-id)
                    :fact-indices [0]}]})))))

(defn- mock-organize-provider-multi-group
  "Mock that returns multiple concept groups from a single batch."
  []
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (if (and system-content (.contains ^String system-content "domain"))
         {:groups [{:domain-id       nil
                    :domain-name     "Test Domain"
                    :domain-content  "Groups multiple concepts"
                    :concept-indices [0 1]}]}
         {:groups [{:concept-id      nil
                    :concept-name    "Concept A"
                    :concept-content "First concept"
                    :fact-indices    [0]}
                   {:concept-id      nil
                    :concept-name    "Concept B"
                    :concept-content "Second concept"
                    :fact-indices    [1]}]})))))

(defn- mock-failing-provider
  "Mock that throws on every call."
  []
  (th/mock-chat-provider
   (fn [_msgs _opts]
     (throw (ex-info "LLM unavailable" {})))))

;; ---------------------------------------------------------------------------
;; Test helpers
;; ---------------------------------------------------------------------------

(defn- make-deps
  ([conn] (make-deps conn (mock-organize-provider)))
  ([conn chat-provider]
   (let [prox-config {:dim 64 :capacity 1000}
         vector-idx  (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config)]
     {:db                 conn
      :vector-index       (atom vector-idx)
      :embedding-provider (th/mock-embedding-provider {:dim 64})
      :chat-provider      chat-provider
      :prompts            th/mock-prompts})))

(defn- insert-orphan-fact! [conn content namespace]
  (dh/insert-memory! conn {:memory/content    content
                           :memory/layer      :layer/fact
                           :memory/importance (float 0.5)
                           :memory/source     "test"
                           :memory/namespace  namespace}))

(defn- insert-parented-fact! [conn content namespace parent-id]
  (dh/insert-memory! conn {:memory/content    content
                           :memory/layer      :layer/fact
                           :memory/importance (float 0.5)
                           :memory/source     "test"
                           :memory/namespace  namespace
                           :memory/parent-id  parent-id}))

(defn- insert-concept! [conn content namespace]
  (dh/insert-memory! conn {:memory/content    content
                           :memory/layer      :layer/concept
                           :memory/importance (float 0.8)
                           :memory/source     "test"
                           :memory/namespace  namespace}))

;; ---------------------------------------------------------------------------
;; Organize phase tests
;; ---------------------------------------------------------------------------

(deftest organize-empty-database
  (th/with-datahike
    (fn [conn]
      (testing "organize with no orphan facts returns zeros"
        (let [deps   (make-deps conn)
              result (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 0 (:facts-processed result)))
          (is (= 0 (:concepts-created result)))
          (is (= 0 (:concepts-reused result)))
          (is (= 0 (:domains-created result))))))))

(deftest organize-skips-parented-facts
  (th/with-datahike
    (fn [conn]
      (testing "facts with a parent-id are not processed"
        (let [parent-id (insert-concept! conn "Parent Concept" "test-ns")]
          (insert-parented-fact! conn "Already parented" "test-ns" parent-id)
          (insert-orphan-fact! conn "Orphan fact" "test-ns")
          (insert-orphan-fact! conn "Another orphan" "test-ns")

          (let [deps   (make-deps conn)
                result (reflect/organize! deps {:namespace "test-ns"})]
            (is (= 2 (:facts-processed result))
                "Should only count orphan facts")))))))

(deftest organize-creates-concept-with-correct-attributes
  (th/with-datahike
    (fn [conn]
      (testing "created concept has correct layer, source, importance, and namespace"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn)
              _      (reflect/organize! deps {:namespace "test-ns"})
              concepts (dh/get-concepts conn :namespace "test-ns")]
          (is (= 1 (count concepts)))
          (let [c (first concepts)]
            (is (= :layer/concept (:memory/layer c)))
            (is (= "reflect" (:memory/source c)))
            (is (= "test-ns" (:memory/namespace c)))
            (is (= (float 0.8) (:memory/importance c)))
            (is (string? (:memory/content c)))))))))

(deftest organize-links-all-facts-in-group
  (th/with-datahike
    (fn [conn]
      (testing "all facts in a group get linked to the created concept"
        (let [f1   (insert-orphan-fact! conn "Fact 1" "test-ns")
              f2   (insert-orphan-fact! conn "Fact 2" "test-ns")
              deps (make-deps conn)
              _    (reflect/organize! deps {:namespace "test-ns"})
              fact1 (dh/get-memory conn f1)
              fact2 (dh/get-memory conn f2)]
          (is (some? (:memory/parent-id fact1)) "Fact 1 should have a parent")
          (is (some? (:memory/parent-id fact2)) "Fact 2 should have a parent")
          (is (= (:memory/parent-id fact1) (:memory/parent-id fact2))
              "Both facts should share the same parent concept"))))))

(deftest organize-context-aware-reuses-existing
  (th/with-datahike
    (fn [conn]
      (testing "assigns facts to existing concepts when LLM returns concept-id"
        (let [concept-id (insert-concept! conn "Existing Concept" "test-ns")
              fact-id    (insert-orphan-fact! conn "A new fact" "test-ns")
              deps       (make-deps conn (mock-organize-provider-with-existing concept-id))
              result     (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 1 (:facts-processed result)))
          (is (= 0 (:concepts-created result)))
          (is (= 1 (:concepts-reused result)))
          (let [fact (dh/get-memory conn fact-id)]
            (is (= concept-id (:memory/parent-id fact)))))))))

(deftest organize-multiple-groups-per-batch
  (th/with-datahike
    (fn [conn]
      (testing "LLM returning multiple groups creates multiple concepts"
        (let [f1     (insert-orphan-fact! conn "Fact about A" "test-ns")
              f2     (insert-orphan-fact! conn "Fact about B" "test-ns")
              deps   (make-deps conn (mock-organize-provider-multi-group))
              result (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 2 (:facts-processed result)))
          (is (= 2 (:concepts-created result)))
          ;; Verify each fact has a different parent concept
          (let [fact1 (dh/get-memory conn f1)
                fact2 (dh/get-memory conn f2)]
            (is (some? (:memory/parent-id fact1)))
            (is (some? (:memory/parent-id fact2)))
            (is (not= (:memory/parent-id fact1) (:memory/parent-id fact2)))))))))

(deftest organize-stores-concept-embedding
  (th/with-datahike
    (fn [conn]
      (testing "created concepts are stored in the vector index"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn)
              _      (reflect/organize! deps {:namespace "test-ns"})
              concepts (dh/get-concepts conn :namespace "test-ns")
              idx    @(:vector-index deps)]
          (is (= 1 (count concepts)))
          ;; Verify concept is searchable in vector index
          (let [concept-id (str (:memory/id (first concepts)))
                results    (protocols/search idx (float-array 64) 10)]
            (is (some #(= concept-id (:id %)) results)
                "Concept should be findable in vector index")))))))

(deftest organize-batch-failure-doesnt-abort
  (th/with-datahike
    (fn [conn]
      (testing "LLM failure on a batch doesn't crash the operation"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn (mock-failing-provider))
              result (reflect/organize! deps {:namespace "test-ns"})]
          ;; Should return results even though batches failed
          (is (= 2 (:facts-processed result)))
          (is (= 0 (:concepts-created result))))))))

;; ---------------------------------------------------------------------------
;; Domain creation tests
;; ---------------------------------------------------------------------------

(deftest organize-creates-domains-for-multiple-concepts
  (th/with-datahike
    (fn [conn]
      (testing "domains are created when multiple orphan concepts exist"
        ;; Create 3 facts so the multi-group mock creates 2 concepts
        ;; (but our mock only handles 2 indices, so use 2 facts)
        (insert-orphan-fact! conn "Fact about A" "test-ns")
        (insert-orphan-fact! conn "Fact about B" "test-ns")

        (let [deps   (make-deps conn (mock-organize-provider-multi-group))
              result (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 2 (:concepts-created result)))
          (is (= 1 (:domains-created result)))
          ;; Verify domain exists in DB
          (let [domains (dh/get-domains conn :namespace "test-ns")]
            (is (= 1 (count domains)))
            (is (= :layer/domain (:memory/layer (first domains))))
            (is (= "reflect" (:memory/source (first domains))))))))))

(deftest organize-skips-domains-with-single-concept
  (th/with-datahike
    (fn [conn]
      (testing "domain creation is skipped when there's only one orphan concept"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn) ;; default mock creates 1 concept
              result (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 1 (:concepts-created result)))
          (is (= 0 (:domains-created result))))))))

(deftest organize-concepts-linked-to-domain
  (th/with-datahike
    (fn [conn]
      (testing "after domain creation, concepts have parent-id pointing to domain"
        (insert-orphan-fact! conn "Fact about A" "test-ns")
        (insert-orphan-fact! conn "Fact about B" "test-ns")

        (let [deps   (make-deps conn (mock-organize-provider-multi-group))
              _      (reflect/organize! deps {:namespace "test-ns"})
              concepts (dh/get-concepts conn :namespace "test-ns")
              domains  (dh/get-domains conn :namespace "test-ns")]
          (is (= 1 (count domains)))
          ;; Both concepts should be children of the domain
          (let [domain-id (:memory/id (first domains))]
            (doseq [c concepts]
              (is (= domain-id (:memory/parent-id c))
                  (str "Concept '" (:memory/content c) "' should be child of domain")))))))))

;; ---------------------------------------------------------------------------
;; Namespace scoping tests
;; ---------------------------------------------------------------------------

(deftest organize-scopes-to-namespace
  (th/with-datahike
    (fn [conn]
      (testing "organize only processes orphan facts in the given namespace"
        (insert-orphan-fact! conn "Fact in ns-a" "ns-a")
        (insert-orphan-fact! conn "Another fact in ns-a" "ns-a")
        (insert-orphan-fact! conn "Fact in ns-b" "ns-b")

        (let [deps   (make-deps conn)
              result (reflect/organize! deps {:namespace "ns-a"})]
          (is (= 2 (:facts-processed result)))
          ;; ns-b fact should still be an orphan
          (is (= 1 (count (dh/get-orphan-facts conn :namespace "ns-b")))))))))

(deftest organize-without-namespace-processes-all
  (th/with-datahike
    (fn [conn]
      (testing "organize without namespace processes all orphan facts"
        (insert-orphan-fact! conn "Fact A" "ns-a")
        (insert-orphan-fact! conn "Fact B" "ns-b")

        (let [deps   (make-deps conn)
              result (reflect/organize! deps {})]
          (is (= 2 (:facts-processed result))))))))

;; ---------------------------------------------------------------------------
;; Idempotency tests
;; ---------------------------------------------------------------------------

(deftest organize-idempotent-second-run
  (th/with-datahike
    (fn [conn]
      (testing "running organize twice doesn't re-process already-parented facts"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps    (make-deps conn)
              result1 (reflect/organize! deps {:namespace "test-ns"})
              result2 (reflect/organize! deps {:namespace "test-ns"})]
          (is (= 2 (:facts-processed result1)))
          (is (= 1 (:concepts-created result1)))
          ;; Second run: no orphans left
          (is (= 0 (:facts-processed result2)))
          (is (= 0 (:concepts-created result2))))))))

;; ---------------------------------------------------------------------------
;; reflect! orchestrator tests
;; ---------------------------------------------------------------------------

(deftest reflect-scopes-to-namespace
  (th/with-datahike
    (fn [conn]
      (testing "reflect only processes orphan facts in the given namespace"
        (insert-orphan-fact! conn "Fact in ns-a" "ns-a")
        (insert-orphan-fact! conn "Another fact in ns-a" "ns-a")
        (insert-orphan-fact! conn "Fact in ns-b" "ns-b")

        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "ns-a"})]
          (is (= 2 (:facts-processed result)))
          (is (= 1 (:concepts-created result))))))))

(deftest reflect-dry-run
  (th/with-datahike
    (fn [conn]
      (testing "dry run reports facts but creates nothing"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "test-ns" :dry-run true})]
          (is (= 2 (:facts-processed result)))
          (is (= 0 (:concepts-created result)))
          (is (true? (:dry-run result)))
          ;; Verify nothing was actually created
          (is (empty? (dh/get-concepts conn :namespace "test-ns")))
          (is (= 2 (count (dh/get-orphan-facts conn :namespace "test-ns")))))))))

(deftest reflect-dry-run-no-side-effects
  (th/with-datahike
    (fn [conn]
      (testing "dry run with query doesn't modify any facts"
        (let [f1    (insert-orphan-fact! conn "Fact 1" "test-ns")
              f2    (insert-orphan-fact! conn "Fact 2" "test-ns")
              deps  (make-deps conn)
              _     (reflect/reflect! deps {:namespace "test-ns" :dry-run true})
              fact1 (dh/get-memory conn f1)
              fact2 (dh/get-memory conn f2)]
          (is (nil? (:memory/parent-id fact1)))
          (is (nil? (:memory/parent-id fact2))))))))

(deftest reflect-phases-only-organize
  (th/with-datahike
    (fn [conn]
      (testing "phases=[organize] only runs organize"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "test-ns"
                                             :phases    ["organize"]})]
          (is (= 2 (:facts-processed result)))
          (is (= 1 (:concepts-created result)))
          (is (= 0 (get-in result [:summarize :summaries-created])))
          (is (= 0 (get-in result [:connect :relationships-created])))
          (is (= 0 (get-in result [:curate :contradictions-found]))))))))

(deftest reflect-phases-skip-organize
  (th/with-datahike
    (fn [conn]
      (testing "phases=[summarize] skips organize"
        (insert-orphan-fact! conn "Fact 1" "test-ns")

        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "test-ns"
                                             :phases    ["summarize"]})]
          ;; organize was skipped, so facts-processed should be 0
          (is (= 0 (:facts-processed result)))
          (is (= 0 (:concepts-created result)))
          ;; facts should still be orphans
          (is (= 1 (count (dh/get-orphan-facts conn :namespace "test-ns")))))))))

(deftest reflect-empty-database
  (th/with-datahike
    (fn [conn]
      (testing "reflect on empty database returns zeros"
        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "test-ns"})]
          (is (= 0 (:facts-processed result)))
          (is (= 0 (:concepts-created result)))
          (is (= 0 (:domains-created result))))))))

(deftest reflect-response-shape
  (th/with-datahike
    (fn [conn]
      (testing "response includes both top-level counts and per-phase detail"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps   (make-deps conn)
              result (reflect/reflect! deps {:namespace "test-ns"})]
          ;; Top-level backward-compatible keys
          (is (number? (:facts-processed result)))
          (is (number? (:concepts-created result)))
          (is (number? (:domains-created result)))
          ;; Per-phase keys
          (is (map? (:organize result)))
          (is (map? (:summarize result)))
          (is (map? (:connect result)))
          (is (map? (:curate result)))
          ;; Organize sub-result
          (is (number? (get-in result [:organize :concepts-created])))
          (is (number? (get-in result [:organize :concepts-reused])))
          (is (number? (get-in result [:organize :domains-created])))
          ;; Details
          (is (vector? (:details result))))))))

(deftest reflect-response-details-per-concept
  (th/with-datahike
    (fn [conn]
      (testing "details array contains info about each created concept"
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")

        (let [deps    (make-deps conn)
              result  (reflect/reflect! deps {:namespace "test-ns"})
              details (:details result)]
          (is (= 1 (count details)))
          (let [d (first details)]
            (is (uuid? (:concept-id d)))
            (is (string? (:content d)))
            (is (number? (:children d)))))))))

(deftest reflect-with-custom-batch-size
  (th/with-datahike
    (fn [conn]
      (testing "tuning batch-size controls how facts are grouped"
        ;; Insert 3 facts, set batch size to 2
        ;; This should create 2 batches: [f1 f2] and [f3]
        (insert-orphan-fact! conn "Fact 1" "test-ns")
        (insert-orphan-fact! conn "Fact 2" "test-ns")
        (insert-orphan-fact! conn "Fact 3" "test-ns")

        (let [deps   (assoc (make-deps conn) :tuning {:reflect-batch-size 2})
              result (reflect/reflect! deps {:namespace "test-ns"})]
          (is (= 3 (:facts-processed result)))
          ;; Each batch creates 1 concept (default mock groups all facts in batch)
          ;; But fact indices [0 1] only covers 2 facts per batch
          ;; Batch 1: facts [0,1] → 1 concept
          ;; Batch 2: facts [0,1] but only 1 fact → still creates concept, links fact at [0]
          (is (pos? (:concepts-created result))))))))

;; ---------------------------------------------------------------------------
;; Datahike query tests
;; ---------------------------------------------------------------------------

(deftest datahike-get-concepts
  (th/with-datahike
    (fn [conn]
      (testing "get-concepts returns only concept-layer memories"
        (insert-concept! conn "Concept A" "test-ns")
        (insert-concept! conn "Concept B" "test-ns")
        (insert-orphan-fact! conn "Fact" "test-ns")

        (is (= 2 (count (dh/get-concepts conn :namespace "test-ns"))))))))

(deftest datahike-get-domains
  (th/with-datahike
    (fn [conn]
      (testing "get-domains returns only domain-layer memories"
        (dh/insert-memory! conn {:memory/content   "Domain A"
                                 :memory/layer     :layer/domain
                                 :memory/importance (float 0.9)
                                 :memory/source    "test"
                                 :memory/namespace "test-ns"})
        (insert-concept! conn "Concept" "test-ns")

        (is (= 1 (count (dh/get-domains conn :namespace "test-ns"))))))))

(deftest datahike-get-orphan-concepts
  (th/with-datahike
    (fn [conn]
      (testing "get-orphan-concepts returns concepts without parent-id"
        (let [domain-id (dh/insert-memory! conn {:memory/content   "Domain"
                                                 :memory/layer     :layer/domain
                                                 :memory/importance (float 0.9)
                                                 :memory/source    "test"
                                                 :memory/namespace "test-ns"})
              _parented (dh/insert-memory! conn {:memory/content   "Parented Concept"
                                                 :memory/layer     :layer/concept
                                                 :memory/importance (float 0.8)
                                                 :memory/source    "test"
                                                 :memory/namespace "test-ns"
                                                 :memory/parent-id domain-id})
              _orphan   (insert-concept! conn "Orphan Concept" "test-ns")]
          (is (= 1 (count (dh/get-orphan-concepts conn :namespace "test-ns")))))))))

(deftest datahike-get-siblings
  (th/with-datahike
    (fn [conn]
      (testing "get-siblings returns other children of the same parent"
        (let [parent-id (insert-concept! conn "Parent" "test-ns")
              f1        (insert-parented-fact! conn "Child 1" "test-ns" parent-id)
              _f2       (insert-parented-fact! conn "Child 2" "test-ns" parent-id)
              _f3       (insert-parented-fact! conn "Child 3" "test-ns" parent-id)
              siblings  (dh/get-siblings conn parent-id :exclude-id f1)]
          (is (= 2 (count siblings)))
          (is (not (some #(= f1 (:memory/id %)) siblings))
              "Should exclude the specified memory"))))))

(deftest datahike-insert-relationship-with-description
  (th/with-datahike
    (fn [conn]
      (testing "insert-relationship! creates relationship with description"
        (let [m1  (insert-concept! conn "Concept A" "test-ns")
              m2  (insert-concept! conn "Concept B" "test-ns")
              rid (dh/insert-relationship! conn {:source-id   m1
                                                 :target-id   m2
                                                 :type        :relates-to
                                                 :confidence  0.8
                                                 :description "Both deal with data structures"})]
          (is (uuid? rid))
          (let [rels (dh/get-relationships-for-memory conn m1)]
            (is (= 1 (count rels)))
            (let [r (first rels)]
              (is (= m1 (:relationship/source-id r)))
              (is (= m2 (:relationship/target-id r)))
              (is (= :relates-to (:relationship/type r)))
              (is (= "Both deal with data structures" (:relationship/description r))))))))))

(deftest datahike-insert-relationship-without-description
  (th/with-datahike
    (fn [conn]
      (testing "insert-relationship! works without description"
        (let [m1  (insert-concept! conn "Concept A" "test-ns")
              m2  (insert-concept! conn "Concept B" "test-ns")
              rid (dh/insert-relationship! conn {:source-id m1 :target-id m2 :type :supports})]
          (is (uuid? rid))
          (let [r (first (dh/get-relationships-for-memory conn m1))]
            (is (nil? (:relationship/description r)))))))))

(deftest datahike-get-summaries-for
  (th/with-datahike
    (fn [conn]
      (testing "get-summaries-for returns summary-layer children"
        (let [concept-id (insert-concept! conn "Test Concept" "test-ns")
              _summary   (dh/insert-memory! conn {:memory/content   "Summary of test concept"
                                                  :memory/layer     :layer/summary
                                                  :memory/importance (float 0.7)
                                                  :memory/source    "reflect"
                                                  :memory/namespace "test-ns"
                                                  :memory/parent-id concept-id})
              summaries (dh/get-summaries-for conn concept-id)]
          (is (= 1 (count summaries)))
          (is (= :layer/summary (:memory/layer (first summaries)))))))))

;; ---------------------------------------------------------------------------
;; Phase 2: Summarize tests
;; ---------------------------------------------------------------------------

(defn- mock-summarize-provider
  "Mock that handles organize + summarize calls.
   Distinguishes by checking the system prompt content."
  []
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (cond
         (and system-content (.contains ^String system-content "summariz"))
         {:summary "This concept covers functional programming topics including immutability and pure functions."}

         (and system-content (.contains ^String system-content "domain"))
         {:groups [{:domain-id       nil
                    :domain-name     "Test Domain"
                    :domain-content  "A test domain"
                    :concept-indices [0]}]}

         :else
         {:groups [{:concept-id      nil
                    :concept-name    "Test Concept"
                    :concept-content "A test concept"
                    :fact-indices    [0 1]}]})))))

(defn- make-summarize-deps
  [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx  (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config)]
    {:db                 conn
     :vector-index       (atom vector-idx)
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (mock-summarize-provider)
     :prompts            th/mock-prompts}))

(deftest summarize-creates-summary-for-concept
  (th/with-datahike
    (fn [conn]
      (testing "summarize! creates a summary node for a concept with children"
        (let [deps       (make-summarize-deps conn)
              concept-id (dh/insert-memory! conn {:memory/content   "Functional Programming"
                                                  :memory/layer     :layer/concept
                                                  :memory/namespace "test"})
              _f1        (dh/insert-memory! conn {:memory/content    "Clojure uses immutable data"
                                                  :memory/layer      :layer/fact
                                                  :memory/namespace  "test"
                                                  :memory/parent-id  concept-id})
              _f2        (dh/insert-memory! conn {:memory/content    "Haskell enforces purity"
                                                  :memory/layer      :layer/fact
                                                  :memory/namespace  "test"
                                                  :memory/parent-id  concept-id})
              result     (reflect/summarize! deps {:namespace "test"})]
          (is (= 1 (:summaries-created result)))
          (let [summaries (dh/get-summaries-for conn concept-id)]
            (is (= 1 (count summaries)))
            (is (= :layer/summary (:memory/layer (first summaries))))
            (is (.contains ^String (:memory/content (first summaries)) "functional programming"))))))))

(deftest summarize-skips-nodes-with-existing-summary
  (th/with-datahike
    (fn [conn]
      (testing "summarize! skips concepts that already have a summary"
        (let [deps       (make-summarize-deps conn)
              concept-id (dh/insert-memory! conn {:memory/content   "Test Concept"
                                                  :memory/layer     :layer/concept
                                                  :memory/namespace "test"})
              _f1        (dh/insert-memory! conn {:memory/content    "Fact one"
                                                  :memory/layer      :layer/fact
                                                  :memory/namespace  "test"
                                                  :memory/parent-id  concept-id})
              _summary   (dh/insert-memory! conn {:memory/content   "Existing summary"
                                                  :memory/layer     :layer/summary
                                                  :memory/namespace "test"
                                                  :memory/parent-id concept-id})
              result     (reflect/summarize! deps {:namespace "test"})]
          (is (= 0 (:summaries-created result)))
          (is (= 1 (count (dh/get-summaries-for conn concept-id)))))))))

(deftest summarize-skips-nodes-without-children
  (th/with-datahike
    (fn [conn]
      (testing "summarize! skips concepts with no children"
        (let [deps (make-summarize-deps conn)
              _    (dh/insert-memory! conn {:memory/content   "Empty Concept"
                                            :memory/layer     :layer/concept
                                            :memory/namespace "test"})
              result (reflect/summarize! deps {:namespace "test"})]
          (is (= 0 (:summaries-created result))))))))

(deftest summarize-handles-domains
  (th/with-datahike
    (fn [conn]
      (testing "summarize! also creates summaries for domains"
        (let [deps      (make-summarize-deps conn)
              domain-id (dh/insert-memory! conn {:memory/content   "Programming"
                                                 :memory/layer     :layer/domain
                                                 :memory/namespace "test"})
              _c1       (dh/insert-memory! conn {:memory/content   "FP Concept"
                                                 :memory/layer     :layer/concept
                                                 :memory/namespace "test"
                                                 :memory/parent-id domain-id})
              result    (reflect/summarize! deps {:namespace "test"})]
          ;; Both the domain and the concept need summaries, but concept has no children
          ;; so only the domain gets one (it has the concept as a child)
          (is (= 1 (:summaries-created result)))
          (is (= 1 (count (dh/get-summaries-for conn domain-id)))))))))

(deftest summarize-idempotent
  (th/with-datahike
    (fn [conn]
      (testing "summarize! is idempotent — running twice doesn't create duplicate summaries"
        (let [deps       (make-summarize-deps conn)
              concept-id (dh/insert-memory! conn {:memory/content   "Test Concept"
                                                  :memory/layer     :layer/concept
                                                  :memory/namespace "test"})
              _f1        (dh/insert-memory! conn {:memory/content    "Fact one"
                                                  :memory/layer      :layer/fact
                                                  :memory/namespace  "test"
                                                  :memory/parent-id  concept-id})
              result1    (reflect/summarize! deps {:namespace "test"})
              result2    (reflect/summarize! deps {:namespace "test"})]
          (is (= 1 (:summaries-created result1)))
          (is (= 0 (:summaries-created result2)))
          (is (= 1 (count (dh/get-summaries-for conn concept-id)))))))))

;; ---------------------------------------------------------------------------
;; Phase 3: Connect tests
;; ---------------------------------------------------------------------------

(defn- mock-connect-provider
  "Mock that handles organize + summarize + connect calls.
   Distinguishes by matching against mock prompt strings."
  []
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (cond
         (= system-content (:reflect-connect th/mock-prompts))
         {:relationships [{:pair-index  0
                           :type        "related-to"
                           :description "Both cover programming paradigms"
                           :confidence  0.85}]}

         (= system-content (:reflect-summarize th/mock-prompts))
         {:summary "Test summary"}

         (= system-content (:reflect-organize-domains th/mock-prompts))
         {:groups []}

         :else
         {:groups [{:concept-id      nil
                    :concept-name    "Test Concept"
                    :concept-content "A test concept"
                    :fact-indices    [0]}]})))))

(defn- make-connect-deps
  [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx  (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config)]
    {:db                 conn
     :vector-index       (atom vector-idx)
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (mock-connect-provider)
     :prompts            th/mock-prompts}))

(defn- setup-concepts-for-connect!
  "Create two concepts with embeddings in vector index. Returns [id-a id-b]."
  [conn deps]
  (let [embed-provider (:embedding-provider deps)
        vector-index   (:vector-index deps)
        embed-fn       (fn [text]
                         (:embedding (llm-provider/embed embed-provider text)))
        id-a (dh/insert-memory! conn {:memory/content   "Functional Programming"
                                      :memory/layer     :layer/concept
                                      :memory/namespace "test"})
        id-b (dh/insert-memory! conn {:memory/content   "Object-Oriented Programming"
                                      :memory/layer     :layer/concept
                                      :memory/namespace "test"})]
    (swap! vector-index protocols/upsert! (str id-a) (embed-fn "Functional Programming"))
    (swap! vector-index protocols/upsert! (str id-b) (embed-fn "Object-Oriented Programming"))
    [id-a id-b]))

(deftest connect-discovers-relationships
  (th/with-datahike
    (fn [conn]
      (testing "connect! creates relationships between concept pairs"
        (let [deps    (make-connect-deps conn)
              [id-a] (setup-concepts-for-connect! conn deps)
              result (reflect/connect! deps {:namespace "test"})]
          (is (pos? (:relationships-created result)))
          (let [rels (dh/get-relationships-for-memory conn id-a)]
            (is (pos? (count rels)))
            (let [rel (first rels)]
              (is (= :related-to (:relationship/type rel)))
              (is (string? (:relationship/description rel))))))))))

(deftest connect-skips-existing-relationships
  (th/with-datahike
    (fn [conn]
      (testing "connect! does not create duplicate relationships"
        (let [deps        (make-connect-deps conn)
              [id-a id-b] (setup-concepts-for-connect! conn deps)
              _           (dh/insert-relationship! conn {:source-id   id-a
                                                         :target-id   id-b
                                                         :type        :related-to
                                                         :confidence  0.9
                                                         :description "Existing"})
              result      (reflect/connect! deps {:namespace "test"})]
          (is (= 0 (:relationships-created result))))))))

(deftest connect-requires-at-least-two-concepts
  (th/with-datahike
    (fn [conn]
      (testing "connect! returns 0 with fewer than 2 concepts"
        (let [deps (make-connect-deps conn)
              _    (dh/insert-memory! conn {:memory/content   "Solo Concept"
                                            :memory/layer     :layer/concept
                                            :memory/namespace "test"})
              result (reflect/connect! deps {:namespace "test"})]
          (is (= 0 (:relationships-created result))))))))

;; ---------------------------------------------------------------------------
;; Phase 4: Curate tests
;; ---------------------------------------------------------------------------

(defn- mock-curate-provider
  "Mock that flags all pairs as contradictions."
  []
  (th/mock-chat-provider
   (fn [msgs _opts]
     (let [system-content (:content (first msgs))]
       (cond
         (= system-content (:reflect-curate th/mock-prompts))
         {:contradictions [{:pair-index  0
                            :explanation "These facts directly contradict each other"}]}

         (= system-content (:reflect-organize-domains th/mock-prompts))
         {:groups []}

         :else
         {:groups [{:concept-id      nil
                    :concept-name    "Test Concept"
                    :concept-content "A test concept"
                    :fact-indices    [0]}]})))))

(defn- make-curate-deps
  [conn]
  (let [prox-config {:dim 64 :capacity 1000}
        vector-idx  (prox/->ProximumVectorStore (prox/create-index! prox-config) prox-config)]
    {:db                 conn
     :vector-index       (atom vector-idx)
     :embedding-provider (th/mock-embedding-provider {:dim 64})
     :chat-provider      (mock-curate-provider)
     :prompts            th/mock-prompts}))

(defn- setup-concept-with-facts!
  "Create a concept with facts and embeddings. Returns {:concept-id :fact-ids}."
  [conn deps]
  (let [embed-provider (:embedding-provider deps)
        vector-index   (:vector-index deps)
        embed-fn       (fn [text]
                         (:embedding (llm-provider/embed embed-provider text)))
        concept-id (dh/insert-memory! conn {:memory/content   "Test Concept"
                                            :memory/layer     :layer/concept
                                            :memory/namespace "test"})
        f1-id      (dh/insert-memory! conn {:memory/content    "The sky is blue"
                                            :memory/layer      :layer/fact
                                            :memory/namespace  "test"
                                            :memory/parent-id  concept-id})
        f2-id      (dh/insert-memory! conn {:memory/content    "The sky is green"
                                            :memory/layer      :layer/fact
                                            :memory/namespace  "test"
                                            :memory/parent-id  concept-id})]
    (swap! vector-index protocols/upsert! (str f1-id) (embed-fn "The sky is blue"))
    (swap! vector-index protocols/upsert! (str f2-id) (embed-fn "The sky is green"))
    {:concept-id concept-id :fact-ids [f1-id f2-id]}))

(deftest curate-finds-contradictions
  (th/with-datahike
    (fn [conn]
      (testing "curate! flags contradicting facts"
        (let [deps   (make-curate-deps conn)
              kg     (setup-concept-with-facts! conn deps)
              result (reflect/curate! deps {:namespace "test"})]
          (is (pos? (:contradictions-found result)))
          (let [[f1-id f2-id] (:fact-ids kg)
                f1 (dh/get-memory conn f1-id)
                f2 (dh/get-memory conn f2-id)]
            (is (contains? (set (:memory/contradiction-ids f1)) f2-id))
            (is (contains? (set (:memory/contradiction-ids f2)) f1-id))))))))

(deftest curate-skips-already-flagged
  (th/with-datahike
    (fn [conn]
      (testing "curate! does not re-flag existing contradictions"
        (let [deps   (make-curate-deps conn)
              kg     (setup-concept-with-facts! conn deps)
              [f1-id f2-id] (:fact-ids kg)]
          ;; Manually set contradiction
          (dh/update-memory! conn f1-id {:memory/contradiction-ids f2-id})
          (dh/update-memory! conn f2-id {:memory/contradiction-ids f1-id})
          (let [result (reflect/curate! deps {:namespace "test"})]
            (is (= 0 (:contradictions-found result)))))))))

(deftest curate-returns-zero-without-facts
  (th/with-datahike
    (fn [conn]
      (testing "curate! returns 0 when no concepts have enough facts"
        (let [deps (make-curate-deps conn)
              _    (dh/insert-memory! conn {:memory/content   "Empty Concept"
                                            :memory/layer     :layer/concept
                                            :memory/namespace "test"})
              result (reflect/curate! deps {:namespace "test"})]
          (is (= 0 (:contradictions-found result))))))))

;; ---------------------------------------------------------------------------
;; Generative tests: dedupe-pairs
;; ---------------------------------------------------------------------------

(def gen-memory-pair
  "Generate a pair of [mem-a mem-b] with distinct UUIDs."
  (gen/let [id-a gen/uuid
            id-b gen/uuid]
    [{:memory/id id-a} {:memory/id id-b}]))

(defspec dedupe-pairs-no-duplicates 100
  (prop/for-all [pairs (gen/vector gen-memory-pair 0 30)]
                (let [result (reflect/dedupe-pairs pairs)
                      keys   (map (fn [[a b]]
                                    (vec (sort [(str (:memory/id a)) (str (:memory/id b))])))
                                  result)]
                  (= (count keys) (count (distinct keys))))))

(defspec dedupe-pairs-subset-of-input 100
  (prop/for-all [pairs (gen/vector gen-memory-pair 0 30)]
                (let [result (reflect/dedupe-pairs pairs)]
                  (every? (fn [pair] (some #(= pair %) pairs)) result))))

(defspec dedupe-pairs-idempotent 100
  (prop/for-all [pairs (gen/vector gen-memory-pair 0 30)]
                (let [once  (reflect/dedupe-pairs pairs)
                      twice (reflect/dedupe-pairs once)]
                  (= once twice))))

(defspec dedupe-pairs-order-independent 100
  (prop/for-all [pairs (gen/vector gen-memory-pair 0 20)]
                (let [with-swapped (into pairs (map (fn [[a b]] [b a]) pairs))
                      result       (reflect/dedupe-pairs with-swapped)]
      ;; After adding reversed pairs, dedup count should equal original dedup count
                  (<= (count result) (count pairs)))))
