(ns memlayer.operations.chunk
  "Splits text content into token-sized chunks at natural boundaries.
   Target: ~2000 tokens per chunk (estimated at 4 chars/token = 8000 chars).

   Boundary search order (within last 20% of char limit):
     1. \". \" (sentence boundary)
     2. \"\\n\" (newline)
     3. Hard split at char_limit (fallback)"
  (:require [memlayer.tuning :as tuning]))

(def ^:const char-limit tuning/chunk-char-limit)
(def ^:const search-zone-ratio tuning/chunk-search-zone-ratio)

(defn- find-split-point
  "Find the best split point within the search zone at the end of the chunk.
   Returns the index to split at (exclusive)."
  [^String text ^long end]
  (let [zone-start (long (Math/floor (* end (- 1.0 search-zone-ratio))))
        search     (.substring text zone-start end)]
    ;; Try sentence boundary first
    (if-let [idx (let [i (.lastIndexOf search ". ")]
                   (when (>= i 0) (+ zone-start i 2)))]
      idx
      ;; Try newline
      (if-let [idx (let [i (.lastIndexOf search "\n")]
                     (when (>= i 0) (+ zone-start i 1)))]
        idx
        ;; Hard split
        end))))

(defn make-stream-chunker
  "Create a stateful streaming chunker for incremental text ingestion.
   Returns a map with:
     :feed!  - (fn [text]) appends text to buffer, returns vector of emitted chunks
              (may be empty if buffer has not yet reached char-limit)
     :flush! - (fn []) returns remaining buffer content as a final chunk, or nil"
  []
  (let [buf (StringBuilder.)]
    {:feed!  (fn [^String text]
               (.append buf text)
               (loop [chunks []]
                 (if (>= (.length buf) char-limit)
                   (let [s     (.toString buf)
                         split (find-split-point s (min (.length buf) char-limit))
                         chunk (.substring s 0 split)]
                     (.delete buf 0 split)
                     (recur (conj chunks chunk)))
                   chunks)))
     :flush! (fn []
               (let [s (.toString buf)]
                 (.setLength buf 0)
                 (when (pos? (.length s)) s)))}))

(defn chunk-text
  "Split text into chunks of approximately char-limit characters.
   Returns a vector of chunk strings."
  [^String text]
  (let [len (.length text)]
    (if (<= len char-limit)
      [text]
      (loop [start 0
             chunks []]
        (if (>= start len)
          chunks
          (let [remaining (- len start)
                end       (+ start (min remaining char-limit))]
            (if (<= remaining char-limit)
              (conj chunks (.substring text start))
              (let [split-at (find-split-point text end)]
                (recur split-at
                       (conj chunks (.substring text start split-at)))))))))))
