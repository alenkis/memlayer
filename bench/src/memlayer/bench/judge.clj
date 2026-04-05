(ns memlayer.bench.judge
  "LLM-as-a-Judge evaluation using GPT-4o. Implements the 5 prompt templates
   from LongMemEval's evaluate_qa.py for reproducible scoring."
  (:require [hato.client :as hc]
            [jsonista.core :as j]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

(def ^:private mapper (j/object-mapper {:decode-key-fn keyword}))
(def ^:private judge-model "gpt-4o-2024-08-06")

;; ---------------------------------------------------------------------------
;; Prompt templates (from LongMemEval evaluate_qa.py)
;; ---------------------------------------------------------------------------

(defn- standard-prompt [question answer hypothesis]
  (str "I am going to provide you with a question and the ground truth answer "
       "to that question, as well as a response from an LLM. Please answer yes "
       "if the response contains information that answers the question correctly, "
       "and no otherwise. If the response only contains a subset of the information "
       "required, answer no.\n\n"
       "Question: " question "\n"
       "Ground truth answer: " answer "\n"
       "Response: " hypothesis "\n\n"
       "Answer yes or no only."))

(defn- temporal-prompt [question answer hypothesis]
  (str "I am going to provide you with a question and the ground truth answer "
       "to that question, as well as a response from an LLM. Please answer yes "
       "if the response contains information that answers the question correctly, "
       "and no otherwise. If the response only contains a subset of the information "
       "required, answer no. Please do not penalize off-by-one errors for the "
       "number of days.\n\n"
       "Question: " question "\n"
       "Ground truth answer: " answer "\n"
       "Response: " hypothesis "\n\n"
       "Answer yes or no only."))

(defn- knowledge-update-prompt [question answer hypothesis]
  (str "I am going to provide you with a question and the ground truth answer "
       "to that question, as well as a response from an LLM. Please answer yes "
       "if the response contains information that answers the question correctly, "
       "and no otherwise. If the response only contains a subset of the information "
       "required, answer no. If the response contains some previous information "
       "along with an updated answer, the response should be considered correct "
       "as long as the updated answer is the required answer.\n\n"
       "Question: " question "\n"
       "Ground truth answer: " answer "\n"
       "Response: " hypothesis "\n\n"
       "Answer yes or no only."))

(defn- preference-prompt [question answer hypothesis]
  (str "I am going to provide you with a question and the desired response "
       "to that question, as well as a response from an LLM. Please answer yes "
       "if the response satisfies the desired response, and no otherwise. The "
       "response should be considered correct as long as it recalls and utilizes "
       "the user's personal information correctly.\n\n"
       "Question: " question "\n"
       "Desired response: " answer "\n"
       "Response: " hypothesis "\n\n"
       "Answer yes or no only."))

(defn- abstention-prompt [question _answer hypothesis]
  (str "I am going to provide you with a question and a response from an LLM. "
       "Please answer yes if the model correctly identifies the question as "
       "unanswerable, and no otherwise.\n\n"
       "Question: " question "\n"
       "Response: " hypothesis "\n\n"
       "Answer yes or no only."))

(defn- build-prompt
  "Select the right prompt template based on question type and abstention status."
  [{:keys [question answer question-type abstention?]} hypothesis]
  (if abstention?
    (abstention-prompt question answer hypothesis)
    (case question-type
      "temporal-reasoning"        (temporal-prompt question answer hypothesis)
      "knowledge-update"          (knowledge-update-prompt question answer hypothesis)
      "single-session-preference" (preference-prompt question answer hypothesis)
      (standard-prompt question answer hypothesis))))

;; ---------------------------------------------------------------------------
;; OpenAI API
;; ---------------------------------------------------------------------------

(defn- call-openai! [api-key prompt]
  (let [resp (hc/post "https://api.openai.com/v1/chat/completions"
                      {:as               :string
                       :content-type     :json
                       :headers          {"Authorization" (str "Bearer " api-key)}
                       :body             (j/write-value-as-string
                                          {:model       judge-model
                                           :messages    [{:role "user" :content prompt}]
                                           :temperature 0
                                           :max_tokens  10})
                       :throw-exceptions false
                       :timeout          30000})
        body (when (string? (:body resp))
               (try (j/read-value (:body resp) mapper)
                    (catch Exception _ nil)))]
    (if (= 200 (:status resp))
      (get-in body [:choices 0 :message :content] "")
      (do (log/warn "Judge API call failed" (:status resp) (:body resp))
          ""))))

(defn- parse-verdict [response]
  (str/includes? (str/lower-case (str response)) "yes"))

;; ---------------------------------------------------------------------------
;; Public API
;; ---------------------------------------------------------------------------

(defn judge-single!
  "Judge a single answer. Returns {:correct? bool :judge-response str}."
  [api-key item hypothesis]
  (let [prompt   (build-prompt item hypothesis)
        response (call-openai! api-key prompt)
        correct? (parse-verdict response)]
    {:correct?       correct?
     :judge-response response}))

(defn judge-all!
  "Judge all answers for a system. items and answers must be parallel seqs.
   Returns a seq of {:question-id str :correct? bool :judge-response str}."
  [api-key items answers]
  (log/info "Judging" (count items) "answers with" judge-model "...")
  ;; Sequential for now — can add pmap/thread-pool later if too slow
  (mapv (fn [item answer]
          (let [result (judge-single! api-key item answer)]
            (merge {:question-id (:question-id item)} result)))
        items answers))
