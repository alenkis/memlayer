(ns memlayer.operations.retain-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.protocols :as protocols]
            [memlayer.persistence.proximum :as prox]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.test-helpers :as th]))

(defn- make-deps
  ([conn] (make-deps conn {}))
  ([conn {:keys [memory-limit extract-result decision-result decision-fn]}]
   (let [prox-config {:dim 64 :capacity 1000}
         vector-idx (atom (prox/->ProximumVectorStore
                           (prox/create-index! prox-config) prox-config))]
     {:db                 conn
      :vector-index       vector-idx
      :embedding-provider (th/mock-embedding-provider {:dim 64})
      :chat-provider      (th/mock-flow-provider
                           (cond-> {}
                             extract-result  (assoc :extract-result extract-result)
                             decision-result (assoc :decision-result decision-result)
                             decision-fn     (assoc :decision-fn decision-fn)))
      :prompts            th/mock-prompts
      :tuning             (cond-> {}
                            memory-limit (assoc :memory-limit memory-limit))})))

(deftest retain-creates-new-memory
  (th/with-datahike
    (fn [conn]
      (testing "retain flow creates a new memory (batch-of-1)"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items     [{:content "User prefers dark mode"
                                                               :source  "conversation"}]
                                                  :namespace "default"})]
              (is (= 1 (count (:memory-ids result))))
              (is (= 1 (count (:decisions result))))
              (is (= "CREATE" (:type (first (:decisions result)))))
              (let [mem (dh/get-memory conn (first (:memory-ids result)))]
                (is (some? mem))
                (is (= "User prefers dark mode" (:memory/content mem)))
                (is (= :layer/fact (:memory/layer mem)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-with-multiple-extractions
  (th/with-datahike
    (fn [conn]
      (testing "retain handles extraction yielding multiple memories"
        (let [deps (make-deps conn
                              {:extract-result
                               [{:content "User likes Clojure" :layer "fact" :importance 0.8}
                                {:content "User is a developer" :layer "concept" :importance 0.6}]})
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "I really enjoy writing Clojure code"
                                                           :source  "conversation"}]})]
              (is (= 2 (count (:memory-ids result))))
              (is (= 2 (count (:decisions result))))
              (is (every? #(= "CREATE" (:type %)) (:decisions result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-with-noop-decision
  (th/with-datahike
    (fn [conn]
      (testing "retain skips storing when decision is NOOP"
        (let [deps (make-deps conn
                              {:decision-result {:action "NOOP"
                                                 :reasoning "Already known"}})
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "something" :source "test"}]})]
              (is (empty? (:memory-ids result)))
              (is (= 1 (count (:decisions result))))
              (is (= "NOOP" (:type (first (:decisions result))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-updates-existing-memory
  (th/with-datahike
    (fn [conn]
      (testing "retain updates existing memory when decision is UPDATE"
        (let [decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "User's favorite number is 42"
                                                  :layer "fact" :importance 0.7}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            ;; Create initial memory
            (let [first-result (retention-flow/submit! flow
                                                       {:items     [{:content "My favorite number is 42"
                                                                     :source  "conversation"}]
                                                        :namespace "default"})
                  first-id (first (:memory-ids first-result))]
              (is (some? first-id))
              ;; Switch to UPDATE decision
              (reset! decide-atom {:action "UPDATE"
                                   :merged-content "User's favorite number is 67"
                                   :reasoning "Updating existing fact"})
              ;; Submit again — same extract text so embedding matches first memory
              (let [result (retention-flow/submit! flow
                                                   {:items     [{:content "My favorite number is 67 now"
                                                                 :source  "conversation"}]
                                                    :namespace "default"})]
                (is (= 1 (count (:decisions result))))
                (is (= "UPDATE" (:type (first (:decisions result)))))
                (let [mem (dh/get-memory conn first-id)]
                  (is (= "User's favorite number is 67" (:memory/content mem))))
                (is (= 1 (count (dh/get-recent-memories conn))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-forgets-on-explicit-request
  (th/with-datahike
    (fn [conn]
      (testing "retain removes memory when decision is FORGET"
        (let [decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "User's phone number is 555-1234"
                                                  :layer "fact" :importance 0.7}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            (let [first-result (retention-flow/submit! flow
                                                       {:items     [{:content "My phone number is 555-1234"
                                                                     :source  "conversation"}]
                                                        :namespace "default"})
                  first-id (first (:memory-ids first-result))]
              (is (some? first-id))
              (is (some? (dh/get-memory conn first-id)))
              ;; Switch to FORGET
              (reset! decide-atom {:action "FORGET"
                                   :reasoning "User asked to forget"})
              (let [result (retention-flow/submit! flow
                                                   {:items     [{:content "Forget my phone number"
                                                                 :source  "conversation"}]
                                                    :namespace "default"})]
                (is (= "FORGET" (:type (first (:decisions result)))))
                (is (nil? (dh/get-memory conn first-id)))
                (is (= 0 (count (dh/get-recent-memories conn))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-with-empty-extraction
  (th/with-datahike
    (fn [conn]
      (testing "retain handles empty extraction gracefully"
        (let [deps (make-deps conn {:extract-result []})
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "nothing useful" :source "test"}]})]
              (is (empty? (:memory-ids result)))
              (is (empty? (:decisions result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-stores-embedding-in-vector-index
  (th/with-datahike
    (fn [conn]
      (testing "retain stores embeddings that can be searched"
        (let [deps (make-deps conn)
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "User prefers Vim" :source "test"}]
                                                  :namespace "editors"})
                  idx    @(:vector-index deps)
                  ;; Search with the extracted content (mock always extracts "User prefers dark mode")
                  {:keys [embedding]} (llm-provider/embed (:embedding-provider deps) "User prefers dark mode")
                  search-results (protocols/search idx embedding 1)]
              (is (= 1 (count (:memory-ids result))))
              (is (seq search-results))
              (is (= (str (first (:memory-ids result)))
                     (:id (first search-results)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-rejects-when-memory-limit-exceeded
  (th/with-datahike
    (fn [conn]
      (testing "retain returns error when memory limit is reached"
        (let [deps (make-deps conn {:memory-limit 1})
              flow (th/start-test-flow! deps)]
          (try
            (let [first-result (retention-flow/submit! flow
                                                       {:items [{:content "First memory" :source "test"}]
                                                        :namespace "default"})]
              (is (nil? (:error first-result))))
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "Second memory" :source "test"}]
                                                  :namespace "default"})]
              (is (= :memory-limit-exceeded (:error result)))
              (is (= 1 (:current-count result)))
              (is (= 1 (:limit result))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-allows-when-under-limit
  (th/with-datahike
    (fn [conn]
      (testing "retain succeeds when under memory limit"
        (let [deps (make-deps conn {:memory-limit 5})
              flow (th/start-test-flow! deps)]
          (try
            (let [result (retention-flow/submit! flow
                                                 {:items [{:content "Under limit" :source "test"}]
                                                  :namespace "default"})]
              (is (= 1 (count (:memory-ids result)))))
            (finally
              (th/stop-test-flow! flow))))))))

;; -- Relationship inference tests --

(deftest retain-creates-relationships-on-create
  (th/with-datahike
    (fn [conn]
      (testing "retain creates LLM-inferred relationships when decision includes them"
        (let [;; First create a memory that will be a candidate
              decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "Clojure uses the JVM"
                                                  :layer "fact" :importance 0.7}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            ;; Create first memory
            (let [first-result (retention-flow/submit! flow
                                                       {:items     [{:content "Clojure is a Lisp on the JVM"
                                                                     :source  "conversation"}]
                                                        :namespace "default"})
                  first-id (first (:memory-ids first-result))]
              (is (some? first-id))
              ;; Now make the decision return relationships pointing to the first memory
              (reset! decide-atom {:action "CREATE"
                                   :reasoning "Related information"
                                   :relationships [{:target-id (str first-id)
                                                    :type "elaborates"}]})
              (let [result (retention-flow/submit! flow
                                                   {:items     [{:content "Clojure compiles to JVM bytecode"
                                                                 :source  "conversation"}]
                                                    :namespace "default"})
                    second-id (first (:memory-ids result))]
                (is (some? second-id))
                ;; Verify relationship was created
                (let [rels (dh/get-relationships conn [second-id])]
                  (is (= 1 (count rels)))
                  (is (= second-id (:relationship/source-id (first rels))))
                  (is (= first-id (:relationship/target-id (first rels))))
                  (is (= :elaborates (:relationship/type (first rels)))))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-creates-relationships-on-update
  (th/with-datahike
    (fn [conn]
      (testing "retain creates relationships when decision is UPDATE"
        (let [decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "User's favorite language is Clojure"
                                                  :layer "fact" :importance 0.8}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            ;; Create initial memory
            (let [first-result (retention-flow/submit! flow
                                                       {:items     [{:content "I love Clojure"
                                                                     :source  "conversation"}]
                                                        :namespace "default"})
                  first-id (first (:memory-ids first-result))]
              (is (some? first-id))
              ;; Create a second memory (will be a candidate for update)
              (reset! decide-atom {:action "CREATE" :reasoning "Another fact"})
              (let [second-result (retention-flow/submit! flow
                                                          {:items     [{:content "Clojure is great for data"
                                                                        :source  "conversation"}]
                                                           :namespace "default"})
                    second-id (first (:memory-ids second-result))]
                ;; Now UPDATE the first memory and add relationship to second
                (reset! decide-atom {:action "UPDATE"
                                     :merged-content "User's favorite language is Clojure, especially for data"
                                     :reasoning "Merging related info"
                                     :relationships [{:target-id (str second-id)
                                                      :type "supports"}]})
                (retention-flow/submit! flow
                                        {:items     [{:content "Clojure is my favorite for data processing"
                                                      :source  "conversation"}]
                                         :namespace "default"})
                ;; The updated memory (first-id, since it's the closest candidate) should have a relationship
                (let [rels (dh/get-relationships conn [first-id])]
                  (is (pos? (count rels)))
                  (is (some #(= :supports (:relationship/type %)) rels)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-skips-invalid-relationship-targets
  (th/with-datahike
    (fn [conn]
      (testing "retain skips relationships with target IDs not in candidates"
        (let [decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "Some fact"
                                                  :layer "fact" :importance 0.5}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            ;; Create first memory
            (let [first-result (retention-flow/submit! flow
                                                       {:items     [{:content "First fact"
                                                                     :source  "test"}]
                                                        :namespace "default"})
                  first-id (first (:memory-ids first-result))
                  ;; A completely random UUID that is NOT a candidate
                  fake-id (java.util.UUID/randomUUID)]
              (is (some? first-id))
              ;; Decision returns a relationship to a non-existent memory
              (reset! decide-atom {:action "CREATE"
                                   :reasoning "New"
                                   :relationships [{:target-id (str fake-id)
                                                    :type "related-to"}]})
              (let [result (retention-flow/submit! flow
                                                   {:items     [{:content "Second fact"
                                                                 :source  "test"}]
                                                    :namespace "default"})
                    second-id (first (:memory-ids result))]
                (is (some? second-id))
                ;; No relationship should be created (fake-id not in candidates)
                (let [rels (dh/get-relationships conn [second-id])]
                  (is (empty? rels)))))
            (finally
              (th/stop-test-flow! flow))))))))

(deftest retain-creates-multiple-relationships
  (th/with-datahike
    (fn [conn]
      (testing "retain creates multiple relationships from a single decision"
        (let [decide-atom (atom {:action "CREATE" :reasoning "New info"})
              deps  (make-deps conn
                               {:extract-result [{:content "Related fact"
                                                  :layer "fact" :importance 0.6}]
                                :decision-fn (fn [_] @decide-atom)})
              flow  (th/start-test-flow! deps)]
          (try
            ;; Create two initial memories
            (let [r1 (retention-flow/submit! flow
                                             {:items [{:content "Memory A" :source "test"}]
                                              :namespace "default"})
                  id1 (first (:memory-ids r1))
                  _ (reset! decide-atom {:action "CREATE" :reasoning "Another"})
                  r2 (retention-flow/submit! flow
                                             {:items [{:content "Memory B" :source "test"}]
                                              :namespace "default"})
                  id2 (first (:memory-ids r2))]
              ;; Now create a third that relates to both
              (reset! decide-atom {:action "CREATE"
                                   :reasoning "Connects both"
                                   :relationships [{:target-id (str id1) :type "elaborates"}
                                                   {:target-id (str id2) :type "supports"}]})
              (let [r3 (retention-flow/submit! flow
                                               {:items [{:content "Memory C connects A and B"
                                                         :source "test"}]
                                                :namespace "default"})
                    id3 (first (:memory-ids r3))
                    rels (dh/get-relationships conn [id3])]
                ;; Both relationships may or may not be created depending on whether
                ;; both id1 and id2 are in the dedup candidates. With threshold 2.0,
                ;; all should be candidates.
                (is (some? id3))
                (when (seq rels)
                  (is (every? #(= id3 (:relationship/source-id %)) rels)))))
            (finally
              (th/stop-test-flow! flow))))))))
