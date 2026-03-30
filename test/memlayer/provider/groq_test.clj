(ns memlayer.provider.groq-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.provider.groq :as groq]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.llm.completion :as completion]))

(deftest create-client-returns-chat-provider
  (testing "creates a GroqChatProvider that satisfies ChatProvider"
    (let [client (groq/create-client {:api-key "test-key"
                                      :base-url "https://api.groq.com/openai/v1"
                                      :model "llama-3.3-70b-versatile"})]
      (is (satisfies? llm-provider/ChatProvider client))
      (is (= "test-key" (:api-key client)))
      (is (= "llama-3.3-70b-versatile" (:model client)))
      (is (some? (:http-client client))))))

(deftest parse-extraction-response-test
  (testing "parses a JSON array extraction response"
    (let [text "[{\"content\":\"User likes Clojure\",\"layer\":\"fact\"}]"
          result (completion/parse-extraction-response text)]
      (is (= 1 (count result)))
      (is (= "User likes Clojure" (:content (first result))))
      (is (= "fact" (:layer (first result))))))

  (testing "rejects markdown code fences (json_object mode should not produce them)"
    (let [text "```json\n[{\"content\":\"test\",\"layer\":\"concept\"}]\n```"]
      (is (thrown? Exception (completion/parse-extraction-response text)))))

  (testing "parses multiple extracted memories"
    (let [text "[{\"content\":\"A\",\"layer\":\"domain\"},
                 {\"content\":\"B\",\"layer\":\"episode\"}]"
          result (completion/parse-extraction-response text)]
      (is (= 2 (count result)))
      (is (= "domain" (:layer (first result))))
      (is (= "episode" (:layer (second result)))))))
