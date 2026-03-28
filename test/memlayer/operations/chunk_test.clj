(ns memlayer.operations.chunk-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.string :as str]
            [memlayer.operations.chunk :as chunk]))

(deftest chunk-text-single-chunk
  (testing "text shorter than char-limit returns single chunk"
    (let [text "Hello, world."]
      (is (= [text] (chunk/chunk-text text))))))

(deftest chunk-text-splits-at-boundaries
  (testing "long text splits at sentence or newline boundaries"
    (let [text (apply str (repeat 1200 "Hello. "))  ;; ~8400 chars
          chunks (chunk/chunk-text text)]
      (is (> (count chunks) 1))
      (is (= text (apply str chunks))))))

(deftest stream-chunker-small-feed
  (testing "feeding text smaller than char-limit emits nothing; flush returns it"
    (let [{:keys [feed! flush!]} (chunk/make-stream-chunker)
          emitted (feed! "small text")]
      (is (= [] emitted))
      (is (= "small text" (flush!))))))

(deftest stream-chunker-flush-empty
  (testing "flush on empty buffer returns nil"
    (let [{:keys [flush!]} (chunk/make-stream-chunker)]
      (is (nil? (flush!))))))

(deftest stream-chunker-multiple-small-feeds
  (testing "multiple small feeds exceeding char-limit emit chunks"
    (let [{:keys [feed! flush!]} (chunk/make-stream-chunker)
          ;; Feed 10 x 1000 chars = 10000 chars total (> 8000 char-limit)
          segment (apply str (repeat 142 "Hello. "))  ;; 142 * 7 = 994 chars
          all-emitted (into [] (mapcat feed!) (repeat 10 segment))
          remainder (flush!)]
      (is (pos? (count all-emitted)) "should emit at least one chunk")
      ;; All text is preserved: emitted chunks + remainder = original
      (let [original (apply str (repeat 10 segment))
            reassembled (str (apply str all-emitted) (or remainder ""))]
        (is (= original reassembled))))))

(deftest stream-chunker-large-feed
  (testing "single large feed emits multiple chunks"
    (let [{:keys [feed! flush!]} (chunk/make-stream-chunker)
          ;; ~64KB of text
          text (apply str (repeat 9200 "Hello. "))  ;; 9200 * 7 = 64400 chars
          emitted (feed! text)
          remainder (flush!)]
      (is (>= (count emitted) 7) "64KB should produce ~8 chunks of ~8KB")
      (let [reassembled (str (apply str emitted) (or remainder ""))]
        (is (= text reassembled))))))

(deftest stream-chunker-boundary-consistency
  (testing "streaming chunker produces same results as chunk-text for complete input"
    (let [text (apply str (repeat 3000 "The quick brown fox. "))  ;; ~60KB
          ;; chunk-text result
          batch-chunks (chunk/chunk-text text)
          ;; stream-chunker: feed all at once + flush
          {:keys [feed! flush!]} (chunk/make-stream-chunker)
          emitted (feed! text)
          remainder (flush!)
          stream-chunks (cond-> emitted remainder (conj remainder))]
      ;; Both should preserve full text
      (is (= text (apply str batch-chunks)))
      (is (= text (apply str stream-chunks)))
      ;; Chunk counts should be equal (same algorithm, same input)
      (is (= (count batch-chunks) (count stream-chunks))))))

(deftest stream-chunker-splits-at-natural-boundaries
  (testing "chunks split at sentence boundaries, not mid-word"
    (let [{:keys [feed! flush!]} (chunk/make-stream-chunker)
          ;; Create text with clear sentence boundaries
          sentences (mapv #(str "Sentence number " % ". ") (range 1 1500))
          text (apply str sentences)
          emitted (feed! text)
          remainder (flush!)
          all-chunks (cond-> emitted remainder (conj remainder))]
      ;; Each emitted chunk (except possibly the last) should end with ". "
      (doseq [c (butlast all-chunks)]
        (is (re-find #"\.\s*$" c)
            (str "Chunk should end at sentence boundary, but ends with: "
                 (subs c (max 0 (- (count c) 20)))))))))

;; ---------------------------------------------------------------------------
;; Generative / property-based tests
;; ---------------------------------------------------------------------------

(def gen-text-with-sentences
  "Text with sentence boundaries ('. ') and newlines interspersed."
  (gen/let [segments (gen/vector
                      (gen/tuple (gen/not-empty gen/string-alphanumeric)
                                 (gen/elements [". " "\n" " "]))
                      1 80)]
    (apply str (map (fn [[w sep]] (str w sep)) segments))))

(def gen-large-text
  "Text with boundaries, guaranteed to exceed char-limit."
  (gen/fmap (fn [n] (apply str (repeat n "Word. ")))
            (gen/choose 1500 5000)))

(def gen-large-text-no-boundaries
  "Long text with no sentence boundaries or newlines — forces hard-split fallback."
  (gen/fmap (fn [n] (apply str (repeat n "x")))
            (gen/choose 9000 20000)))

(def gen-chunk-input
  (gen/frequency [[1 gen/string-alphanumeric]
                  [3 gen-text-with-sentences]
                  [3 gen-large-text]
                  [1 gen-large-text-no-boundaries]]))

(defspec chunk-text-reassembly 100
  (prop/for-all [text gen-chunk-input]
                (= text (apply str (chunk/chunk-text text)))))

(defspec chunk-text-size-bounded 100
  (prop/for-all [text gen-chunk-input]
                (every? #(<= (count %) chunk/char-limit)
                        (chunk/chunk-text text))))

(defspec chunk-text-short-input-single-chunk 100
  (prop/for-all [text (gen/such-that #(<= (count %) chunk/char-limit)
                                     gen/string-alphanumeric
                                     100)]
                (= 1 (count (chunk/chunk-text text)))))

(defspec chunk-text-splits-at-natural-boundaries 100
  (prop/for-all [text gen-large-text]
                (let [chunks (chunk/chunk-text text)]
                  (every? (fn [c]
                            (or (str/ends-with? c ". ")
                                (str/ends-with? c "\n")
                                (= (count c) chunk/char-limit)))
                          (butlast chunks)))))
