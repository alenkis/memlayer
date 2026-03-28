(ns memlayer.integration.helpers
  "Test helpers for integration tests: assertion utilities, wait functions."
  (:require [clojure.test :refer [is]]
            [clojure.string :as str]))

(defn wait-for-consistency
  "Sleep briefly to allow vector index sync and any async operations."
  []
  (Thread/sleep 200))

(defn contains-keyword?
  "Case-insensitive substring match."
  [text kw]
  (when (and text kw)
    (str/includes?
     (str/lower-case (str text))
     (str/lower-case (str kw)))))

(defn any-memory-contains?
  "Check if any memory in a collection contains the keyword in its content."
  [memories kw]
  (some #(contains-keyword? (:content %) kw) memories))

(defn assert-ok
  "Assert HTTP response is successful (2xx) and return the body."
  [response]
  (is (contains? #{200 201 204} (:status response))
      (str "Expected success, got " (:status response) ": " (:body response)))
  (:body response))

(defn assert-status
  "Assert a specific HTTP status code."
  [expected response]
  (is (= expected (:status response))
      (str "Expected " expected ", got " (:status response) ": " (:body response)))
  (:body response))

(defn get-operation-ids
  "Extract memory IDs from retain decisions by operation type."
  [retain-response op-type]
  (->> (:decisions retain-response)
       (filter #(= op-type (:type %)))
       (keep :memory_id)
       vec))

(defn get-create-ids [resp] (get-operation-ids resp "CREATE"))
(defn get-update-ids [resp] (get-operation-ids resp "UPDATE"))

(defn has-any-create?
  "Check if a retain response includes at least one CREATE operation."
  [retain-response]
  (some #(= "CREATE" (:type %)) (:decisions retain-response)))

(defn valid-uuid? [s]
  (try
    (java.util.UUID/fromString (str s))
    true
    (catch Exception _ false)))
