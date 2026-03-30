(ns memlayer.mcp.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.mcp.tools :as tools]))

(deftest tool-definitions-count
  (testing "all six tools are defined"
    (is (= 6 (count tools/tool-definitions)))))

(deftest recall-tool-has-advanced-params
  (testing "memlayer_recall includes temporal, layer, and graph params"
    (let [recall (tools/find-tool "memlayer_recall")
          props  (get-in recall [:inputSchema :properties])]
      (is (some? recall))
      (is (= "query" (first (get-in recall [:inputSchema :required]))))
      ;; Core params
      (is (some? (:query props)))
      (is (some? (:namespace props)))
      (is (some? (:limit props)))
      ;; Advanced params
      (is (= "string" (get-in props [:as-of :type])))
      (is (= "string" (get-in props [:layer :type])))
      (is (= "boolean" (get-in props [:expand-graph :type]))))))

(deftest recall-tool-advanced-param-descriptions
  (testing "advanced param descriptions mention their purpose"
    (let [recall (tools/find-tool "memlayer_recall")
          props  (get-in recall [:inputSchema :properties])]
      (is (re-find #"ISO-8601" (get-in props [:as-of :description])))
      (is (re-find #"domain.*concept.*fact.*episode" (get-in props [:layer :description])))
      (is (re-find #"ancestor" (get-in props [:expand-graph :description]))))))

(deftest find-tool-returns-nil-for-unknown
  (testing "find-tool returns nil for non-existent tool"
    (is (nil? (tools/find-tool "nonexistent_tool")))))

(deftest all-tools-have-required-fields
  (testing "every tool has name, description, and inputSchema"
    (doseq [tool tools/tool-definitions]
      (is (string? (:name tool)) (str "missing name on " tool))
      (is (string? (:description tool)) (str "missing description on " (:name tool)))
      (is (map? (:inputSchema tool)) (str "missing inputSchema on " (:name tool))))))
