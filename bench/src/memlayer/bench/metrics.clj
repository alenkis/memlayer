(ns memlayer.bench.metrics
  "Compute benchmark metrics from raw results: accuracy, latency percentiles,
   token efficiency, and MemScore.")

;; ---------------------------------------------------------------------------
;; Percentiles
;; ---------------------------------------------------------------------------

(defn percentile
  "Compute the p-th percentile of a sorted numeric seq."
  [sorted-vals p]
  (if (empty? sorted-vals)
    0
    (let [n (count sorted-vals)
          k (* (/ p 100.0) (dec n))
          f (long (Math/floor k))
          c (long (Math/ceil k))]
      (if (= f c)
        (nth sorted-vals f)
        (let [d (- k f)]
          (+ (* (- 1 d) (nth sorted-vals f))
             (* d (nth sorted-vals c))))))))

(defn latency-stats
  "Compute p50, p95, p99 from a seq of latency-ms values."
  [latencies]
  (let [sorted (vec (sort latencies))]
    {:p50  (long (percentile sorted 50))
     :p95  (long (percentile sorted 95))
     :p99  (long (percentile sorted 99))
     :mean (if (empty? sorted) 0 (long (/ (reduce + sorted) (count sorted))))}))

;; ---------------------------------------------------------------------------
;; Accuracy
;; ---------------------------------------------------------------------------

(defn accuracy
  "Compute accuracy from a seq of {:correct? bool} maps."
  [verdicts]
  (if (empty? verdicts)
    0.0
    (double (/ (count (filter :correct? verdicts))
               (count verdicts)))))

(defn accuracy-by-ability
  "Group verdicts by ability and compute accuracy per group.
   items and verdicts must be parallel seqs."
  [items verdicts]
  (->> (map (fn [item verdict]
              (assoc verdict :ability (:ability item)
                     :abstention? (:abstention? item)))
            items verdicts)
       (group-by :ability)
       (map (fn [[ability vs]]
              [ability {:accuracy (accuracy vs)
                        :correct  (count (filter :correct? vs))
                        :total    (count vs)}]))
       (into (sorted-map))))

(defn accuracy-by-type
  "Group verdicts by question-type and compute accuracy per group."
  [items verdicts]
  (->> (map (fn [item verdict]
              (assoc verdict :question-type (:question-type item)))
            items verdicts)
       (group-by :question-type)
       (map (fn [[qt vs]]
              [qt {:accuracy (accuracy vs)
                   :correct  (count (filter :correct? vs))
                   :total    (count vs)}]))
       (into (sorted-map))))

;; ---------------------------------------------------------------------------
;; Token efficiency
;; ---------------------------------------------------------------------------

(defn sum-tokens
  "Sum token usage across a seq of {:usage {:total-tokens n}} maps."
  [results]
  (reduce (fn [acc r]
            (let [u (or (:usage r) {})]
              {:prompt-tokens     (+ (:prompt-tokens acc 0) (:prompt-tokens u 0))
               :completion-tokens (+ (:completion-tokens acc 0) (:completion-tokens u 0))
               :total-tokens      (+ (:total-tokens acc 0) (:total-tokens u 0))}))
          {:prompt-tokens 0 :completion-tokens 0 :total-tokens 0}
          results))

;; ---------------------------------------------------------------------------
;; Composite
;; ---------------------------------------------------------------------------

(defn memscore
  "MemScore = accuracy% / (avg_recall_latency_ms * avg_context_tokens).
   Higher is better. Returns 0 if denominator would be zero."
  [accuracy-pct avg-recall-latency-ms avg-context-tokens]
  (let [denom (* avg-recall-latency-ms avg-context-tokens)]
    (if (zero? denom)
      0.0
      (/ accuracy-pct denom))))

;; ---------------------------------------------------------------------------
;; Aggregate
;; ---------------------------------------------------------------------------

(defn compute-metrics
  "Compute all metrics from raw benchmark data.
   - items: the benchmark questions
   - verdicts: judge results (parallel to items)
   - retain-results: flat seq of all retain! results across all questions
   - recall-results: seq of recall! results (parallel to items)"
  [items verdicts retain-results recall-results]
  (let [overall-acc       (accuracy verdicts)
        acc-pct           (* 100.0 overall-acc)
        ;; Filter out errored results from latency/token stats
        ok-recalls        (remove :error recall-results)
        ok-retains        (remove :error retain-results)
        recall-lats       (mapv :latency-ms ok-recalls)
        retain-lats       (mapv :latency-ms ok-retains)
        recall-tokens     (sum-tokens ok-recalls)
        retain-tokens     (sum-tokens ok-retains)
        avg-recall-ms     (if (empty? recall-lats) 0
                              (/ (reduce + recall-lats) (count recall-lats)))
        avg-ctx-toks      (if (empty? ok-recalls) 0
                              (/ (:prompt-tokens recall-tokens) (count ok-recalls)))
        error-count       (count (filter :error recall-results))]
    {:accuracy        {:overall  overall-acc
                       :by-ability (accuracy-by-ability items verdicts)
                       :by-type   (accuracy-by-type items verdicts)}
     :latency         {:retain (latency-stats retain-lats)
                       :recall (latency-stats recall-lats)}
     :tokens          {:retain retain-tokens
                       :recall recall-tokens}
     :memscore        (memscore acc-pct avg-recall-ms avg-ctx-toks)
     :total-questions (count items)
     :errors          error-count}))
