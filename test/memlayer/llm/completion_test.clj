(ns memlayer.llm.completion-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.llm.completion :as completion]
            [memlayer.test-helpers :as th]
            [clojure.string :as str]))

(deftest parse-extraction-response-test
  (testing "parses a JSON array response"
    (let [text "[{\"content\":\"User likes Clojure\",\"layer\":\"fact\",\"importance\":0.8}]"
          result (completion/parse-extraction-response text)]
      (is (= 1 (count result)))
      (is (= "User likes Clojure" (:content (first result))))
      (is (= "fact" (:layer (first result))))
      (is (= 0.8 (:importance (first result))))))

  (testing "rejects markdown code fences (json_object mode should not produce them)"
    (let [text "```json\n[{\"content\":\"test\",\"layer\":\"concept\",\"importance\":0.5}]\n```"]
      (is (thrown? Exception (completion/parse-extraction-response text)))))

  (testing "parses multiple items"
    (let [text "[{\"content\":\"A\",\"layer\":\"domain\",\"importance\":0.9},
                 {\"content\":\"B\",\"layer\":\"episode\",\"importance\":0.3}]"
          result (completion/parse-extraction-response text)]
      (is (= 2 (count result)))
      (is (= "domain" (:layer (first result))))
      (is (= "episode" (:layer (second result)))))))

;; -- format-candidates-text (private, accessed via var) --

(def ^:private format-candidates-text #'completion/format-candidates-text)
(def ^:private format-subgraph-text #'completion/format-subgraph-text)

(deftest format-candidates-text-test
  (testing "formats candidates with memory IDs and similarity"
    (let [id1 (java.util.UUID/randomUUID)
          id2 (java.util.UUID/randomUUID)
          candidates [{:memory-id id1 :content "Clojure is great" :distance 0.15}
                      {:memory-id id2 :content "FP is powerful" :distance 0.25}]
          result (format-candidates-text candidates)]
      (is (str/includes? result (str id1)))
      (is (str/includes? result (str id2)))
      (is (str/includes? result "Clojure is great"))
      (is (str/includes? result "FP is powerful"))
      (is (str/includes? result "similarity: 0.15"))
      (is (str/includes? result "similarity: 0.25"))))

  (testing "returns fallback text for empty candidates"
    (is (= "No similar memories found." (format-candidates-text [])))
    (is (= "No similar memories found." (format-candidates-text nil)))))

(deftest format-subgraph-text-test
  (let [id1 (java.util.UUID/randomUUID)
        id2 (java.util.UUID/randomUUID)
        id3 (java.util.UUID/randomUUID)]
    (testing "formats edges and known types"
      (let [subgraph {:edges [{:relationship/source-id id1
                               :relationship/target-id id2
                               :relationship/type :elaborates}
                              {:relationship/source-id id2
                               :relationship/target-id id3
                               :relationship/type :supports}]
                      :known-types [:elaborates :supports :caused-by]}
            result (format-subgraph-text subgraph)]
        (is (str/includes? result "Existing relationships between these memories:"))
        (is (str/includes? result (str id1)))
        (is (str/includes? result "--elaborates-->"))
        (is (str/includes? result "--supports-->"))
        (is (str/includes? result "Relationship types in use:"))
        (is (str/includes? result "elaborates"))
        (is (str/includes? result "caused-by"))))

    (testing "returns empty string for nil subgraph"
      (is (= "" (format-subgraph-text {})))
      (is (= "" (format-subgraph-text {:edges [] :known-types []}))))

    (testing "formats only types when no edges"
      (let [result (format-subgraph-text {:edges [] :known-types [:related-to]})]
        (is (not (str/includes? result "Existing relationships")))
        (is (str/includes? result "Relationship types in use: related-to"))))

    (testing "formats only edges when no known types"
      (let [result (format-subgraph-text {:edges [{:relationship/source-id id1
                                                   :relationship/target-id id2
                                                   :relationship/type :refines}]
                                          :known-types []})]
        (is (str/includes? result "--refines-->"))
        (is (not (str/includes? result "Relationship types in use:")))))))

;; -- decide-action --

(deftest decide-action-parses-relationships
  (testing "decide-action parses relationships from LLM response"
    (let [target-id (str (java.util.UUID/randomUUID))
          provider (th/mock-chat-provider
                    (fn [_msgs _opts]
                      {:action "CREATE"
                       :reasoning "New memory"
                       :relationships [{:target_id target-id :type "elaborates"}
                                       {:target_id target-id :type "supports"}]}))
          result (completion/decide-action provider th/mock-prompts
                                           {:content "test memory" :layer "fact" :importance 0.7}
                                           [])]
      (is (= "CREATE" (get-in result [:result :action])))
      (is (= 2 (count (get-in result [:result :relationships]))))
      ;; jsonista mapper converts target_id -> target-id
      (is (= target-id (get-in result [:result :relationships 0 :target-id])))
      (is (= "elaborates" (get-in result [:result :relationships 0 :type]))))))

(deftest decide-action-handles-no-relationships
  (testing "decide-action handles response without relationships field"
    (let [provider (th/mock-chat-provider
                    (fn [_msgs _opts]
                      {:action "NOOP"
                       :reasoning "Already known"}))
          result (completion/decide-action provider th/mock-prompts
                                           {:content "test" :layer "fact" :importance 0.5}
                                           [])]
      (is (= "NOOP" (get-in result [:result :action])))
      (is (nil? (get-in result [:result :relationships]))))))

(deftest decide-action-includes-subgraph-in-prompt
  (testing "decide-action passes subgraph context to the LLM prompt"
    (let [captured-msgs (atom nil)
          id1 (java.util.UUID/randomUUID)
          id2 (java.util.UUID/randomUUID)
          provider (th/mock-chat-provider
                    (fn [msgs _opts]
                      (reset! captured-msgs msgs)
                      {:action "CREATE" :reasoning "New"}))
          subgraph {:edges [{:relationship/source-id id1
                             :relationship/target-id id2
                             :relationship/type :related-to}]
                    :known-types [:related-to :elaborates]}]
      (completion/decide-action provider th/mock-prompts
                                {:content "test memory" :layer "fact" :importance 0.7}
                                [{:memory-id id1 :content "existing" :distance 0.1}]
                                :subgraph subgraph)
      (let [user-msg (:content (second @captured-msgs))]
        (is (str/includes? user-msg (str id1)))
        (is (str/includes? user-msg "Existing relationships between these memories:"))
        (is (str/includes? user-msg "--related-to-->"))
        (is (str/includes? user-msg "Relationship types in use:"))))))
