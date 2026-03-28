(ns memlayer.provider.openai-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.provider.openai :as openai]
            [memlayer.provider.llm :as llm-provider]))

(deftest parse-embedding-response-test
  (testing "parses a well-formed embedding response"
    (let [response {:data [{:index 0 :embedding [0.1 0.2 0.3]}
                           {:index 1 :embedding [0.4 0.5 0.6]}]
                    :model "text-embedding-3-small"
                    :usage {:prompt_tokens 10 :total_tokens 10}}
          result   (openai/parse-embedding-response response)]
      (is (= 2 (count result)))
      (is (= 3 (alength (first result))))
      (is (float? (aget (first result) 0)))
      (is (< (Math/abs (- 0.1 (aget (first result) 0))) 0.001))))

  (testing "sorts by index"
    (let [response {:data [{:index 1 :embedding [0.4 0.5 0.6]}
                           {:index 0 :embedding [0.1 0.2 0.3]}]}
          result   (openai/parse-embedding-response response)]
      (is (< (Math/abs (- 0.1 (aget (first result) 0))) 0.001))
      (is (< (Math/abs (- 0.4 (aget (second result) 0))) 0.001)))))

(deftest create-client-returns-embedding-provider
  (testing "creates an OpenAIEmbeddingProvider that satisfies EmbeddingProvider"
    (let [client (openai/create-client {:api-key "test-key"
                                        :base-url "https://api.openai.com/v1"
                                        :embedding-model "text-embedding-3-small"})]
      (is (satisfies? llm-provider/EmbeddingProvider client))
      (is (= "test-key" (:api-key client)))
      (is (= "https://api.openai.com/v1" (:base-url client)))
      (is (= "text-embedding-3-small" (:embedding-model client)))
      (is (some? (:http-client client))))))
