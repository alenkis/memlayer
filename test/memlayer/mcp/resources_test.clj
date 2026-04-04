(ns memlayer.mcp.resources-test
  (:require [clojure.test :refer [deftest is]]
            [memlayer.mcp.resources :as resources])
  (:import [java.io File]))

(def ^:private temp-dir (System/getProperty "java.io.tmpdir"))

(defn- temp-file
  "Create a temporary file with the given content. Returns the path."
  [content]
  (let [f (File/createTempFile "memlayer-test-instructions" ".md" (File. ^String temp-dir))]
    (.deleteOnExit f)
    (spit f content :encoding "UTF-8")
    (.getAbsolutePath f)))

;; -- instructions-text --

(deftest instructions-text-no-file-returns-base
  (let [base (resources/instructions-text)]
    (is (string? base))
    (is (.contains base "memlayer"))))

(deftest instructions-text-nonexistent-path-returns-base
  (let [base (resources/instructions-text)
        result (resources/instructions-text "/nonexistent/path/instructions.md")]
    (is (= base result))))

(deftest instructions-text-nil-path-returns-base
  (let [base (resources/instructions-text)
        result (resources/instructions-text nil)]
    (is (= base result))))

(deftest instructions-text-with-user-file-overrides
  (let [path (temp-file "# My Custom Instructions\n\nCustom content only.")
        base (resources/instructions-text)
        result (resources/instructions-text path)]
    (is (= "# My Custom Instructions\n\nCustom content only." result))
    (is (not= base result))))

;; -- read-resource --

(deftest read-resource-skill-without-instructions-file
  (let [result (resources/read-resource "memlayer://skill")]
    (is (= 1 (count (:contents result))))
    (is (= "memlayer://skill" (:uri (first (:contents result)))))
    (is (.contains (:text (first (:contents result))) "memlayer_retain"))))

(deftest read-resource-skill-with-instructions-file
  (let [path (temp-file "Custom instructions only")
        result (resources/read-resource "memlayer://skill" path)
        text (:text (first (:contents result)))]
    (is (= "Custom instructions only" text))
    (is (not (.contains text "memlayer_retain")))))

(deftest read-resource-unknown-uri-throws
  (is (thrown? Exception (resources/read-resource "memlayer://nonexistent"))))
