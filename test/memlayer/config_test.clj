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
                                                    :prompts {:extraction "p"
                                                              :batch-extraction "p"
                                                              :decision "p"
                                                              :resolution "p"
                                                              :reflect "p"
                                                              :reflect-organize "p"
                                                              :reflect-organize-domains "p"
                                                              :reflect-summarize "p"
                                                              :reflect-connect "p"
                                                              :reflect-curate "p"
                                                              :recall "p"}
                                                    :tuning {:retain-context-threshold 0.4
                                                             :retain-context-limit 10
                                                             :recall-default-limit 10
                                                             :reflect-batch-size 15
                                                             :reflect-default-threshold 0.5}}))))

  (testing "validate-config! returns config on valid input"
    (let [valid {:server {:port 8080 :dashboard-port 3000}
                 :datahike {:backend :file :path "db"}
                 :proximum {:dim 1536 :capacity 100000 :backend :file :path "v"}
                 :openai {:api-key nil :base-url "u" :embedding-model "m"}
                 :groq {:api-key nil :base-url "u" :model "m"}
                 :prompts {:extraction "p"
                           :batch-extraction "p"
                           :decision "p"
                           :resolution "p"
                           :reflect "p"
                           :reflect-organize "p"
                           :reflect-organize-domains "p"
                           :reflect-summarize "p"
                           :reflect-connect "p"
                           :reflect-curate "p"
                           :recall "p"}
                 :auth {:auth-enabled false}
                 :tuning {:retain-context-threshold 0.4
                          :retain-context-limit 10
                          :recall-default-limit 10
                          :reflect-batch-size 15
                          :reflect-default-threshold 0.5
                          :memory-limit 1000}}]
      (is (= valid (config/validate-config! valid))))))
