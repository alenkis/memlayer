(ns memlayer.bench.report
  "Generate benchmark results as JSON files and a markdown summary."
  (:require [jsonista.core :as j]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.time LocalDateTime]
           [java.time.format DateTimeFormatter]))

(def ^:private mapper
  (j/object-mapper {:encode-key-fn name
                    :pretty        true}))

(def run-dir-name
  (delay
    (let [fmt (DateTimeFormatter/ofPattern "yyyy-MM-dd_HHmmss")]
      (.format (LocalDateTime/now) fmt))))

(defn- results-dir []
  (let [dir (io/file "bench" "results" @run-dir-name)]
    (.mkdirs dir)
    dir))

;; ---------------------------------------------------------------------------
;; JSON output
;; ---------------------------------------------------------------------------

(defn write-raw-results!
  "Write per-question raw results for a system."
  [system-name items verdicts recall-results]
  (let [dir  (results-dir)
        data (mapv (fn [item verdict recall]
                     {:question-id   (:question-id item)
                      :question-type (:question-type item)
                      :ability       (name (:ability item))
                      :question      (:question item)
                      :expected      (:answer item)
                      :answer        (:answer recall)
                      :correct       (:correct? verdict)
                      :latency-ms    (:latency-ms recall)
                      :usage         (:usage recall)})
                   items verdicts recall-results)
        file (io/file dir (str system-name "-raw.json"))]
    (spit file (j/write-value-as-string data mapper))
    (.getPath file)))

(defn write-metrics!
  "Write aggregated metrics for a system."
  [system-name metrics]
  (let [dir  (results-dir)
        file (io/file dir (str system-name "-metrics.json"))]
    (spit file (j/write-value-as-string metrics mapper))
    (.getPath file)))

;; ---------------------------------------------------------------------------
;; Markdown summary
;; ---------------------------------------------------------------------------

(defn- fmt-pct [n] (format "%.1f%%" (* 100.0 (double n))))
(defn- fmt-ms [n] (format "%,d ms" (long n)))
(defn- fmt-tokens [n] (format "%,d" (long n)))
(defn- fmt-memscore [n] (format "%.6f" (double n)))

(defn- comparison-table [all-metrics]
  (let [entries (sort-by key all-metrics)
        systems (mapv key entries)
        metrics (mapv val entries)
        header  (str "| Metric | " (str/join " | " (map name systems)) " |")
        sep     (str "|--------|" (str/join "|" (repeat (count systems) "-------")) "|")]
    (str/join
     "\n"
     [header sep
      (str "| Accuracy | "
           (str/join " | " (map #(fmt-pct (get-in % [:accuracy :overall])) metrics))
           " |")
      (str "| Recall p50 | "
           (str/join " | " (map #(fmt-ms (get-in % [:latency :recall :p50])) metrics))
           " |")
      (str "| Recall p95 | "
           (str/join " | " (map #(fmt-ms (get-in % [:latency :recall :p95])) metrics))
           " |")
      (str "| Retain p50 | "
           (str/join " | " (map #(fmt-ms (get-in % [:latency :retain :p50])) metrics))
           " |")
      (str "| Ingest tokens | "
           (str/join " | " (map #(fmt-tokens (get-in % [:tokens :retain :total-tokens])) metrics))
           " |")
      (str "| Recall tokens | "
           (str/join " | " (map #(fmt-tokens (get-in % [:tokens :recall :total-tokens])) metrics))
           " |")
      (str "| MemScore | "
           (str/join " | " (map #(fmt-memscore (:memscore %)) metrics))
           " |")])))

(defn- ability-table [all-metrics]
  (let [entries    (sort-by key all-metrics)
        systems    (mapv key entries)
        metrics    (mapv val entries)
        abilities  (-> metrics first :accuracy :by-ability keys sort)
        header     (str "| Ability | "
                        (str/join " | " (map name systems)) " |")
        sep        (str "|---------|"
                        (str/join "|" (repeat (count systems) "-------")) "|")]
    (str/join
     "\n"
     (concat
      [header sep]
      (for [ability abilities]
        (str "| " (name ability) " | "
             (str/join " | "
                       (map (fn [m]
                              (let [a (get-in m [:accuracy :by-ability ability])]
                                (str (fmt-pct (:accuracy a)) " (" (:correct a) "/" (:total a) ")")))
                            metrics))
             " |"))))))

(defn write-summary!
  "Write a markdown summary comparing all systems."
  [all-metrics dataset-variant]
  (let [dir  (results-dir)
        file (io/file dir "summary.md")
        md   (str/join
              "\n\n"
              [(str "# LongMemEval Benchmark Results\n\n"
                    "Dataset: `" dataset-variant "`  \n"
                    "Date: " @run-dir-name "  \n"
                    "Questions: " (:total-questions (first (vals all-metrics))))
               "## Overall Comparison"
               (comparison-table all-metrics)
               "## Accuracy by Memory Ability"
               (ability-table all-metrics)
               (str "## Methodology\n\n"
                    "- Judge: GPT-4o (gpt-4o-2024-08-06), temperature 0\n"
                    "- memlayer: recall with expand-graph + reflect before query\n"
                    "- hindsight: reflect endpoint (retrieval + LLM reasoning)\n"
                    "- MemScore = accuracy% / (avg_recall_latency_ms * avg_context_tokens)")])]
    (spit file md)
    (.getPath file)))

;; ---------------------------------------------------------------------------
;; Console output
;; ---------------------------------------------------------------------------

(defn print-summary
  "Print a concise summary table to stdout."
  [all-metrics]
  (println "\n=== LongMemEval Results ===\n")
  (doseq [[system metrics] all-metrics]
    (println (str "  " (name system) ":"
                  "  accuracy=" (fmt-pct (get-in metrics [:accuracy :overall]))
                  "  recall-p50=" (fmt-ms (get-in metrics [:latency :recall :p50]))
                  "  memscore=" (fmt-memscore (:memscore metrics)))))
  (println))
