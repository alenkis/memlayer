(ns memlayer.util.retry-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.util.retry :as retry]))

(deftest with-retry-succeeds-immediately
  (testing "returns result when no exception"
    (is (= 42 (retry/with-retry (fn [] 42))))))

(deftest with-retry-retries-on-retryable-error
  (testing "retries on 429 and eventually succeeds"
    (let [call-count (atom 0)]
      (is (= "ok"
             (retry/with-retry
               (fn []
                 (swap! call-count inc)
                 (if (< @call-count 3)
                   (throw (ex-info "rate limited" {:status 429}))
                   "ok"))
               {:max-retries 3 :base-delay-ms 1 :max-delay-ms 10})))
      (is (= 3 @call-count)))))

(deftest with-retry-retries-on-500
  (testing "retries on 500 server error"
    (let [call-count (atom 0)]
      (is (= "recovered"
             (retry/with-retry
               (fn []
                 (swap! call-count inc)
                 (if (= 1 @call-count)
                   (throw (ex-info "server error" {:status 500}))
                   "recovered"))
               {:max-retries 2 :base-delay-ms 1 :max-delay-ms 5})))
      (is (= 2 @call-count)))))

(deftest with-retry-does-not-retry-400
  (testing "does not retry on 400 bad request"
    (let [call-count (atom 0)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/with-retry
                     (fn []
                       (swap! call-count inc)
                       (throw (ex-info "bad request" {:status 400})))
                     {:max-retries 3 :base-delay-ms 1})))
      (is (= 1 @call-count)))))

(deftest with-retry-does-not-retry-401
  (testing "does not retry on 401 unauthorized"
    (let [call-count (atom 0)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/with-retry
                     (fn []
                       (swap! call-count inc)
                       (throw (ex-info "unauthorized" {:status 401})))
                     {:max-retries 3 :base-delay-ms 1})))
      (is (= 1 @call-count)))))

(deftest with-retry-exhausts-retries
  (testing "throws after max retries exhausted"
    (let [call-count (atom 0)]
      (is (thrown? clojure.lang.ExceptionInfo
                   (retry/with-retry
                     (fn []
                       (swap! call-count inc)
                       (throw (ex-info "server error" {:status 500})))
                     {:max-retries 2 :base-delay-ms 1 :max-delay-ms 5})))
      ;; 1 initial + 2 retries = 3 calls
      (is (= 3 @call-count)))))

(deftest with-retry-returns-nil
  (testing "returns nil when f returns nil"
    (is (nil? (retry/with-retry (fn [] nil))))))
