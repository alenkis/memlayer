(ns memlayer.persistence.proximum-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.persistence.proximum :as prox]))

(defn- random-vec [dim]
  (float-array (repeatedly dim #(float (rand)))))

(deftest create-and-search-index
  (testing "store vectors and search for nearest neighbors"
    (let [dim   64
          idx   (prox/create-index! {:dim dim :capacity 1000})
          v1    (random-vec dim)
          v2    (random-vec dim)
          idx   (-> idx
                    (prox/store-vector! "mem-1" v1)
                    (prox/store-vector! "mem-2" v2)
                    (prox/sync-index!))
          results (prox/search idx v1 2)]
      (is (seq results))
      (is (= "mem-1" (:id (first results))))
      (is (<= (:distance (first results)) (:distance (second results)))))))

(deftest store-and-sync
  (testing "store-and-sync! convenience function"
    (let [dim 64
          idx (prox/create-index! {:dim dim :capacity 100})
          vec (random-vec dim)
          idx (prox/store-and-sync! idx "key-1" vec)
          results (prox/search idx vec 1)]
      (is (= 1 (count results)))
      (is (= "key-1" (:id (first results)))))))
