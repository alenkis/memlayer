(ns memlayer.persistence.usage-test
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [memlayer.test-helpers :as th]
            [memlayer.persistence.usage :as usage])
  (:import [java.util Calendar UUID]))

(deftest record-and-query-usage-test
  (th/with-datahike
    (fn [conn]
      (testing "record-usage! inserts an event that can be queried"
        (usage/record-usage! conn {:operation         "retain"
                                   :step              "context-embed"
                                   :provider          "openai"
                                   :model             "text-embedding-3-small"
                                   :namespace         "default"
                                   :prompt-tokens     100
                                   :completion-tokens 0
                                   :total-tokens      100
                                   :cost-usd          0.000002})
        (let [summary (usage/aggregate-summary conn {:range-days 7})]
          (is (= 100 (:total-tokens summary)))
          (is (= 1 (count (:by-provider summary))))
          (is (= "openai" (:provider (first (:by-provider summary))))))))))

(deftest aggregate-summary-test
  (th/with-datahike
    (fn [conn]
      (testing "aggregates across multiple events and providers"
        (usage/record-usage! conn {:operation "retain" :step "context-embed"
                                   :provider "openai" :model "text-embedding-3-small"
                                   :namespace "default"
                                   :prompt-tokens 100 :completion-tokens 0 :total-tokens 100
                                   :cost-usd 0.000002})
        (usage/record-usage! conn {:operation "retain" :step "resolution"
                                   :provider "groq" :model "llama-3.3-70b-versatile"
                                   :namespace "default"
                                   :prompt-tokens 500 :completion-tokens 200 :total-tokens 700
                                   :cost-usd 0.000453})
        (let [summary (usage/aggregate-summary conn {:range-days 7})]
          (is (= 800 (:total-tokens summary)))
          (is (= 2 (count (:by-provider summary))))
          (is (= 2 (count (:by-operation summary)))))))))

(deftest aggregate-by-namespace-test
  (th/with-datahike
    (fn [conn]
      (testing "aggregates tokens by namespace"
        (usage/record-usage! conn {:operation "retain" :step "context-embed"
                                   :provider "openai" :model "m"
                                   :namespace "ns-a"
                                   :prompt-tokens 100 :completion-tokens 0 :total-tokens 100
                                   :cost-usd 0.0})
        (usage/record-usage! conn {:operation "recall" :step "query-embed"
                                   :provider "openai" :model "m"
                                   :namespace "ns-b"
                                   :prompt-tokens 200 :completion-tokens 0 :total-tokens 200
                                   :cost-usd 0.0})
        (usage/record-usage! conn {:operation "retain" :step "resolution"
                                   :provider "groq" :model "m"
                                   :namespace "ns-a"
                                   :prompt-tokens 300 :completion-tokens 50 :total-tokens 350
                                   :cost-usd 0.0})
        (let [by-ns (usage/aggregate-by-namespace conn {:range-days 7})]
          (is (= 2 (count by-ns)))
          ;; sorted by total-tokens desc
          (is (= "ns-a" (:namespace (first by-ns))))
          (is (= 450 (:total-tokens (first by-ns))))
          (is (= "ns-b" (:namespace (second by-ns))))
          (is (= 200 (:total-tokens (second by-ns)))))))))

(deftest aggregate-timeseries-test
  (th/with-datahike
    (fn [conn]
      (testing "returns daily aggregated timeseries"
        (usage/record-usage! conn {:operation "retain" :step "context-embed"
                                   :provider "openai" :model "m"
                                   :namespace "default"
                                   :prompt-tokens 100 :completion-tokens 0 :total-tokens 100
                                   :cost-usd 0.0})
        (let [ts (usage/aggregate-timeseries conn {:range-days 7})]
          (is (= 1 (count ts)))
          (is (string? (:date (first ts))))
          (is (= 100 (:total-tokens (first ts)))))))))

(deftest pricing-crud-test
  (th/with-datahike
    (fn [conn]
      (testing "default pricing is seeded"
        (let [p (usage/get-pricing conn "text-embedding-3-small")]
          (is (some? p))
          (is (= 0.02 (:prompt p)))
          (is (= 0.0 (:completion p)))))

      (testing "update-pricing! changes current pricing"
        (usage/update-pricing! conn "text-embedding-3-small" {:prompt 0.04 :completion 0.0})
        (let [p (usage/get-pricing conn "text-embedding-3-small")]
          (is (= 0.04 (:prompt p)))))

      (testing "unknown model returns nil"
        (is (nil? (usage/get-pricing conn "nonexistent-model")))))))

(deftest estimate-cost-test
  (th/with-datahike
    (fn [conn]
      (testing "estimates cost from Datahike pricing"
        (let [cost (usage/estimate-cost conn "llama-3.3-70b-versatile" 1000000 1000000)]
          ;; prompt: 0.59 USD + completion: 0.79 USD = 1.38 USD
          (is (< (Math/abs (- 1.38 cost)) 0.001))))

      (testing "returns 0.0 for unknown models"
        (is (= 0.0 (usage/estimate-cost conn "unknown-model" 1000 500)))))))

(defn- days-ago
  "Return a java.util.Date n days in the past."
  [n]
  (let [cal (Calendar/getInstance)]
    (.add cal Calendar/DAY_OF_YEAR (- n))
    (.getTime cal)))

(deftest date-range-filtering-test
  (th/with-datahike
    (fn [conn]
      (testing "events outside the range are excluded from aggregation"
        ;; Insert a recent event via record-usage! (timestamp = now)
        (usage/record-usage! conn {:operation "retain" :step "context-embed"
                                   :provider "openai" :model "m"
                                   :namespace "default"
                                   :prompt-tokens 100 :completion-tokens 0 :total-tokens 100
                                   :cost-usd 0.0})
        ;; Insert an old event directly with a timestamp 60 days ago
        (d/transact (:conn conn) [{:usage/id                (UUID/randomUUID)
                                   :usage/operation         "retain"
                                   :usage/step              "old-step"
                                   :usage/provider          "openai"
                                   :usage/model             "m"
                                   :usage/namespace         "default"
                                   :usage/prompt-tokens     500
                                   :usage/completion-tokens 0
                                   :usage/total-tokens      500
                                   :usage/cost-usd          0.0
                                   :usage/timestamp         (days-ago 60)}])
        ;; 7-day range should only include the recent event
        (let [summary-7 (usage/aggregate-summary conn {:range-days 7})]
          (is (= 100 (:total-tokens summary-7))))
        ;; 90-day range should include both
        (let [summary-90 (usage/aggregate-summary conn {:range-days 90})]
          (is (= 600 (:total-tokens summary-90))))))))

(deftest record-from-provider-test
  (th/with-datahike
    (fn [conn]
      (testing "record-from-provider! computes cost and stores event"
        (usage/record-from-provider! conn
                                     {:operation "retain" :step "context-embed" :namespace "test-ns"}
                                     {:provider "openai" :model "text-embedding-3-small"
                                      :prompt-tokens 1000000 :completion-tokens 0 :total-tokens 1000000})
        (let [summary (usage/aggregate-summary conn {:range-days 7})]
          (is (= 1000000 (:total-tokens summary)))
          ;; cost should be ~0.02 USD (1M tokens * 0.02 USD/1M)
          (is (< (Math/abs (- 0.02 (:total-cost summary))) 0.001)))))))
