(ns memlayer.llm.completion
  "Domain functions for LLM-based memory operations.
   Uses ChatProvider protocol for transport — all prompt construction,
   message formatting, and response parsing lives here."
  (:require [memlayer.provider.llm :as llm-provider]
            [memlayer.json :as json]
            [jsonista.core :as j]
            [clojure.tools.logging :as log]
            [clojure.string :as str]))

(defn- parse-json-from-llm
  "Parse JSON from LLM response. With response_format json_object enabled,
   the response should always be valid JSON without markdown fences."
  [text]
  (j/read-value (str/trim text) json/mapper))

(defn parse-extraction-response
  "Parse an extraction response string into structured data.
   Useful for testing with canned responses."
  [text]
  (parse-json-from-llm text))

;; -- Extraction --

(defn extract-memories
  "Extract structured memories from content using an LLM.
   Returns {:result [memory-map ...] :usage usage-map}."
  [provider prompts content context]
  (log/debug "Extracting memories from content of length" (count content))
  (let [user-msg (if (seq context)
                   (str "Context (existing memories):\n" context
                        "\n\nNew content to extract memories from:\n" content)
                   (str "Extract memories from:\n" content))
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:extraction prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})
        parsed (parse-json-from-llm content)]
    {:result (if (vector? parsed) parsed (or (:memories parsed) [parsed]))
     :usage  usage}))

;; -- Batch Extraction --

(defn extract-memories-batch
  "Extract memories from multiple content items in a single LLM call.
   Items are presented in order so the LLM can resolve cross-references.
   Returns {:result [...] :usage {...}}."
  [provider prompts items context]
  (log/debug "Batch extracting memories from" (count items) "items")
  (let [items-text (->> items
                        (map-indexed (fn [i item]
                                       (str "[" (inc i) "] " (:content item))))
                        (str/join "\n"))
        user-msg (if (seq context)
                   (str "Context (existing memories):\n" context
                        "\n\nItems to extract memories from:\n" items-text)
                   (str "Items to extract memories from:\n" items-text))
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:batch-extraction prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})
        parsed (parse-json-from-llm content)]
    {:result (if (vector? parsed) parsed (or (:memories parsed) [parsed]))
     :usage  usage}))

;; -- Decision --

(defn- format-candidates-text
  "Format candidates with their memory IDs for the decision prompt."
  [candidates]
  (if (seq candidates)
    (str/join "\n" (map (fn [c]
                          (str "- [" (:memory-id c) "] "
                               (:content c)
                               " (similarity: " (:distance c) ")"))
                        candidates))
    "No similar memories found."))

(defn- format-subgraph-text
  "Format existing relationships between candidates and known types."
  [{:keys [edges known-types]}]
  (let [edges-text (when (seq edges)
                     (str "\n\nExisting relationships between these memories:\n"
                          (str/join "\n" (map (fn [e]
                                                (str "- [" (:relationship/source-id e)
                                                     "] --" (name (:relationship/type e))
                                                     "--> [" (:relationship/target-id e) "]"))
                                              edges))))
        types-text (when (seq known-types)
                     (str "\n\nRelationship types in use: "
                          (str/join ", " (map name known-types))))]
    (str (or edges-text "") (or types-text ""))))

(defn decide-action
  "Decide whether to CREATE, UPDATE, DELETE, or NOOP for a memory.
   Optionally receives subgraph context (edges between candidates + known types).
   Returns {:result decision-map :usage usage-map}."
  [provider prompts extracted-memory candidates & {:keys [subgraph]}]
  (log/debug "Deciding action for memory:" (:content extracted-memory))
  (let [candidates-text (format-candidates-text candidates)
        subgraph-text   (when subgraph (format-subgraph-text subgraph))
        user-msg (str "New memory:\n" (:content extracted-memory)
                      "\n\nExisting similar memories:\n" candidates-text
                      (or subgraph-text ""))
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:decision prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})
        parsed (parse-json-from-llm content)]
    {:result {:action           (:action parsed)
              :reasoning        (:reasoning parsed)
              :merged-content   (:merged-content parsed)
              :delete-target-id (:delete-target-id parsed)
              :relationships    (:relationships parsed)}
     :usage  usage}))

;; -- Reflect --

