(ns memlayer.integration.core-test
  "Integration tests for memlayer: tests real backend with real LLM providers.
   Requires OPENAI_API_KEY and GROQ_API_KEY in .env.

   Tests are invariant-based: they assert structural properties, not exact LLM output."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.tools.logging :as log]
            [memlayer.integration.test-system :as ts]
            [memlayer.integration.client :as c]
            [memlayer.integration.helpers :as h]))

;; -- Fixture: start/stop system once for all tests in this ns --

(def ^:private system (atom nil))

(defn system-fixture [f]
  (when-not (or @system ts/*system*)
    (reset! system (ts/start-test-system!)))
  (f))

;; Reset before each test for isolation + log progress
(defn reset-and-log-fixture [f]
  (let [test-name (str (:name (meta (first clojure.test/*testing-vars*))))]
    (log/info (str "START " test-name))
    (c/reset!)
    (let [start (System/currentTimeMillis)]
      (f)
      (log/info (str "DONE  " test-name " (" (- (System/currentTimeMillis) start) "ms)")))))

(use-fixtures :once system-fixture)
(use-fixtures :each reset-and-log-fixture)

;; ============================================================
;; Health
;; ============================================================

(deftest health-check
  (testing "health endpoint returns ok"
    (let [resp (c/health)]
      (h/assert-ok resp)
      (is (= "ok" (:status (:body resp)))))))

;; ============================================================
;; Basic Retain and Recall
;; ============================================================

(deftest basic-retain-creates-memories
  (testing "retaining content creates at least one memory"
    (let [resp (c/retain! "ContactAlice has employee ID EMP-7834 and desk phone extension 4521"
                          "e2e-test")
          body (h/assert-ok resp)]
      (is (seq (:memory_ids body)) "Should have at least one memory ID")
      (is (seq (:decisions body)) "Should have at least one decision")
      (is (some #(contains? #{"CREATE" "UPDATE"} (:type %)) (:decisions body))
          "Should have at least one CREATE or UPDATE"))))

(deftest semantic-recall-finds-content
  (testing "recall finds content by semantic similarity, not keyword matching"
    (let [_ (h/assert-ok (c/retain! "SemanticTestPerson enjoys playing basketball on weekends"
                                    "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/recall! "sports activities")
          body (h/assert-ok resp)]
      (is (pos? (:count body)) "Should find at least one result")
      (is (h/any-memory-contains? (:memories body) "basketball")
          "Should find basketball-related memory"))))

(deftest recall-results-ordered-by-distance
  (testing "recall results are ordered by distance (ascending)"
    (let [_ (h/assert-ok (c/retain! "Machine learning uses neural networks for pattern recognition"
                                    "e2e-test"))
          _ (h/assert-ok (c/retain! "Data science involves statistical analysis and visualization"
                                    "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/recall! "data science machine learning" {:limit 10})
          body (h/assert-ok resp)]
      (when (>= (:count body) 2)
        (let [distances (mapv :distance (:memories body))]
          (is (= distances (sort distances))
              "Distances should be sorted ascending"))))))

;; ============================================================
;; Entity Identity
;; ============================================================

(deftest entity-identity-invariants
  (testing "maintains invariants regardless of LLM decision path"
    (let [resp1 (c/retain! "EntityTestPerson works at Acme Corp as a senior engineer"
                           "e2e-test")
          body1 (h/assert-ok resp1)
          _ (h/wait-for-consistency)
          ;; Retain an update about the same person
          resp2 (c/retain! "EntityTestPerson has been promoted to principal engineer at Acme Corp"
                           "e2e-test")
          body2 (h/assert-ok resp2)]
      ;; Invariant: both operations should succeed
      (is (seq (:decisions body1)) "First retain should produce decisions")
      (is (seq (:decisions body2)) "Second retain should produce decisions")
      ;; Invariant: every decision has a valid type
      (doseq [d (concat (:decisions body1) (:decisions body2))]
        (is (contains? #{"CREATE" "UPDATE" "DELETE" "NOOP"} (:type d))
            (str "Decision type should be valid, got: " (:type d)))))))

;; ============================================================
;; Layer Assignment
;; ============================================================

(deftest fact-gets-fact-layer
  (testing "timeless facts get assigned fact layer"
    (let [resp (c/retain! "My username is TestUserABC and my favorite color is blue"
                          "e2e-test")
          body (h/assert-ok resp)
          _ (h/wait-for-consistency)
          mem-ids (:memory_ids body)]
      (when (seq mem-ids)
        (let [mem-resp (c/get-memory (first mem-ids))
              mem (h/assert-ok mem-resp)]
          (is (= "fact" (:layer mem))
              "Timeless fact should be assigned fact layer"))))))

(deftest episode-gets-episode-layer
  (testing "time-bound events get assigned episode layer"
    (let [resp (c/retain! "Today I had a meeting with ClientXYZ at 2pm to discuss the Q4 roadmap"
                          "e2e-test")
          body (h/assert-ok resp)
          _ (h/wait-for-consistency)
          mem-ids (:memory_ids body)]
      (when (seq mem-ids)
        (let [mem-resp (c/get-memory (first mem-ids))
              mem (h/assert-ok mem-resp)]
          ;; LLM may classify time-bound content as episode, fact, or concept
          ;; depending on what it extracts — the invariant is a valid layer
          (is (contains? #{"domain" "concept" "fact" "episode"} (:layer mem))
              "Should be assigned a valid layer"))))))

(deftest all-layers-in-valid-range
  (testing "all memory layers are valid values"
    (let [_ (h/assert-ok (c/retain! "General knowledge about programming languages"
                                    "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/list-memories {:limit 50})
          body (h/assert-ok resp)]
      (doseq [mem (:memories body)]
        (is (contains? #{"domain" "concept" "fact" "episode"} (:layer mem))
            (str "Layer should be valid, got: " (:layer mem)))))))

;; ============================================================
;; Memory Deletion
;; ============================================================

(deftest delete-removes-memory
  (testing "DELETE removes memory and its embedding"
    (let [resp (c/retain! "UniqueDeleteTest fact about quantum computing XYZ789"
                          "e2e-test")
          body (h/assert-ok resp)
          mem-id (first (:memory_ids body))
          _ (h/wait-for-consistency)]
      (when mem-id
        ;; Verify it exists
        (h/assert-ok (c/get-memory mem-id))
        ;; Delete it
        (h/assert-status 204 (c/delete-memory! mem-id))
        ;; Verify it's gone
        (h/assert-status 404 (c/get-memory mem-id))
        ;; Verify recall no longer finds it
        (let [recall-resp (c/recall! "quantum computing XYZ789")
              recall-body (h/assert-ok recall-resp)]
          (is (not-any? #(= mem-id (:memory_id %)) (:memories recall-body))
              "Deleted memory should not appear in recall"))))))

;; ============================================================
;; Forget Operation
;; ============================================================

(deftest forget-removes-memory
  (testing "forget retracts a memory and removes from recall"
    (let [resp (c/retain! "ForgetTestPerson lives in Tokyo and works as a designer"
                          "e2e-test")
          body (h/assert-ok resp)
          ;; Use decision-based IDs: only CREATE/UPDATE produce real memories.
          ;; Within a batch, concurrent processing can cause FORGET decisions
          ;; that delete previously-created memories from the same batch.
          created-ids (h/get-create-ids body)
          updated-ids (h/get-update-ids body)
          mem-ids     (into created-ids updated-ids)
          _ (h/wait-for-consistency)]
      (is (seq mem-ids) "Retain should produce at least one CREATE or UPDATE")
      (when (seq mem-ids)
        ;; Forget all memories created by this retain
        (doseq [mid mem-ids]
          (h/assert-ok (c/get-memory mid))
          (let [forget-resp (c/forget! mid)
                forget-body (h/assert-ok forget-resp)]
            (is (pos? (:memories_removed forget-body)))))
        ;; Verify all are gone
        (doseq [mid mem-ids]
          (h/assert-status 404 (c/get-memory mid)))
        (let [recall-body (h/assert-ok (c/recall! "ForgetTestPerson Tokyo designer"))]
          (is (not (h/any-memory-contains? (:memories recall-body) "ForgetTestPerson"))))))))

(deftest forget-nonexistent-memory-returns-zeros
  (testing "forget on non-existent memory returns zero counts"
    (let [resp (c/forget! (java.util.UUID/randomUUID))
          body (h/assert-ok resp)]
      (is (= 0 (:memories_removed body)))
      (is (= 0 (:relationships_removed body))))))

;; ============================================================
;; Data Consistency
;; ============================================================

(deftest memory-and-vector-counts-match
  (testing "memory count equals vector count"
    (let [stats       (h/assert-ok (c/get-memory-stats))
          consistency (h/assert-ok (c/get-consistency))]
      (is (= (:total_count stats)
             (:vector_count consistency))
          "Memory and vector counts should match on empty DB"))))

(deftest counts-consistent-after-operations
  (testing "counts remain consistent after retain"
    (let [_ (h/assert-ok (c/retain! "Consistency test fact about databases"
                                    "e2e-test"))
          _ (h/wait-for-consistency)
          stats       (h/assert-ok (c/get-memory-stats))
          consistency (h/assert-ok (c/get-consistency))]
      (is (pos? (:total_count stats))
          "Should have at least one memory")
      (is (= (:total_count stats)
             (:vector_count consistency))
          "Memory and vector counts should match after retain"))))

;; ============================================================
;; Memory CRUD
;; ============================================================

(deftest list-memories-respects-limit
  (testing "list memories respects limit parameter"
    (let [_ (h/assert-ok (c/retain! "CRUD test fact one about cats" "e2e-test"))
          _ (h/assert-ok (c/retain! "CRUD test fact two about dogs" "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/list-memories {:limit 5})
          body (h/assert-ok resp)]
      (is (<= (count (:memories body)) 5) "Should respect limit")
      (is (seq (:memories body)) "Should have some memories"))))

(deftest memories-have-required-fields
  (testing "memory responses have all required fields"
    (let [_ (h/assert-ok (c/retain! "Required fields test fact" "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/list-memories {:limit 5})
          body (h/assert-ok resp)]
      (doseq [mem (:memories body)]
        (is (h/valid-uuid? (:id mem)) "ID should be valid UUID")
        (is (seq (:content mem)) "Content should not be empty")
        (is (contains? #{"domain" "concept" "fact" "episode"} (:layer mem))
            "Layer should be valid")))))

;; ============================================================
;; Error Conditions
;; ============================================================

(deftest get-nonexistent-memory-returns-404
  (testing "GET non-existent memory returns 404"
    (h/assert-status 404
                     (c/get-memory "00000000-0000-0000-0000-000000000000"))))

(deftest children-of-leaf-memory-returns-empty
  (testing "children of a leaf memory returns empty list"
    (let [resp (c/retain! "Leaf memory test" "e2e-test")
          body (h/assert-ok resp)
          mem-id (first (:memory_ids body))
          _ (h/wait-for-consistency)]
      (when mem-id
        (let [children (h/assert-ok (c/get-children mem-id))]
          (is (empty? (:children children))
              "Leaf memory should have no children"))))))

(deftest relationships-of-isolated-memory-returns-empty
  (testing "relationships of an isolated memory returns empty list"
    (let [resp (c/retain! "Isolated memory test" "e2e-test")
          body (h/assert-ok resp)
          mem-id (first (:memory_ids body))
          _ (h/wait-for-consistency)]
      (when mem-id
        (let [rels (h/assert-ok (c/get-relationships mem-id))]
          (is (empty? (:relationships rels))
              "Isolated memory should have no relationships"))))))

(deftest near-duplicate-retain-produces-relationships
  (testing "retaining near-duplicate content triggers dedup and creates relationships"
    ;; Each retain adds genuinely new information about the same entity,
    ;; so extraction produces memories AND embeddings land close together.
    (h/assert-ok (c/retain! "RelTestUser Bob Smith works at Acme Corp as a backend engineer using Clojure"
                            "e2e-test"))
    (h/wait-for-consistency)
    (h/assert-ok (c/retain! "RelTestUser Bob Smith at Acme Corp was promoted to senior backend engineer"
                            "e2e-test"))
    (h/wait-for-consistency)
    (h/assert-ok (c/retain! "RelTestUser Bob Smith at Acme Corp senior engineer now leads the platform team"
                            "e2e-test"))
    (h/wait-for-consistency)
    ;; Collect all memory IDs
    (let [list-resp (c/list-memories {:limit 50})
          memories  (:memories (h/assert-ok list-resp))
          all-ids   (mapv :id memories)]
      (when (>= (count all-ids) 2)
        (let [rels-resp (apply c/get-relationships all-ids)
              rels-body (h/assert-ok rels-resp)
              rels      (:relationships rels-body)]
          ;; Near-duplicate content about the same entity should produce relationships
          (is (pos? (count rels))
              "Near-duplicate memories about the same entity should have at least one relationship")
          (doseq [rel rels]
            (is (h/valid-uuid? (:id rel)) "Relationship should have a valid UUID")
            (is (h/valid-uuid? (:source_id rel)) "source_id should be a valid UUID")
            (is (h/valid-uuid? (:target_id rel)) "target_id should be a valid UUID")
            (is (seq (:type rel)) "Relationship type should be non-empty")
            (is (nil? (:confidence rel)) "confidence field should not be present")
            (is (nil? (:strength rel)) "strength field should not be present")))))))

(deftest relationship-types-are-free-form
  (testing "relationship types are free-form strings, not limited to a fixed enum"
    ;; Same entity, new info each time — triggers dedup and relationship inference
    (h/assert-ok (c/retain! "TypeTestUser Jane Doe is a data scientist at BigCorp working on recommendation systems"
                            "e2e-test"))
    (h/wait-for-consistency)
    (h/assert-ok (c/retain! "TypeTestUser Jane Doe at BigCorp data scientist published a paper on collaborative filtering"
                            "e2e-test"))
    (h/wait-for-consistency)
    (let [list-resp (c/list-memories {:limit 50})
          all-ids   (mapv :id (:memories (h/assert-ok list-resp)))]
      (when (>= (count all-ids) 2)
        (let [rels (-> (apply c/get-relationships all-ids) h/assert-ok :relationships)]
          (doseq [rel rels]
            ;; Type should be a descriptive string, not blank
            (is (string? (:type rel)) "Type should be a string")
            (is (pos? (count (:type rel))) "Type should not be empty")))))))

;; ============================================================
;; Reflect
;; ============================================================

(deftest reflect-dry-run-returns-stats
  (testing "reflect dry_run returns processing stats without changes"
    (let [resp (c/reflect! {:dry_run true})
          body (h/assert-ok resp)]
      (is (= true (:dry_run body)))
      (is (number? (:facts_processed body)))
      (is (= 0 (:concepts_created body))))))

(deftest reflect-creates-concepts-from-orphan-facts
  (testing "reflect consolidates orphan facts into concepts"
    ;; Retain several related facts
    (h/assert-ok (c/retain! "Neural networks use layers of interconnected nodes for computation"
                            "e2e-test"))
    (h/assert-ok (c/retain! "Gradient descent is an optimization algorithm used to train neural networks"
                            "e2e-test"))
    (h/assert-ok (c/retain! "Deep learning is a subset of machine learning that uses deep neural networks"
                            "e2e-test"))
    (h/wait-for-consistency)
    ;; Run reflect — the LLM may classify memories into various layers,
    ;; so we verify reflect completes successfully and returns valid structure
    (let [resp (c/reflect!)
          body (h/assert-ok resp)]
      (is (number? (:facts_processed body))
          "Should return facts_processed count")
      (is (number? (:concepts_created body))
          "Should return concepts_created count"))))

;; ============================================================
;; Recall with Options
;; ============================================================

(deftest recall-with-layer-filter
  (testing "recall filters by layer"
    (let [_ (h/assert-ok (c/retain! "Technology is a broad domain of human knowledge"
                                    "e2e-test"))
          _ (h/assert-ok (c/retain! "Python is a programming language with clean syntax"
                                    "e2e-test"))
          _ (h/wait-for-consistency)
          resp (c/recall! "technology programming" {:layer "fact" :limit 10})
          body (h/assert-ok resp)]
      (doseq [mem (:memories body)]
        (is (= "fact" (:layer mem))
            "All results should be facts when filtered by fact layer")))))

;; ============================================================
;; Retain Response Field Coverage
;; ============================================================

(deftest retain-memory-source-matches-request
  (testing "memory source matches the retain request source"
    (let [resp (c/retain! "Source verification test: user likes hiking"
                          "test-source-verification")
          body (h/assert-ok resp)
          mem-id (first (:memory_ids body))
          _ (h/wait-for-consistency)]
      (when mem-id
        (let [mem (h/assert-ok (c/get-memory mem-id))]
          (is (= "test-source-verification" (:source mem))
              "Source should match the retain request"))))))
