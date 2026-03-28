(ns memlayer.util.resources-test
  (:require [clojure.test :refer [deftest is testing]]
            [memlayer.util.resources :as resources]))

(deftest read-edn-test
  (testing "reads and parses an EDN resource from the classpath"
    (let [edn (resources/read-edn! "prompts/extraction.edn")]
      (is (map? edn))
      (is (string? (:system-prompt edn)))
      (is (seq (:system-prompt edn)))))

  (testing "returns nil for missing resource"
    (is (nil? (resources/read-edn! "nonexistent.edn"))))

  (testing "all prompt files load and contain :system-prompt"
    (doseq [path ["prompts/extraction.edn"
                  "prompts/batch-extraction.edn"
                  "prompts/decision.edn"
                  "prompts/resolution.edn"
                  "prompts/reflect.edn"]]
      (let [edn (resources/read-edn! path)]
        (is (some? edn) (str path " should exist"))
        (is (string? (:system-prompt edn)) (str path " should have :system-prompt"))))))