(defn categorize-facts
  "Categorize orphan facts into concept groups.
   Returns {:result groups :usage usage-map}."
  [provider prompts facts]
  (let [facts-text (str/join "\n" (map-indexed
                                   (fn [i f]
                                     (str i ". " (:memory/content f)))
                                   facts))
        user-msg (str "Categorize these facts into concept groups:\n" facts-text)
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))

(defn organize-facts
  "Assign orphan facts to existing concepts or propose new ones.
   Returns {:result parsed-json :usage usage-map}."
  [provider prompts facts existing-concepts]
  (let [concepts-text (if (seq existing-concepts)
                        (str/join "\n" (map-indexed
                                        (fn [i c]
                                          (str i ". [" (:memory/id c) "] "
                                               (:memory/content c)))
                                        existing-concepts))
                        "No existing concepts.")
        facts-text (str/join "\n" (map-indexed
                                   (fn [i f]
                                     (str i ". " (:memory/content f)))
                                   facts))
        user-msg (str "Existing concepts:\n" concepts-text
                      "\n\nOrphan facts to organize:\n" facts-text)
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect-organize prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))

(defn summarize-memories
  "Generate a summary for a parent node from its children's content.
   Returns {:result {:summary string} :usage usage-map}."
  [provider prompts parent children]
  (let [parent-label (str (:memory/layer parent) " — " (:memory/content parent))
        children-text (str/join "\n" (map-indexed
                                      (fn [i c]
                                        (str (inc i) ". " (:memory/content c)))
                                      children))
        user-msg (str "Parent: " parent-label
                      "\n\nChildren:\n" children-text)
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect-summarize prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))

(defn discover-relationships
  "Discover relationships between memory pairs (any layer).
   Returns {:result {:relationships [...]} :usage usage-map}."
  [provider prompts pairs]
  (let [pairs-text (->> pairs
                        (map-indexed (fn [i [a b]]
                                       (str "Pair " i ":\n"
                                            "  A [" (:memory/id a) "] (" (name (:memory/layer a)) "): "
                                            (:memory/content a) "\n"
                                            "  B [" (:memory/id b) "] (" (name (:memory/layer b)) "): "
                                            (:memory/content b))))
                        (str/join "\n\n"))
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect-connect prompts)}
                                       {:role "user"   :content pairs-text}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))

(defn detect-contradictions
  "Ask LLM to identify contradictions between fact pairs.
   pairs: seq of [fact-a fact-b] tuples.
   Returns {:result {:contradictions [...]} :usage usage-map}."
  [provider prompts pairs]
  (let [pairs-text (str/join "\n" (map-indexed
                                   (fn [i [a b]]
                                     (str i ". \"" (:memory/content a)
                                          "\" vs \"" (:memory/content b) "\""))
                                   pairs))
        user-msg (str "Fact pairs to check for contradictions:\n" pairs-text)
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect-curate prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))

;; -- Recall Answer Generation --

(defn generate-answer
  "Generate a natural language answer from recalled memories and graph context.
   Returns {:result <string> :usage usage-map}."
  [provider prompts query memories graph]
  (log/debug "Generating answer for query:" query)
  (let [memories-text (str/join "\n" (map-indexed
                                      (fn [i m]
                                        (str (inc i) ". [" (:layer m) "] " (:content m)
                                             (when (seq (:related m))
                                               (str " (related: "
                                                    (str/join ", " (map :content (:related m)))
                                                    ")"))))
                                      memories))
        graph-text (when graph
                     (str (when (seq (:concepts graph))
                            (str "\n\nConcepts:\n"
                                 (str/join "\n" (map :content (:concepts graph)))))
                          (when (seq (:summaries graph))
                            (str "\n\nSummaries:\n"
                                 (str/join "\n" (map :content (:summaries graph)))))
                          (when (seq (:relationships graph))
                            (str "\n\nRelationships:\n"
                                 (str/join "\n" (map (fn [r]
                                                       (str (:type r) ": " (:description r)))
                                                     (:relationships graph)))))))
        user-msg (str "Query: " query
                      "\n\nRetrieved memories:\n" memories-text
                      (or graph-text ""))
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:recall prompts)}
                                       {:role "user"   :content user-msg}]
                                      {})]
    {:result content
     :usage  usage}))

(defn organize-concepts
  "Assign orphan concepts to existing domains or propose new ones.
   Returns {:result parsed-json :usage usage-map}."
  [provider prompts concepts existing-domains]
  (let [domains-text (if (seq existing-domains)
                       (str/join "\n" (map-indexed
                                       (fn [i d]
                                         (str i ". [" (:memory/id d) "] "
                                              (:memory/content d)))
                                       existing-domains))
                       "No existing domains.")
        concepts-text (str/join "\n" (map-indexed
                                      (fn [i c]
                                        (str i ". " (:memory/content c)))
                                      concepts))
        user-msg (str "Existing domains:\n" domains-text
                      "\n\nOrphan concepts to organize:\n" concepts-text)
        {:keys [content usage]}
        (llm-provider/chat-completion provider
                                      [{:role "system" :content (:reflect-organize-domains prompts)}
                                       {:role "user"   :content user-msg}]
                                      {:response-format {:type "json_object"}})]
    {:result (parse-json-from-llm content)
     :usage  usage}))
