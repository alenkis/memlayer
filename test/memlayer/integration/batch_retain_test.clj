(ns memlayer.integration.batch-retain-test
  "Integration tests for POST /api/v1/retain/batch endpoint.
   Requires OPENAI_API_KEY and GROQ_API_KEY in .env.

   Tests are invariant-based: they assert structural properties, not exact LLM output."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [memlayer.integration.test-system :as ts]
            [memlayer.integration.client :as c]
            [memlayer.integration.helpers :as h]))

;; -- Fixture: start/stop system once for all tests in this ns --
;; Reuses the system from test-system if already started (e.g. by core-test).

(def ^:private system (atom nil))

(defn system-fixture [f]
  (when-not (or @system ts/*system*)
    (reset! system (ts/start-test-system!)))
  (f))

(use-fixtures :once system-fixture)

;; Reset before each test for isolation
(defn reset-fixture [f]
  (c/reset!)
  (f))

(use-fixtures :each reset-fixture)

;; ============================================================
;; Basic Batch Retain
;; ============================================================

(deftest batch-retain-creates-memories
  (testing "batch-retain with multiple items returns 201 and creates memories"
    (let [resp (c/batch-retain! "test"
                                [{:content "User likes Clojure" :source "test"}
                                 {:content "User prefers dark mode" :source "test"}
                                 {:content "User works at Acme Corp" :source "test"}])
          body (h/assert-status 201 resp)]
      (is (seq (:memory_ids body)) "Should have at least one memory ID")
      (doseq [mid (:memory_ids body)]
        (is (h/valid-uuid? mid) (str "Memory ID should be valid UUID, got: " mid)))
      (is (some? (:decisions body)) "Should have decisions")
      (is (seq (:decisions body)) "Decisions should be non-empty")
      (is (some? (:usage body)) "Should have usage"))))

(deftest batch-retain-decisions-have-valid-types
  (testing "every decision in batch-retain response has a valid type"
    (let [resp (c/batch-retain! "test"
                                [{:content "BatchDecTest likes hiking in the mountains" :source "test"}
                                 {:content "BatchDecTest studies machine learning" :source "test"}])
          body (h/assert-status 201 resp)]
      (doseq [d (:decisions body)]
        (is (contains? #{"CREATE" "UPDATE" "DELETE" "NOOP"} (:type d))
            (str "Decision type should be valid, got: " (:type d)))))))

;; ============================================================
;; Recall After Batch Retain
;; ============================================================

(deftest batch-retained-memories-are-recallable
  (testing "memories created via batch-retain can be found by recall"
    (let [_ (h/assert-status 201
                             (c/batch-retain! "test"
                                              [{:content "BatchRecallUser enjoys playing basketball on weekends" :source "test"}
                                               {:content "BatchRecallUser's favorite programming language is Haskell" :source "test"}]))
          _ (h/wait-for-consistency)
          resp (c/recall! "basketball sports activities")
          body (h/assert-ok resp)]
      (is (pos? (:count body)) "Should find at least one result")
      (is (h/any-memory-contains? (:memories body) "basketball")
          "Should find basketball-related memory"))))

;; ============================================================
;; Empty Items
;; ============================================================

(deftest batch-retain-empty-items-succeeds
  (testing "batch-retain with empty items returns 201 with empty results"
    (let [resp (c/batch-retain! "test" [])
          body (h/assert-status 201 resp)]
      (is (empty? (:memory_ids body)) "Should have no memory IDs for empty input")
      (is (empty? (:decisions body)) "Should have no decisions for empty input"))))

;; ============================================================
;; Large Batch
;; ============================================================

(deftest batch-retain-large-batch-works
  (testing "batch-retain with 10+ items creates memories and they are recallable"
    (let [items (mapv (fn [i]
                        {:content (str "LargeBatch fact #" i ": "
                                       (nth ["User enjoys hiking in the Rocky Mountains"
                                             "User is fluent in Japanese and French"
                                             "User works as a backend engineer at a fintech company"
                                             "User has a golden retriever named Max"
                                             "User prefers Emacs with evil-mode for editing"
                                             "User plays piano and guitar"
                                             "User studied mathematics at MIT"
                                             "User is allergic to shellfish"
                                             "User runs marathons every spring"
                                             "User collects vintage mechanical keyboards"
                                             "User volunteers at a local animal shelter"
                                             "User is learning Rust for systems programming"]
                                            i))
                         :source "e2e-large-batch"})
                      (range 12))
          resp (c/batch-retain! "test" items)
          body (h/assert-status 201 resp)]
      (is (seq (:memory_ids body)) "Should create at least one memory")
      (is (seq (:decisions body)) "Should have decisions")
      (doseq [d (:decisions body)]
        (is (contains? #{"CREATE" "UPDATE" "DELETE" "NOOP"} (:type d))
            (str "Invalid decision type: " (:type d))))
      (is (some? (:usage body)) "Should track usage")
      ;; Verify memories are recallable
      (h/wait-for-consistency)
      (let [resp   (c/recall! "hiking mountains outdoor")
            recall (h/assert-ok resp)]
        (is (pos? (:count recall)) "Should find at least one memory via recall")))))
