(ns memlayer.persistence.migrations-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.migrations :as migrations]
            [memlayer.schema :as schema]
            [memlayer.test-helpers :as th]))

(deftest migration-001-backfills-namespace
  (th/with-datahike
    (fn [conn]
      (testing "backfills memories without namespace to \"default\""
        ;; Insert memories without namespace (simulating pre-migration data)
        (dh/insert-memory! conn {:memory/content "no namespace"
                                 :memory/layer   :layer/fact})
        (dh/insert-memory! conn {:memory/content "also no namespace"
                                 :memory/layer   :layer/fact})
        ;; Insert one WITH namespace
        (dh/insert-memory! conn {:memory/content   "has namespace"
                                 :memory/layer      :layer/fact
                                 :memory/namespace  "custom"})
        ;; Run migrations
        (migrations/run-migrations! conn)
        ;; All memories should now have a namespace
        (let [all (dh/get-all-memories conn)]
          (is (= 3 (count all)))
          (is (every? #(some? (:memory/namespace %)) all))
          ;; The two without namespace should now be "default"
          (is (= 2 (count (filter #(= schema/default-namespace (:memory/namespace %)) all))))
          ;; The one with "custom" should be unchanged
          (is (= 1 (count (filter #(= "custom" (:memory/namespace %)) all)))))))))

(deftest migration-is-idempotent
  (th/with-datahike
    (fn [conn]
      (testing "running migrations twice does not fail or duplicate"
        (dh/insert-memory! conn {:memory/content "test"
                                 :memory/layer   :layer/fact})
        (migrations/run-migrations! conn)
        (migrations/run-migrations! conn)
        (let [all (dh/get-all-memories conn)]
          (is (= 1 (count all)))
          (is (= schema/default-namespace (:memory/namespace (first all)))))))))

(deftest nil-namespace-assertion-in-insert
  (th/with-datahike
    (fn [conn]
      (testing "insert-memory! throws when :memory/namespace is explicitly nil"
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"Cannot insert memory with nil namespace"
             (dh/insert-memory! conn {:memory/content   "bad"
                                      :memory/layer     :layer/fact
                                      :memory/namespace nil})))))))

(deftest nil-namespace-assertion-in-update
  (th/with-datahike
    (fn [conn]
      (testing "update-memory! throws when :memory/namespace is explicitly nil"
        (let [id (dh/insert-memory! conn {:memory/content   "good"
                                          :memory/layer     :layer/fact
                                          :memory/namespace "default"})]
          (is (thrown-with-msg?
               clojure.lang.ExceptionInfo
               #"Cannot update memory with nil namespace"
               (dh/update-memory! conn id {:memory/namespace nil}))))))))
