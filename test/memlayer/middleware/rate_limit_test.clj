(ns memlayer.middleware.rate-limit-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.middleware.rate-limit :as rl]))

(deftest create-limiter-allows-within-limit
  (testing "requests within limit are allowed"
    (let [limiter (rl/create-limiter {:max-requests 3 :window-ms 60000})]
      (is (:allowed? (limiter "user-1")))
      (is (:allowed? (limiter "user-1")))
      (is (:allowed? (limiter "user-1"))))))

(deftest create-limiter-blocks-over-limit
  (testing "requests over limit are blocked"
    (let [limiter (rl/create-limiter {:max-requests 2 :window-ms 60000})]
      (is (:allowed? (limiter "user-1")))
      (is (:allowed? (limiter "user-1")))
      (is (not (:allowed? (limiter "user-1")))))))

(deftest create-limiter-tracks-remaining
  (testing "remaining count decreases with each request"
    (let [limiter (rl/create-limiter {:max-requests 3 :window-ms 60000})]
      (is (= 2 (:remaining (limiter "user-1"))))
      (is (= 1 (:remaining (limiter "user-1"))))
      (is (= 0 (:remaining (limiter "user-1"))))
      ;; Over limit, still 0
      (is (= 0 (:remaining (limiter "user-1")))))))

(deftest create-limiter-isolates-users
  (testing "different users have independent limits"
    (let [limiter (rl/create-limiter {:max-requests 1 :window-ms 60000})]
      (is (:allowed? (limiter "user-a")))
      (is (not (:allowed? (limiter "user-a"))))
      ;; user-b is independent
      (is (:allowed? (limiter "user-b"))))))

(deftest create-limiter-resets-after-window
  (testing "counter resets when window expires"
    (let [limiter (rl/create-limiter {:max-requests 1 :window-ms 50})]
      (is (:allowed? (limiter "user-1")))
      (is (not (:allowed? (limiter "user-1"))))
      ;; Wait for window to expire
      (Thread/sleep 100)
      (is (:allowed? (limiter "user-1"))))))

(deftest create-limiter-returns-reset-at
  (testing "reset-at is in the future"
    (let [limiter (rl/create-limiter {:max-requests 10 :window-ms 60000})
          now     (System/currentTimeMillis)
          result  (limiter "user-1")]
      (is (> (:reset-at result) now)))))

(deftest wrap-rate-limit-passes-through-when-allowed
  (testing "middleware passes request to handler when under limit"
    (let [limiter    (rl/create-limiter {:max-requests 10 :window-ms 60000})
          handler    (fn [_] {:status 200 :headers {} :body "ok"})
          middleware (rl/wrap-rate-limit handler {:limiter limiter :enabled? true})
          response   (middleware {:user-context {:user-id "u1"}})]
      (is (= 200 (:status response)))
      (is (contains? (:headers response) "X-RateLimit-Remaining")))))

(deftest wrap-rate-limit-returns-429-when-exceeded
  (testing "middleware returns 429 when rate limit exceeded"
    (let [limiter    (rl/create-limiter {:max-requests 1 :window-ms 60000})
          handler    (fn [_] {:status 200 :headers {} :body "ok"})
          middleware (rl/wrap-rate-limit handler {:limiter limiter :enabled? true})
          _first     (middleware {:user-context {:user-id "u1"}})
          response   (middleware {:user-context {:user-id "u1"}})]
      (is (= 429 (:status response)))
      (is (= "0" (get-in response [:headers "X-RateLimit-Remaining"])))
      (is (some? (get-in response [:headers "Retry-After"]))))))

(deftest wrap-rate-limit-disabled-passes-all
  (testing "middleware passes all requests when disabled"
    (let [limiter    (rl/create-limiter {:max-requests 1 :window-ms 60000})
          handler    (fn [_] {:status 200 :headers {} :body "ok"})
          middleware (rl/wrap-rate-limit handler {:limiter limiter :enabled? false})]
      (is (= 200 (:status (middleware {:user-context {:user-id "u1"}}))))
      (is (= 200 (:status (middleware {:user-context {:user-id "u1"}}))))
      (is (= 200 (:status (middleware {:user-context {:user-id "u1"}})))))))

(deftest wrap-rate-limit-uses-anonymous-without-user-context
  (testing "requests without user-context use 'anonymous' key"
    (let [limiter    (rl/create-limiter {:max-requests 1 :window-ms 60000})
          handler    (fn [_] {:status 200 :headers {} :body "ok"})
          middleware (rl/wrap-rate-limit handler {:limiter limiter :enabled? true})]
      (is (= 200 (:status (middleware {}))))
      (is (= 429 (:status (middleware {})))))))
