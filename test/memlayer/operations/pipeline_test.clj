(ns memlayer.operations.pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [memlayer.operations.pipeline :as pipeline]))

;; ---------------------------------------------------------------------------
;; Generators
;; ---------------------------------------------------------------------------

(def gen-usage
  "Token usage map with the three standard keys."
  (gen/let [prompt     (gen/choose 0 10000)
            completion (gen/choose 0 10000)
            total      (gen/choose 0 20000)]
    {:prompt-tokens     prompt
     :completion-tokens completion
     :total-tokens      total}))

(def gen-full-usage
  "Usage map including embedding token counts."
  (gen/let [prompt     (gen/choose 0 10000)
            completion (gen/choose 0 10000)
            total      (gen/choose 0 20000)
            embed      (gen/choose 0 10000)]
    {:prompt-tokens     prompt
     :completion-tokens completion
     :total-tokens      total
     :embedding         {:total-tokens embed}}))

(def gen-cost-config
  (gen/let [embed-rate  (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})
            prompt-rate (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})
            compl-rate  (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})]
    {:embedding-per-1k-tokens      embed-rate
     :chat-prompt-per-1k-tokens    prompt-rate
     :chat-completion-per-1k-tokens compl-rate}))

(def gen-usage-with-embedding
  "Usage map that may include embedding tokens."
  (gen/let [base  gen-usage
            embed (gen/one-of [(gen/return nil)
                               (gen/let [t (gen/choose 0 10000)]
                                 {:total-tokens t})])]
    (if embed (assoc base :embedding embed) base)))

;; ---------------------------------------------------------------------------
;; merge-usage: monoid laws
;; ---------------------------------------------------------------------------

(defspec merge-usage-associative 100
  (prop/for-all [a gen-usage
                 b gen-usage
                 c gen-usage]
                (= (pipeline/merge-usage (pipeline/merge-usage a b) c)
                   (pipeline/merge-usage a (pipeline/merge-usage b c)))))

(defspec merge-usage-commutative 100
  (prop/for-all [a gen-usage
                 b gen-usage]
                (= (pipeline/merge-usage a b)
                   (pipeline/merge-usage b a))))

(defspec merge-usage-identity 100
  (prop/for-all [u gen-usage]
                (= u (pipeline/merge-usage pipeline/zero-usage u))))

(defspec merge-usage-preserves-embedding 100
  (prop/for-all [a gen-usage-with-embedding
                 b gen-usage-with-embedding]
                (let [merged (pipeline/merge-usage a b)
                      e-a    (get-in a [:embedding :total-tokens] 0)
                      e-b    (get-in b [:embedding :total-tokens] 0)]
                  (= (get-in merged [:embedding :total-tokens] 0)
                     (+ e-a e-b)))))

;; ---------------------------------------------------------------------------
;; estimate-cost: structural invariants
;; ---------------------------------------------------------------------------

(defspec estimate-cost-non-negative 100
  (prop/for-all [usage  gen-full-usage
                 config gen-cost-config]
                (let [result (pipeline/estimate-cost usage config)]
                  (and (>= (:embedding-cost result) 0)
                       (>= (:chat-cost result) 0)
                       (>= (:total-cost result) 0)))))

(defspec estimate-cost-total-is-sum 100
  (prop/for-all [usage  gen-full-usage
                 config gen-cost-config]
                (let [result (pipeline/estimate-cost usage config)]
                  (< (abs (- (:total-cost result)
                             (+ (:embedding-cost result) (:chat-cost result))))
                     1e-10))))

(defspec estimate-cost-zero-tokens-zero-cost 100
  (prop/for-all [config gen-cost-config]
                (let [result (pipeline/estimate-cost pipeline/zero-usage config)]
                  (and (= 0.0 (:embedding-cost result))
                       (= 0.0 (:chat-cost result))
                       (= 0.0 (:total-cost result))))))

(defspec estimate-cost-always-usd 100
  (prop/for-all [usage  gen-full-usage
                 config gen-cost-config]
                (= "USD" (:currency (pipeline/estimate-cost usage config)))))

;; ---------------------------------------------------------------------------
;; layer-str->keyword: exhaustive mapping
;; ---------------------------------------------------------------------------

(deftest layer-str->keyword-complete
  (testing "maps all valid layer strings"
    (is (= :layer/domain  (pipeline/layer-str->keyword "domain")))
    (is (= :layer/concept (pipeline/layer-str->keyword "concept")))
    (is (= :layer/fact    (pipeline/layer-str->keyword "fact")))
    (is (= :layer/episode (pipeline/layer-str->keyword "episode"))))
  (testing "returns nil for unknown layers"
    (is (nil? (pipeline/layer-str->keyword "unknown")))))

;; ---------------------------------------------------------------------------
;; build-mem-attrs: pure attribute construction
;; ---------------------------------------------------------------------------

(def gen-layer-kw
  (gen/one-of [(gen/return nil)
               (gen/elements [:layer/fact :layer/concept :layer/domain :layer/episode])]))

(def gen-mem-attrs-input
  (gen/let [content       (gen/not-empty gen/string-alphanumeric)
            layer-kw      gen-layer-kw
            importance    (gen/one-of [(gen/return nil)
                                       (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})])
            source        (gen/not-empty gen/string-alphanumeric)
            namespace     (gen/not-empty gen/string-alphanumeric)
            display-title (gen/one-of [(gen/return nil)
                                       (gen/not-empty gen/string-alphanumeric)])]
    {:content content :layer-kw layer-kw :importance importance
     :source source :namespace namespace :display-title display-title}))

(defspec build-mem-attrs-has-required-keys 100
  (prop/for-all [input gen-mem-attrs-input]
                (let [attrs (pipeline/build-mem-attrs input)]
                  (and (contains? attrs :memory/content)
                       (contains? attrs :memory/layer)
                       (contains? attrs :memory/importance)
                       (contains? attrs :memory/source)
                       (contains? attrs :memory/namespace)))))

(defspec build-mem-attrs-display-title-conditional 100
  (prop/for-all [input gen-mem-attrs-input]
                (let [attrs (pipeline/build-mem-attrs input)]
                  (if (:display-title input)
                    (contains? attrs :memory/display-title)
                    (not (contains? attrs :memory/display-title))))))

(defspec build-mem-attrs-importance-is-float 100
  (prop/for-all [input gen-mem-attrs-input]
                (float? (:memory/importance (pipeline/build-mem-attrs input)))))

(defspec build-mem-attrs-layer-defaults-to-fact 100
  (prop/for-all [input gen-mem-attrs-input]
                (let [attrs (pipeline/build-mem-attrs input)]
                  (if (:layer-kw input)
                    (= (:layer-kw input) (:memory/layer attrs))
                    (= :layer/fact (:memory/layer attrs))))))
