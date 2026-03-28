(ns memlayer.mcp.protocol-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.mcp.protocol :as proto]
            [jsonista.core :as j]))

(def ^:private json-mapper (j/object-mapper {:decode-key-fn keyword}))

(deftest parse-message-test
  (testing "parses a valid JSON-RPC request"
    (let [msg (proto/parse-message
               "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\",\"params\":{}}")]
      (is (= "2.0" (:jsonrpc msg)))
      (is (= 1 (:id msg)))
      (is (= "tools/list" (:method msg)))))

  (testing "returns error for invalid JSON"
    (let [msg (proto/parse-message "not json")]
      (is (some? (:error msg))))))

(deftest success-response-test
  (testing "builds correct success response"
    (let [resp (proto/success-response 1 {:tools []})]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (is (= {:tools []} (:result resp))))))

(deftest error-response-test
  (testing "builds correct error response"
    (let [resp (proto/error-response 1 -32601 "Method not found")]
      (is (= "2.0" (:jsonrpc resp)))
      (is (= 1 (:id resp)))
      (is (= -32601 (get-in resp [:error :code])))
      (is (= "Method not found" (get-in resp [:error :message])))))

  (testing "builds error response with data"
    (let [resp (proto/error-response 1 -32603 "Internal error" {:detail "something"})]
      (is (= {:detail "something"} (get-in resp [:error :data]))))))

(deftest encode-test
  (testing "encodes response to JSON string"
    (let [resp (proto/success-response 1 {:status "ok"})
          json (proto/encode resp)
          parsed (j/read-value json json-mapper)]
      (is (string? json))
      (is (= "2.0" (:jsonrpc parsed)))
      (is (= "ok" (get-in parsed [:result :status]))))))

;; ---------------------------------------------------------------------------
;; Generative / property-based tests
;; ---------------------------------------------------------------------------

(def gen-json-id
  "Valid JSON-RPC id: integer or non-empty string."
  (gen/one-of [(gen/choose 1 10000)
               (gen/not-empty gen/string-alphanumeric)]))

(def ^:private gen-alpha-keyword
  "Keywords safe for JSON round-trip (no underscores)."
  (gen/fmap keyword
            (gen/fmap #(apply str %)
                      (gen/not-empty
                       (gen/vector (gen/elements "abcdefghijklmnopqrstuvwxyz") 1 8)))))

(def gen-json-leaf
  (gen/one-of [(gen/choose -1000 1000)
               gen/string-alphanumeric
               (gen/return true)
               (gen/return false)
               (gen/return nil)]))

(def gen-simple-map
  "Shallow map safe for JSON round-tripping via memlayer.json/mapper."
  (gen/map gen-alpha-keyword gen-json-leaf {:max-elements 5}))

(defspec success-response-round-trips 100
  (prop/for-all [id gen-json-id
                 result gen-simple-map]
                (let [resp (proto/success-response id result)
                      parsed (proto/parse-message (proto/encode resp))]
                  (and (= "2.0" (:jsonrpc parsed))
                       (= id (:id parsed))
                       (= result (:result parsed))))))

(defspec all-responses-have-jsonrpc-2 100
  (prop/for-all [id gen-json-id
                 result gen-simple-map
                 code (gen/choose -32700 -32600)
                 message (gen/not-empty gen/string-alphanumeric)
                 method (gen/not-empty gen/string-alphanumeric)
                 params gen-simple-map]
                (and (= "2.0" (:jsonrpc (proto/success-response id result)))
                     (= "2.0" (:jsonrpc (proto/error-response id code message)))
                     (= "2.0" (:jsonrpc (proto/notification-response method params))))))

(defspec error-response-has-code-and-message 100
  (prop/for-all [id gen-json-id
                 code (gen/choose -32700 -32600)
                 message (gen/not-empty gen/string-alphanumeric)]
                (let [resp (proto/error-response id code message)]
                  (and (= id (:id resp))
                       (integer? (get-in resp [:error :code]))
                       (string? (get-in resp [:error :message]))))))

(defspec notification-has-no-id 100
  (prop/for-all [method (gen/not-empty gen/string-alphanumeric)
                 params gen-simple-map]
                (not (contains? (proto/notification-response method params) :id))))
