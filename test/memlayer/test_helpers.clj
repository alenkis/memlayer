(ns memlayer.test-helpers
  "Shared test fixtures: in-memory datahike, mock LLM providers, flow helpers."
  (:require [datahike.api :as d]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.usage :as usage]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.api.retain :as retain]
            [memlayer.api.recall :as recall]
            [memlayer.api.forget :as forget]
            [memlayer.api.evict :as evict]
            [memlayer.api.ingest :as ingest]
            [memlayer.api.batch-retain :as batch-retain]
            [memlayer.api.reflect :as reflect]
            [memlayer.api.ws-ingest :as ws-ingest]
            [memlayer.api.admin :as admin]
            [memlayer.api.namespaces :as namespaces]
            [memlayer.api.memories :as memories]
            [memlayer.api.stats :as stats]
            [memlayer.api.dashboard :as dashboard]
            [memlayer.mcp.http :as mcp-http]
            [jsonista.core :as j]))

;; -- Datahike fixtures --

(defn fresh-datahike-cfg []
  {:store {:backend :memory
           :id (java.util.UUID/randomUUID)}
   :schema-flexibility :write})

(defn with-datahike
  "Test fixture: creates an in-memory datahike DB with schema, yields store, tears down."
  [f]
  (let [cfg (fresh-datahike-cfg)]
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (dh/transact-schema! conn)
      (usage/transact-schema! conn)
      (usage/seed-default-pricing! conn)
      (try
        (f (dh/->DatahikeEntityStore conn))
        (finally
          (d/release conn)
          (d/delete-database cfg))))))

;; -- Mock LLM providers --

