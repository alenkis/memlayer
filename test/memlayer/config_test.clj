(ns memlayer.config-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.config :as config]))

(deftest load-config-returns-valid-config
  (testing "load-config returns a map that passes schema validation"
    (let [cfg (config/load-config)]
      (is (map? cfg))
      (is (contains? cfg :server))
      (is (contains? cfg :datahike))
      (is (contains? cfg :proximum))
      (is (contains? cfg :openai))
      (is (contains? cfg :groq))
      (is (contains? cfg :prompts))
      ;; Prompts should be resolved to content strings, not file paths
      (is (every? (fn [[_ v]] (and (string? v) (not (.endsWith v ".edn"))))
                  (:prompts cfg)))
      (is (contains? cfg :auth))
      (is (contains? cfg :firebase))
      (is (contains? cfg :dynamodb))
      (is (contains? cfg :rate-limit))
      (is (contains? cfg :tuning)))))

(deftest validate-config-rejects-bad-input
  (testing "validate-config! throws on missing keys"
    (is (thrown-with-msg? Exception #"Invalid configuration"
                          (config/validate-config! {}))))

  (testing "validate-config! throws on wrong types"
    (is (thrown-with-msg? Exception #"Invalid configuration"
                          (config/validate-config! {:server {:port "not-an-int" :dashboard-port 3000}
                                                    :datahike {:backend :file :path "db"}
                                                    :proximum {:dim 1536 :capacity 100000 :backend :file :path "v"}
                                                    :openai {:api-key nil :base-url "u" :embedding-model "m"}
                                                    :groq {:api-key nil :base-url "u" :model "m"}
                                                    :prompts {:extraction "prompts/extraction.edn"
                                                              :batch-extraction "prompts/batch-extraction.edn"
                                                              :decision "prompts/decision.edn"
                                                              :resolution "prompts/resolution.edn"
                                                              :reflect "prompts/reflect.edn"
                                                              :reflect-organize "prompts/reflect-organize.edn"
                                                              :reflect-organize-domains "prompts/reflect-organize-domains.edn"
                                                              :reflect-summarize "prompts/reflect-summarize.edn"
                                                              :reflect-connect "prompts/reflect-connect.edn"
                                                              :reflect-curate "prompts/reflect-curate.edn"
                                                              :recall "prompts/recall.edn"}
                                                    :auth {:e2e-mode false :api-key-hash nil}
                                                    :firebase {:project-id "p"}
                                                    :dynamodb {:endpoint nil :region "us-east-1" :table nil}
                                                    :rate-limit {:enabled true :max-requests 60 :window-ms 60000}
                                                    :tuning {:retain-context-threshold 0.4
                                                             :retain-context-limit 10
                                                             :recall-default-limit 10
                                                             :recall-default-threshold 1.5
                                                             :recall-temporal-penalty 1.3
                                                             :reflect-batch-size 15
                                                             :reflect-default-threshold 0.5
                                                             :memory-limit 1000}}))))

  (testing "validate-config! returns config on valid input"
    (let [valid {:server {:port 8080 :dashboard-port 3000}
                 :datahike {:backend :file :path "db"}
                 :proximum {:dim 1536 :capacity 100000 :backend :file :path "v"}
                 :openai {:api-key nil :base-url "u" :embedding-model "m"}
                 :groq {:api-key nil :base-url "u" :model "m"}
                 :prompts {:extraction "prompts/extraction.edn"
                           :batch-extraction "prompts/batch-extraction.edn"
                           :decision "prompts/decision.edn"
                           :resolution "prompts/resolution.edn"
                           :reflect "prompts/reflect.edn"
                           :reflect-organize "prompts/reflect-organize.edn"
                           :reflect-organize-domains "prompts/reflect-organize-domains.edn"
                           :reflect-summarize "prompts/reflect-summarize.edn"
                           :reflect-connect "prompts/reflect-connect.edn"
                           :reflect-curate "prompts/reflect-curate.edn"
                           :recall "prompts/recall.edn"}
                 :auth {:e2e-mode false :api-key-hash nil}
                 :firebase {:project-id "p"}
                 :dynamodb {:endpoint nil :region "us-east-1" :table nil}
                 :rate-limit {:enabled true :max-requests 60 :window-ms 60000}
                 :tuning {:retain-dedup-threshold 0.15
                          :retain-context-threshold 0.4
                          :retain-context-limit 10
                          :recall-default-limit 10
                          :recall-default-threshold 1.5
                          :recall-temporal-penalty 1.3
                          :reflect-batch-size 15
                          :reflect-default-threshold 0.5
                          :memory-limit 1000}}]
      (is (= valid (config/validate-config! valid))))))
