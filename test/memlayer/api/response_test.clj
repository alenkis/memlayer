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

(deftest flow-result-error-returns-429
  (testing "error result returns 429 with quota info"
    (let [resp (response/flow-result->response
                {:error :memory-limit-exceeded :current-count 50 :limit 50}
                identity)]
      (is (= 429 (:status resp)))
      (is (= 50 (get-in resp [:body :current-count])))
      (is (= 50 (get-in resp [:body :limit]))))))

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

(def gen-error-result
  (gen/let [count (gen/choose 0 1000)
            limit (gen/choose 1 1000)]
    {:error :memory-limit-exceeded :current-count count :limit limit}))

(def gen-success-result
  (gen/let [n (gen/choose 1 10)]
    {:memory-ids (vec (range n))}))

(defspec nil-always-504 100
  (prop/for-all [_ gen/small-integer]
                (= 504 (:status (response/flow-result->response nil identity)))))

(defspec error-always-429 100
  (prop/for-all [result gen-error-result]
                (let [resp (response/flow-result->response result identity)]
                  (and (= 429 (:status resp))
                       (= (:current-count result) (get-in resp [:body :current-count]))
                       (= (:limit result) (get-in resp [:body :limit]))))))

(defspec success-always-201 100
  (prop/for-all [result gen-success-result]
                (= 201 (:status (response/flow-result->response result identity)))))
