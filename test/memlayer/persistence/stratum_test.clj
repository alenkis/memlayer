(ns memlayer.persistence.stratum-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.persistence.stratum :as strat]
            [memlayer.persistence.datahike :as dh]
            [memlayer.test-helpers :as th]))

(defn- insert-test-memories! [conn]
  (dh/insert-memory! conn {:memory/content   "User prefers dark mode"
                           :memory/layer     :layer/fact
                           :memory/importance (float 0.7)
                           :memory/source    "conversation"
                           :memory/namespace "default"})
  (dh/insert-memory! conn {:memory/content   "Clojure is a Lisp"
                           :memory/layer     :layer/concept
                           :memory/importance (float 0.8)
                           :memory/source    "document"
                           :memory/namespace "default"})
  (dh/insert-memory! conn {:memory/content   "Meeting about API design"
                           :memory/layer     :layer/episode
                           :memory/importance (float 0.5)
                           :memory/source    "conversation"
                           :memory/namespace "work"})
  (dh/insert-memory! conn {:memory/content   "Functional programming"
                           :memory/layer     :layer/domain
                           :memory/importance (float 0.9)
                           :memory/source    "document"
                           :memory/namespace "default"}))

(deftest materialize-test
  (th/with-datahike
    (fn [conn]
      (testing "materialize returns nil for empty db"
        (is (nil? (strat/materialize conn))))

      (testing "materialize returns dataset with memories"
        (insert-test-memories! conn)
        (let [ds (strat/materialize conn)]
          (is (some? ds)))))))

(deftest count-by-layer-test
  (th/with-datahike
    (fn [conn]
      (insert-test-memories! conn)
      (let [ds     (strat/materialize conn)
            result (strat/count-by-layer ds)]
        (testing "returns counts grouped by layer"
          (is (= 4 (count result)))
          (let [by-layer (into {} (map (fn [r] [(:layer r) (:_count r)]) result))]
            (is (= 1 (get by-layer "fact")))
            (is (= 1 (get by-layer "concept")))
            (is (= 1 (get by-layer "episode")))
            (is (= 1 (get by-layer "domain")))))))))

(deftest count-by-namespace-test
  (th/with-datahike
    (fn [conn]
      (insert-test-memories! conn)
      (let [ds     (strat/materialize conn)
            result (strat/count-by-namespace ds)]
        (testing "returns counts grouped by namespace"
          (let [by-ns (into {} (map (fn [r] [(:namespace r) (:_count r)]) result))]
            (is (= 3 (get by-ns "default")))
            (is (= 1 (get by-ns "work")))))))))

(deftest avg-importance-by-layer-test
  (th/with-datahike
    (fn [conn]
      (insert-test-memories! conn)
      (let [ds     (strat/materialize conn)
            result (strat/avg-importance-by-layer ds)]
        (testing "returns average importance per layer"
          (is (seq result))
          (let [by-layer (into {} (map (fn [r] [(:layer r) (:avg r)]) result))]
            ;; fact has importance 0.7
            (is (< (abs (- 0.7 (get by-layer "fact"))) 0.01))))))))

(deftest query-sql-test
  (th/with-datahike
    (fn [conn]
      (insert-test-memories! conn)
      (let [ds     (strat/materialize conn)
            result (strat/query-sql ds
                                    "SELECT namespace, COUNT(*) AS cnt FROM memories GROUP BY namespace ORDER BY cnt DESC")]
        (testing "SQL query works against materialized dataset"
          (is (seq result))
          (is (= "default" (:namespace (first result))))
          (is (= 3 (:cnt (first result)))))))))

(deftest auto-sync-listener-test
  (th/with-datahike
    (fn [conn]
      (testing "listener auto-materializes on transaction"
        (let [ds-atom (strat/install-sync-listener! conn)]
          ;; Initially nil (empty db)
          (is (nil? @ds-atom))

          ;; Insert a memory - listener should fire
          (dh/insert-memory! conn {:memory/content   "test"
                                   :memory/layer     :layer/fact
                                   :memory/importance (float 0.5)
                                   :memory/source    "test"
                                   :memory/namespace "default"})
          ;; Give listener a moment
          (Thread/sleep 100)
          (is (some? @ds-atom))

          ;; Cleanup
          (strat/remove-sync-listener! conn))))))
