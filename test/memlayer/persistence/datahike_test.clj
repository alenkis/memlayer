(ns memlayer.persistence.datahike-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.persistence.datahike :as dh]
            [memlayer.test-helpers :as th]))

(deftest insert-and-get-memory
  (th/with-datahike
    (fn [conn]
      (testing "insert a memory and retrieve it by id"
        (let [mem-id (dh/insert-memory! conn
                                        {:memory/content   "User prefers dark mode"
                                         :memory/layer     :layer/fact
                                         :memory/source    "conversation"
                                         :memory/namespace "default"})
              result (dh/get-memory conn mem-id)]
          (is (some? result))
          (is (= "User prefers dark mode" (:memory/content result)))
          (is (= :layer/fact (:memory/layer result))))))))

(deftest get-memories-by-namespace
  (th/with-datahike
    (fn [conn]
      (testing "query memories by namespace with limit"
        (dotimes [i 5]
          (dh/insert-memory! conn {:memory/content (str "memory " i)
                                   :memory/layer :layer/fact
                                   :memory/source "test"
                                   :memory/namespace "project-a"}))
        (let [results (dh/get-memories-by-namespace conn "project-a" :limit 3)]
          (is (= 3 (count results)))
          (is (every? #(= "project-a" (:memory/namespace %)) results)))))))

(deftest update-memory
  (th/with-datahike
    (fn [conn]
      (testing "update a memory's content in place"
        (let [mem-id (dh/insert-memory! conn {:memory/content "original"
                                              :memory/layer :layer/fact
                                              :memory/source "test"
                                              :memory/namespace "default"})
              _      (dh/update-memory! conn mem-id {:memory/content "updated"})
              result (dh/get-memory conn mem-id)]
          (is (= "updated" (:memory/content result))))))))

(deftest retract-memory
  (th/with-datahike
    (fn [conn]
      (testing "retracted memory is gone from current DB but in history"
        (let [mem-id (dh/insert-memory! conn {:memory/content "to be forgotten"
                                              :memory/layer :layer/fact
                                              :memory/source "test"
                                              :memory/namespace "default"})
              _      (dh/retract-memory! conn mem-id)]
          (is (nil? (dh/get-memory conn mem-id)))
          (is (seq (dh/get-memory-history conn mem-id))))))))

(deftest history-tracks-changes
  (th/with-datahike
    (fn [conn]
      (testing "d/history tracks attribute changes"
        (let [mem-id (dh/insert-memory! conn {:memory/content "v1"
                                              :memory/layer :layer/fact
                                              :memory/source "test"
                                              :memory/namespace "default"})
              _      (dh/update-memory! conn mem-id {:memory/content "v2"})
              hist   (dh/get-memory-history conn mem-id)
              content-history (filter #(= :memory/content (first %)) hist)]
          (is (>= (count content-history) 2)))))))

(deftest insert-relationship
  (th/with-datahike
    (fn [conn]
      (testing "insert a relationship between two memories"
        (let [src-id (dh/insert-memory! conn {:memory/content "Clojure"
                                              :memory/layer :layer/concept
                                              :memory/source "test"
                                              :memory/namespace "default"})
              tgt-id (dh/insert-memory! conn {:memory/content "Functional programming"
                                              :memory/layer :layer/domain
                                              :memory/source "test"
                                              :memory/namespace "default"})
              rel-id (th/insert-relationship! conn {:source-id src-id
                                                    :target-id tgt-id
                                                    :type      :related-to})]
          (is (uuid? rel-id))
          (let [rels (dh/get-relationships conn [src-id])]
            (is (= 1 (count rels)))
            (is (= :related-to (:relationship/type (first rels))))))))))

;; -- Count queries --

(deftest count-all-memories-unfiltered
  (th/with-datahike
    (fn [conn]
      (testing "counts all memories"
        (is (= 0 (dh/count-all-memories conn)))
        (dotimes [i 3]
          (dh/insert-memory! conn {:memory/content (str "m" i)
                                   :memory/layer :layer/fact
                                   :memory/source "test"
                                   :memory/namespace "default"}))
        (is (= 3 (dh/count-all-memories conn)))))))

(deftest count-all-memories-filtered
  (th/with-datahike
    (fn [conn]
      (testing "counts memories filtered by namespace and layer"
        (dh/insert-memory! conn {:memory/content "a"
                                 :memory/layer :layer/fact
                                 :memory/source "test"
                                 :memory/namespace "ns-a"})
        (dh/insert-memory! conn {:memory/content "b"
                                 :memory/layer :layer/concept
                                 :memory/source "test"
                                 :memory/namespace "ns-a"})
        (dh/insert-memory! conn {:memory/content "c"
                                 :memory/layer :layer/fact
                                 :memory/source "test"
                                 :memory/namespace "ns-b"})
        (is (= 3 (dh/count-all-memories conn)))
        (is (= 2 (dh/count-all-memories conn :namespace "ns-a")))
        (is (= 1 (dh/count-all-memories conn :namespace "ns-b")))
        (is (= 2 (dh/count-all-memories conn :layer :layer/fact)))
        (is (= 1 (dh/count-all-memories conn :namespace "ns-a" :layer :layer/fact)))
        (is (= 0 (dh/count-all-memories conn :namespace "ns-b" :layer :layer/concept)))))))

(deftest count-memories-by-namespace
  (th/with-datahike
    (fn [conn]
      (testing "counts memories per namespace without materializing"
        (dotimes [i 5]
          (dh/insert-memory! conn {:memory/content (str "m" i)
                                   :memory/layer :layer/fact
                                   :memory/source "test"
                                   :memory/namespace "counted-ns"}))
        (dh/insert-memory! conn {:memory/content "other"
                                 :memory/layer :layer/fact
                                 :memory/source "test"
                                 :memory/namespace "other-ns"})
        (is (= 5 (dh/count-memories-by-namespace conn "counted-ns")))
        (is (= 1 (dh/count-memories-by-namespace conn "other-ns")))
        (is (= 0 (dh/count-memories-by-namespace conn "nonexistent")))))))

;; -- Pagination --

(deftest get-all-memories-pagination
  (th/with-datahike
    (fn [conn]
      (testing "get-all-memories respects limit and offset"
        (dotimes [i 10]
          (dh/insert-memory! conn {:memory/content (str "m" i)
                                   :memory/layer :layer/fact
                                   :memory/source "test"
                                   :memory/namespace "default"}))
        (is (= 10 (count (dh/get-all-memories conn))))
        (is (= 3 (count (dh/get-all-memories conn :limit 3))))
        (is (= 5 (count (dh/get-all-memories conn :limit 5 :offset 5))))
        (is (= 2 (count (dh/get-all-memories conn :limit 5 :offset 8))))))))

(deftest get-children-pagination
  (th/with-datahike
    (fn [conn]
      (testing "get-children respects limit and offset"
        (let [parent-id (dh/insert-memory! conn {:memory/content "parent"
                                                 :memory/layer :layer/domain
                                                 :memory/source "test"
                                                 :memory/namespace "default"})]
          (dotimes [i 5]
            (dh/insert-memory! conn {:memory/content (str "child " i)
                                     :memory/layer :layer/fact
                                     :memory/source "test"
                                     :memory/namespace "default"
                                     :memory/parent [:memory/id parent-id]}))
          (is (= 5 (count (dh/get-children conn parent-id))))
          (is (= 2 (count (dh/get-children conn parent-id :limit 2))))
          (is (= 3 (count (dh/get-children conn parent-id :offset 2))))
          (is (= 5 (dh/count-children conn parent-id))))))))

(deftest get-relationships-for-memory-ids
  (th/with-datahike
    (fn [conn]
      (testing "get-relationships returns relationships for given memory IDs"
        (let [src-id (dh/insert-memory! conn {:memory/content "source"
                                              :memory/layer :layer/concept
                                              :memory/source "test"
                                              :memory/namespace "default"})]
          (dotimes [i 5]
            (let [tgt-id (dh/insert-memory! conn {:memory/content (str "target " i)
                                                  :memory/layer :layer/fact
                                                  :memory/source "test"
                                                  :memory/namespace "default"})]
              (th/insert-relationship! conn {:source-id src-id
                                             :target-id tgt-id
                                             :type :related-to})))
          (is (= 5 (count (dh/get-relationships conn [src-id]))))
          (is (nil? (dh/get-relationships conn []))))))))

(deftest get-all-memory-ids
  (th/with-datahike
    (fn [conn]
      (testing "returns all memory UUIDs without full entity pull"
        (let [ids (mapv (fn [i]
                          (dh/insert-memory! conn {:memory/content (str "m" i)
                                                   :memory/layer :layer/fact
                                                   :memory/source "test"
                                                   :memory/namespace "default"}))
                        (range 3))
              result (set (dh/get-all-memory-ids conn))]
          (is (= 3 (count result)))
          (is (every? result ids)))))))

;; -- Relationship tests --

(deftest insert-relationship-direct
  (th/with-datahike
    (fn [conn]
      (testing "dh/insert-relationship! creates a relationship and returns UUID"
        (let [src-id (dh/insert-memory! conn {:memory/content "A"
                                              :memory/layer :layer/fact
                                              :memory/source "test"
                                              :memory/namespace "default"})
              tgt-id (dh/insert-memory! conn {:memory/content "B"
                                              :memory/layer :layer/fact
                                              :memory/source "test"
                                              :memory/namespace "default"})
              rel-id (dh/insert-relationship! conn {:source-id src-id
                                                    :target-id tgt-id
                                                    :type :elaborates})]
          (is (uuid? rel-id))
          (let [rels (dh/get-relationships conn [src-id])]
            (is (= 1 (count rels)))
            (is (= src-id (get-in (first rels) [:relationship/source :memory/id])))
            (is (= tgt-id (get-in (first rels) [:relationship/target :memory/id])))
            (is (= :elaborates (:relationship/type (first rels))))))))))

(deftest get-relationships-multiple-ids
  (th/with-datahike
    (fn [conn]
      (testing "get-relationships returns relationships for multiple memory IDs"
        (let [m1 (dh/insert-memory! conn {:memory/content "M1"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              m2 (dh/insert-memory! conn {:memory/content "M2"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              m3 (dh/insert-memory! conn {:memory/content "M3"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              _r1  (dh/insert-relationship! conn {:source-id m1 :target-id m2 :type :related-to})
              _r2  (dh/insert-relationship! conn {:source-id m2 :target-id m3 :type :elaborates})
              ;; Query with [m1 m3] should find both relationships (m1 is source of r1, m3 is target of r2)
              rels (dh/get-relationships conn [m1 m3])]
          (is (= 2 (count rels)))
          (is (= #{:related-to :elaborates}
                 (set (map :relationship/type rels)))))))))

(deftest get-relationships-dedup
  (th/with-datahike
    (fn [conn]
      (testing "get-relationships deduplicates when a relationship matches both source and target queries"
        (let [m1   (dh/insert-memory! conn {:memory/content "M1"
                                            :memory/layer :layer/fact
                                            :memory/source "test"
                                            :memory/namespace "default"})
              m2   (dh/insert-memory! conn {:memory/content "M2"
                                            :memory/layer :layer/fact
                                            :memory/source "test"
                                            :memory/namespace "default"})
              _r   (dh/insert-relationship! conn {:source-id m1 :target-id m2 :type :supports})
              ;; Both m1 and m2 are in the query — the single relationship should appear once
              rels (dh/get-relationships conn [m1 m2])]
          (is (= 1 (count rels)))
          (is (= :supports (:relationship/type (first rels)))))))))

(deftest get-relationships-returns-nil-for-empty
  (th/with-datahike
    (fn [conn]
      (testing "get-relationships returns nil for empty input"
        (is (nil? (dh/get-relationships conn [])))
        (is (nil? (dh/get-relationships conn nil)))))))

(deftest get-relationships-no-matches
  (th/with-datahike
    (fn [conn]
      (testing "get-relationships returns empty for IDs with no relationships"
        (let [m1 (dh/insert-memory! conn {:memory/content "M1"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})]
          (is (empty? (dh/get-relationships conn [m1]))))))))

(deftest get-distinct-relationship-types-test
  (th/with-datahike
    (fn [conn]
      (testing "returns all unique relationship types"
        (let [m1 (dh/insert-memory! conn {:memory/content "M1"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              m2 (dh/insert-memory! conn {:memory/content "M2"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              m3 (dh/insert-memory! conn {:memory/content "M3"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})]
          ;; No relationships yet
          (is (empty? (dh/get-distinct-relationship-types conn)))
          ;; Add some with overlapping types
          (dh/insert-relationship! conn {:source-id m1 :target-id m2 :type :elaborates})
          (dh/insert-relationship! conn {:source-id m2 :target-id m3 :type :supports})
          (dh/insert-relationship! conn {:source-id m1 :target-id m3 :type :elaborates})
          (let [types (set (dh/get-distinct-relationship-types conn))]
            (is (= #{:elaborates :supports} types))))))))

;; -- Batch query tests --

(deftest get-memories-batch-test
  (th/with-datahike
    (fn [conn]
      (testing "fetches multiple memories by ID in a single query"
        (let [ids (mapv (fn [i]
                          (dh/insert-memory! conn {:memory/content (str "batch-" i)
                                                   :memory/layer :layer/fact
                                                   :memory/source "test"
                                                   :memory/namespace "default"}))
                        (range 3))
              result (dh/get-memories-batch conn ids)]
          (is (= 3 (count result)))
          (is (= (set ids) (set (map :memory/id result))))
          (is (every? :memory/content result))))

      (testing "returns minimal pull pattern (no full entity)"
        (let [id (dh/insert-memory! conn {:memory/content "minimal"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              [mem] (dh/get-memories-batch conn [id])]
          (is (= id (:memory/id mem)))
          (is (= "minimal" (:memory/content mem)))
          (is (= :layer/fact (:memory/layer mem)))))

      (testing "returns nil for empty input"
        (is (nil? (dh/get-memories-batch conn [])))
        (is (nil? (dh/get-memories-batch conn nil))))

      (testing "deduplicates input IDs"
        (let [id (dh/insert-memory! conn {:memory/content "dup"
                                          :memory/layer :layer/fact
                                          :memory/source "test"
                                          :memory/namespace "default"})
              result (dh/get-memories-batch conn [id id id])]
          (is (= 1 (count result))))))))

(deftest get-summaries-for-batch-test
  (th/with-datahike
    (fn [conn]
      (testing "fetches summaries for multiple parent IDs in one query"
        (let [p1 (dh/insert-memory! conn {:memory/content "concept-1"
                                          :memory/layer :layer/concept
                                          :memory/namespace "default"})
              p2 (dh/insert-memory! conn {:memory/content "concept-2"
                                          :memory/layer :layer/concept
                                          :memory/namespace "default"})
              s1 (dh/insert-memory! conn {:memory/content "summary of concept-1"
                                          :memory/layer :layer/summary
                                          :memory/parent [:memory/id p1]
                                          :memory/namespace "default"})
              s2 (dh/insert-memory! conn {:memory/content "summary of concept-2"
                                          :memory/layer :layer/summary
                                          :memory/parent [:memory/id p2]
                                          :memory/namespace "default"})
              _  (dh/insert-memory! conn {:memory/content "fact under concept-1"
                                          :memory/layer :layer/fact
                                          :memory/parent [:memory/id p1]
                                          :memory/namespace "default"})
              result (dh/get-summaries-for-batch conn [p1 p2])]
          (is (= 2 (count result)))
          (is (= #{s1 s2} (set (map :memory/id result))))
          (is (every? #(= :layer/summary (:memory/layer %)) result))))

      (testing "returns nil for empty input"
        (is (nil? (dh/get-summaries-for-batch conn [])))
        (is (nil? (dh/get-summaries-for-batch conn nil)))))))

(deftest get-children-of-parents-batch-test
  (th/with-datahike
    (fn [conn]
      (testing "fetches children for multiple parents in one query"
        (let [p1 (dh/insert-memory! conn {:memory/content "parent-1"
                                          :memory/layer :layer/concept
                                          :memory/namespace "default"})
              p2 (dh/insert-memory! conn {:memory/content "parent-2"
                                          :memory/layer :layer/concept
                                          :memory/namespace "default"})
              c1 (dh/insert-memory! conn {:memory/content "child-of-1a"
                                          :memory/layer :layer/fact
                                          :memory/parent [:memory/id p1]
                                          :memory/namespace "default"})
              c2 (dh/insert-memory! conn {:memory/content "child-of-1b"
                                          :memory/layer :layer/fact
                                          :memory/parent [:memory/id p1]
                                          :memory/namespace "default"})
              c3 (dh/insert-memory! conn {:memory/content "child-of-2"
                                          :memory/layer :layer/fact
                                          :memory/parent [:memory/id p2]
                                          :memory/namespace "default"})
              result (dh/get-children-of-parents-batch conn [p1 p2])]
          (is (= 3 (count result)))
          (is (= #{c1 c2 c3} (set (map :memory/id result))))))

      (testing "returns nil for empty input"
        (is (nil? (dh/get-children-of-parents-batch conn [])))
        (is (nil? (dh/get-children-of-parents-batch conn nil)))))))

(deftest get-memories-since-test
  (th/with-datahike
    (fn [conn]
      (let [_      (Thread/sleep 50)
            _      (dh/insert-memory! conn {:memory/content    "old memory"
                                            :memory/layer      :layer/fact
                                            :memory/source     "test"
                                            :memory/namespace  "default"})
            _      (Thread/sleep 50)
            mid    (java.util.Date.)
            _      (Thread/sleep 50)
            id2    (dh/insert-memory! conn {:memory/content    "new memory"
                                            :memory/layer      :layer/fact
                                            :memory/source     "test"
                                            :memory/namespace  "default"})]
        (testing "returns memories created after timestamp"
          (let [result (dh/get-memories-since conn mid)]
            (is (= 1 (count result)))
            (is (= id2 (:memory/id (first result))))))
        (testing "returns all memories when since is epoch"
          (let [result (dh/get-memories-since conn (java.util.Date. 0))]
            (is (= 2 (count result)))))
        (testing "namespace filtering"
          (dh/insert-memory! conn {:memory/content    "ns memory"
                                   :memory/layer      :layer/fact
                                   :memory/source     "test"
                                   :memory/namespace  "foo"})
          (let [result (dh/get-memories-since conn (java.util.Date. 0) :namespace "default")]
            (is (= 2 (count result)))))))))

