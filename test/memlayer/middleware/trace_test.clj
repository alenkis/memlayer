(ns memlayer.middleware.trace-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.middleware.trace :as trace]))

(deftest wrap-trace-id-test
  (testing "generates trace-id and adds to response header"
    (let [handler  (trace/wrap-trace-id (fn [req]
                                          (is (string? (get-in req [:headers "x-trace-id"])))
                                          {:status 200 :body "ok"}))
          response (handler {:request-method :get :uri "/test" :headers {}})]
      (is (= 200 (:status response)))
      (is (string? (get-in response [:headers "X-Trace-Id"])))))

  (testing "propagates incoming trace-id from request header"
    (let [handler  (trace/wrap-trace-id (fn [req]
                                          (is (= "incoming-trace"
                                                 (get-in req [:headers "x-trace-id"])))
                                          {:status 200 :body "ok"}))
          response (handler {:request-method :get
                             :uri            "/test"
                             :headers        {"x-trace-id" "incoming-trace"}})]
      (is (= "incoming-trace" (get-in response [:headers "X-Trace-Id"]))))))
