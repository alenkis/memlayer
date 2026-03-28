(ns memlayer.mcp.tools
  "MCP tool definitions for memlayer operations.")

(def tool-definitions
  [{:name        "memlayer_retain"
    :description "Store information the user wants remembered. Extracts structured memories from content, deduplicates against existing knowledge, and stores new or updated memories."
    :inputSchema {:type       "object"
                  :properties {:content   {:type        "string"
                                           :description "The content to extract and store memories from"}
                               :source    {:type        "string"
                                           :description "Where this content came from (e.g., 'conversation', 'document')"}
                               :namespace {:type        "string"
                                           :description "Optional namespace to scope memories to"}}
                  :required   ["content" "source"]}}
   {:name        "memlayer_recall"
    :description "Search stored memories and generate an answer. Returns a natural language answer along with the underlying memories ranked by relevance. Supports temporal queries (as_of) and layer filtering."
    :inputSchema {:type       "object"
                  :properties {:query        {:type        "string"
                                              :description "Natural language query to search memories for"}
                               :namespace    {:type        "string"
                                              :description "Optional namespace to scope search to"}
                               :limit        {:type        "integer"
                                              :description "Max number of memories to return (default 10, max 100)"}
                               :as-of        {:type        "string"
                                              :description "ISO-8601 timestamp to query memories as they were at that point in time (e.g. \"2025-06-15T00:00:00Z\")"}
                               :layer        {:type        "string"
                                              :description "Filter by semantic layer: \"domain\" (broad areas), \"concept\" (topics), \"fact\" (specific assertions), or \"episode\" (time-bound events)"}
                               :expand-graph {:type        "boolean"
                                              :description "If true, include ancestor chain and related memories for each result. Enriches the generated answer with graph context."}}
                  :required   ["query"]}}
   {:name        "memlayer_forget"
    :description "Forget a specific memory. Removes it from current queries and search, but preserves it in history for audit purposes."
    :inputSchema {:type       "object"
                  :properties {:memory-id {:type        "string"
                                           :description "The memory UUID to forget"}}
                  :required   ["memory_id"]}}
   {:name        "memlayer_batch_retain"
    :description "Store multiple pieces of information in a single operation. Extracts structured memories from each item, deduplicates against existing knowledge, and stores new or updated memories. More efficient than calling retain multiple times."
    :inputSchema {:type       "object"
                  :properties {:namespace {:type        "string"
                                           :description "Namespace to scope memories to"}
                               :items     {:type        "array"
                                           :items       {:type       "object"
                                                         :properties {:content {:type        "string"
                                                                                :description "The content to extract and store memories from"}
                                                                      :source  {:type        "string"
                                                                                :description "Where this content came from (e.g., 'conversation', 'document')"}}
                                                         :required   ["content"]}
                                           :description "Array of items to retain"}}
                  :required   ["namespace" "items"]}}
   {:name        "memlayer_reflect"
    :description "Organize the knowledge graph: group orphan facts into concepts, concepts into domains, create summaries, discover relationships, and flag contradictions. Runs 4 phases: organize, summarize, connect, curate."
    :inputSchema {:type       "object"
                  :properties {:dry-run   {:type        "boolean"
                                           :description "If true, preview what would happen without making changes"}
                               :query     {:type        "string"
                                           :description "Optional query to filter which facts to reflect on"}
                               :threshold {:type        "number"
                                           :description "Similarity threshold for clustering (0-1, default 0.5)"}
                               :namespace {:type        "string"
                                           :description "Namespace to scope reflect to"}
                               :phases    {:type        "array"
                                           :items       {:type "string" :enum ["organize" "summarize" "connect" "curate"]}
                                           :description "Which phases to run (default: all four)"}}
                  :required   []}}])

(defn find-tool
  "Find a tool definition by name."
  [tool-name]
  (first (filter #(= tool-name (:name %)) tool-definitions)))
