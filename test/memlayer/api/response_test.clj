(ns memlayer.api.response-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.api.response :as response]))

;; ---------------------------------------------------------------------------
;; Example-based tests
;; ---------------------------------------------------------------------------

(deftest flow-result-nil-returns-504
  (testing "nil result returns 504 timeout"
    (let [resp (response/flow-result->response nil identity)]
      (is (= 504 (:status resp)))
      (is (= "Processing timeout" (get-in resp [:body :error]))))))

(deftest flow-result-success-calls-body-fn
  (testing "success result calls body-fn and returns 201"
    (let [resp (response/flow-result->response
                {:memory-ids [1 2 3]}
                (fn [r] {:ids (:memory-ids r)}))]
      (is (= 201 (:status resp)))
      (is (= {:ids [1 2 3]} (:body resp))))))

;; ---------------------------------------------------------------------------
;; Generative tests
;; ---------------------------------------------------------------------------

(def gen-success-result
  (gen/let [n (gen/choose 1 10)]
    {:memory-ids (vec (range n))}))

(defspec nil-always-504 100
  (prop/for-all [_ gen/small-integer]
                (= 504 (:status (response/flow-result->response nil identity)))))

(defspec success-always-201 100
  (prop/for-all [result gen-success-result]
                (= 201 (:status (response/flow-result->response result identity)))))
