(ns memlayer.domain.memory
  "Malli schemas for the memory domain model and API contracts."
  (:require [malli.core :as m]
            [memlayer.domain.layer :as layer]))

;; -- Domain schemas --

(def Layer
  (into [:enum] layer/layers))

(def Memory
  [:map
   [:memory/id uuid?]
   [:memory/content :string]
   [:memory/layer Layer]
   [:memory/importance [:double {:min 0.0 :max 1.0}]]
   [:memory/source :string]
   [:memory/namespace {:optional true} :string]
   [:memory/parent-id {:optional true} uuid?]])

(def Relationship
  [:map
   [:relationship/id uuid?]
   [:relationship/source-id uuid?]
   [:relationship/target-id uuid?]
   [:relationship/type :keyword]])

;; -- API schemas --

(def RetainRequest
  [:map
   [:content :string]
   [:source :string]
   [:namespace {:optional true} :string]])

(def Decision
  [:map
   [:type [:enum "CREATE" "UPDATE" "FORGET" "DELETE" "NOOP"]]
   [:memory-id {:optional true} uuid?]
   [:content :string]])

(def RetainResponse
  [:map
   [:memory-ids [:vector uuid?]]
   [:decisions [:vector Decision]]])

;; -- Extraction schema (LLM output) --

(def ExtractedMemory
  [:map
   [:content :string]
   [:layer [:enum "domain" "concept" "fact" "episode"]]
   [:importance [:double {:min 0.0 :max 1.0}]]])

(def ExtractionResult
  [:vector ExtractedMemory])

;; -- Decision schema (LLM output) --

(def DecisionResult
  [:map
   [:action [:enum "CREATE" "UPDATE" "FORGET" "DELETE" "NOOP"]]
   [:reasoning :string]
   [:merged-content {:optional true} :string]
   [:delete-target-id {:optional true} :string]
   [:relationships {:optional true}
    [:vector [:map
              [:target-id :string]
              [:type :string]]]]])

;; -- Recall API schemas --

(def RecallRequest
  [:map
   [:query :string]
   [:namespace {:optional true} :string]
   [:limit {:optional true} [:int {:min 1 :max 100}]]
   [:threshold {:optional true} [:double {:min 0.0 :max 1.0}]]])

(def RecallMemory
  [:map
   [:memory-id :string]
   [:content :string]
   [:layer :string]
   [:importance :double]
   [:source :string]
   [:namespace {:optional true} :string]
   [:distance :double]])

(def RecallResponse
  [:map
   [:query :string]
   [:memories [:vector RecallMemory]]
   [:count :int]])

;; -- Validation helpers --

(defn validate [schema value]
  (m/validate schema value))

(defn explain [schema value]
  (m/explain schema value))