(defn- deterministic-embedding
  "Hash-based deterministic embedding for testing."
  [text dim]
  (let [h   (hash text)
        rng (java.util.Random. h)]
    (float-array (repeatedly dim #(.nextFloat rng)))))

(defn mock-embedding-provider
  "Returns a mock EmbeddingProvider that returns deterministic embeddings."
  ([] (mock-embedding-provider {}))
  ([{:keys [dim] :or {dim 1536}}]
   (reify llm-provider/EmbeddingProvider
     (embed [_ text]
       {:embedding (deterministic-embedding text dim)
        :usage     {:prompt-tokens 10 :completion-tokens 0 :total-tokens 10
                    :model "test-embed-model" :provider "openai"}})
     (embed-batch [_ texts]
       {:embeddings (mapv #(deterministic-embedding % dim) texts)
        :usage      {:prompt-tokens (* 10 (count texts)) :completion-tokens 0
                     :total-tokens (* 10 (count texts))
                     :model "test-embed-model" :provider "openai"}}))))

(defn mock-chat-provider
  "Returns a mock ChatProvider that returns canned JSON responses.
   response-fn: (fn [messages opts] -> data-map) — the data map will be JSON-serialized
   as the chat completion content."
  [response-fn]
  (reify llm-provider/ChatProvider
    (chat-completion [_ messages opts]
      {:content (j/write-value-as-string (response-fn messages opts))
       :usage   {:prompt-tokens 100 :completion-tokens 50 :total-tokens 150
                 :model "test-chat-model" :provider "groq"}})))

(def ^:private default-extract-result
  [{:content "User prefers dark mode"
    :layer "fact"}])

(def ^:private default-decision-result
  {:action "CREATE"
   :reasoning "New information not found in existing memories"})

;; Stub prompts for tests — operations need them but content is irrelevant with mocks
(def mock-prompts
  {:extraction               "test-extraction-prompt"
   :batch-extraction         "test-batch-extraction-prompt"
   :decision                 "test-decision-prompt"
   :resolution               "test-resolution-prompt"
   :reflect                  "test-reflect-prompt"
   :reflect-organize         "test-reflect-organize-prompt"
   :reflect-organize-domains "test-reflect-organize-domains-prompt"
   :reflect-summarize        "test-reflect-summarize-prompt"
   :reflect-connect          "test-reflect-connect-prompt"
   :reflect-curate           "test-reflect-curate-prompt"
   :recall                   "test-recall-prompt"})

(defn mock-flow-provider
  "Mock ChatProvider for the retention flow (extract + decide + generate-answer).
   Distinguishes calls by system prompt content.
   Works correctly across multiple flow submits (no shared call counter).
   Accepts :extract-result, :decision-result, :decision-fn, or :answer-result."
  ([] (mock-flow-provider {}))
  ([{:keys [extract-result decision-result decision-fn answer-result]}]
   (reify llm-provider/ChatProvider
     (chat-completion [_ messages _opts]
       (let [system-content (:content (first messages))
             ;; Answer generation returns raw text; other calls return JSON-serialized maps
             [content raw?]
             (cond
               (= system-content (:recall mock-prompts))
               [(or answer-result "Generated answer based on recalled memories.") true]

               (= system-content (:batch-extraction mock-prompts))
               [{:memories (or extract-result default-extract-result)} false]

               :else
               [(if decision-fn
                  (decision-fn messages)
                  (or decision-result default-decision-result)) false])]
         {:content (if raw? content (j/write-value-as-string content))
          :usage   {:prompt-tokens 100 :completion-tokens 50 :total-tokens 150
                    :model "test-chat-model" :provider "groq"}})))))

(defn mock-categorize-provider
  "Mock ChatProvider for reflect (categorize-facts)."
  ([] (mock-categorize-provider {}))
  ([{:keys [categorize-result]}]
   (mock-chat-provider
    (fn [_msgs _opts]
      (or categorize-result
          {:groups [{:concept-name "Test Concept"
                     :concept-content "A test concept"
                     :fact-indices [0]}]})))))

;; -- Retention flow test helpers --

(defn start-test-flow!
  "Start a retention flow for testing with minimal config.
   Needs at least 5 io-threads (one per flow process, all :io workload).
   Returns the flow handle map (pass to retention-flow/submit! and stop-test-flow!)."
  [deps]
  (retention-flow/start-standalone! deps {:flow {:io-threads 8
                                                 :submit-timeout-ms 30000}}))

(defn stop-test-flow!
  "Stop a test retention flow and clean up resources."
  [flow]
  (retention-flow/stop-standalone! flow))

(defn with-test-flow
  "Start a test flow from deps, call f with the flow, then stop it."
  [deps f]
  (let [flow (start-test-flow! deps)]
    (try
      (f flow)
      (finally
        (stop-test-flow! flow)))))

;; -- Test handler builder --

(defn make-test-handlers
  "Build all handler fns from test deps + flow, mimicking Integrant wiring.
   Returns a map suitable for passing to router/create-router."
  [deps flow]
  {:retain       (retain/handler flow)
   :recall       (recall/handler deps)
   :forget       (forget/handler deps)
   :evict        (evict/handler deps)
   :ingest       (ingest/handler flow)
   :batch-retain (batch-retain/handler flow deps)
   :reflect      (reflect/handler deps)
   :ws-ingest    (ws-ingest/handler flow deps)
   :admin        {:reset (admin/reset-handler deps)}
   :namespaces   {:list (namespaces/list-handler deps)}
   :memories     {:list          (memories/list-handler deps)
                  :get           (memories/get-handler deps)
                  :delete        (memories/delete-handler deps)
                  :children      (memories/children-handler deps)
                  :relationships (memories/relationships-handler deps)
                  :history       (memories/memory-history-handler deps)}
   :stats        {:memory-stats (stats/memory-stats-handler deps)
                  :consistency  (stats/consistency-handler deps)}
   :dashboard    {:usage            (dashboard/usage-handler deps)
                  :list-namespaces  (dashboard/list-namespaces-handler deps)
                  :create-namespace (dashboard/create-namespace-handler deps)
                  :rename-namespace (dashboard/rename-namespace-handler deps)
                  :delete-namespace (dashboard/delete-namespace-handler deps)}
   :mcp            (mcp-http/create-handler flow deps)
   :retention-flow flow})

;; -- Relationship helpers --

(defn insert-relationship!
  "Insert a relationship between two memories. Delegates to production fn."
  [conn params]
  (dh/insert-relationship! conn (select-keys params [:source-id :target-id :type])))
