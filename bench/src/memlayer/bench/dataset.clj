(ns memlayer.bench.dataset
  "LongMemEval dataset loader. Reads JSON from bench/data/ and returns a
   seq of benchmark items ready for the runner.

   LongMemEval (ICLR 2025): https://github.com/xiaowu0162/LongMemEval
   Dataset: https://huggingface.co/datasets/xiaowu0162/longmemeval-cleaned"
  (:require [jsonista.core :as j]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private mapper
  (j/object-mapper {:decode-key-fn (fn [s] (-> s (str/replace "_" "-") keyword))}))

(def dataset-files
  {"oracle" "longmemeval_oracle.json"
   "s"      "longmemeval_s_cleaned.json"
   "m"      "longmemeval_m_cleaned.json"})

(def question-type->ability
  "Map LongMemEval question_type to the 5 memory abilities."
  {"single-session-user"       :information-extraction
   "single-session-assistant"  :information-extraction
   "single-session-preference" :information-extraction
   "multi-session"             :multi-session-reasoning
   "temporal-reasoning"        :temporal-reasoning
   "knowledge-update"          :knowledge-updates})

(defn abstention?
  "True if this question tests abstention (the model should refuse to answer)."
  [question-id]
  (str/ends-with? (str question-id) "_abs"))

(defn format-session
  "Format a single session (list of turns) into a text block for ingestion."
  [turns]
  (->> turns
       (map (fn [{:keys [role content]}]
              (str role ": " content)))
       (str/join "\n")))

(defn load-dataset
  "Load a LongMemEval dataset variant. Returns a seq of maps:
   {:question-id str
    :question-type str
    :ability keyword
    :question str
    :answer str
    :question-date str
    :abstention? bool
    :sessions [{:id str :date str :text str}]}"
  [variant]
  (let [filename (get dataset-files variant)
        _ (when-not filename
            (throw (ex-info (str "Unknown dataset variant: " variant
                                 ". Choose from: " (str/join ", " (keys dataset-files)))
                            {:variant variant})))
        path (str "bench/data/" filename)
        file (io/file path)
        _ (when-not (.exists file)
            (throw (ex-info (str "Dataset not found at " path
                                 ". Run: bb bench-download")
                            {:path path})))
        items (j/read-value file mapper)]
    (mapv (fn [item]
            (let [sessions (mapv (fn [turns date id]
                                   {:id   id
                                    :date date
                                    :text (format-session turns)})
                                 (:haystack-sessions item)
                                 (:haystack-dates item)
                                 (:haystack-session-ids item))]
              {:question-id   (:question-id item)
               :question-type (:question-type item)
               :ability       (get question-type->ability (:question-type item) :unknown)
               :question      (:question item)
               :answer        (str (:answer item))
               :question-date (:question-date item)
               :abstention?   (abstention? (:question-id item))
               :sessions      sessions}))
          items)))
