(ns memlayer.util.pagination-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.util.pagination :as pagination]))

;; ---------------------------------------------------------------------------
;; Example-based tests
;; ---------------------------------------------------------------------------

(deftest parse-pagination-defaults
  (testing "missing params default to max-page-size and offset 0"
    (let [{:keys [limit offset]} (pagination/parse-pagination {})]
      (is (= pagination/default-max-page-size limit))
      (is (= 0 offset)))))

(deftest parse-pagination-clamps-limit
  (testing "limit is clamped to max-page-size"
    (is (= 100 (:limit (pagination/parse-pagination {"limit" "999"}))))
    (is (= 50 (:limit (pagination/parse-pagination {"limit" "999"} {:max-page-size 50}))))))

(deftest parse-pagination-clamps-offset
  (testing "negative offset clamps to 0"
    (is (= 0 (:offset (pagination/parse-pagination {"offset" "-5"}))))))

(deftest parse-pagination-uses-valid-values
  (testing "valid values are used as-is"
    (let [{:keys [limit offset]} (pagination/parse-pagination {"limit" "25" "offset" "10"})]
      (is (= 25 limit))
      (is (= 10 offset)))))

;; ---------------------------------------------------------------------------
;; Generative tests
;; ---------------------------------------------------------------------------

(def gen-positive-int-string
  (gen/fmap str (gen/choose 1 500)))

(def gen-pagination-params
  (gen/let [has-limit  gen/boolean
            has-offset gen/boolean
            limit-str  gen-positive-int-string
            offset-str gen-positive-int-string]
    (cond-> {}
      has-limit  (assoc "limit" limit-str)
      has-offset (assoc "offset" offset-str))))

(defspec pagination-limit-at-most-max 100
  (prop/for-all [params gen-pagination-params]
                (<= (:limit (pagination/parse-pagination params))
                    pagination/default-max-page-size)))

(defspec pagination-offset-non-negative 100
  (prop/for-all [params gen-pagination-params]
                (>= (:offset (pagination/parse-pagination params)) 0)))

(defspec pagination-custom-max-respected 100
  (prop/for-all [params gen-pagination-params
                 max-size (gen/choose 1 200)]
                (<= (:limit (pagination/parse-pagination params {:max-page-size max-size}))
                    max-size)))
