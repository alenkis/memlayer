(ns memlayer.provider.llm
  "Provider protocols for LLM chat completions and text embeddings.

   These define the transport contract — implementations handle HTTP calls,
   auth, retries. Domain logic (prompts, response parsing) lives in
   memlayer.llm.completion and callers.")

(defprotocol ChatProvider
  (chat-completion [provider messages opts]
    "Send messages to a chat completion API.
     opts: map with optional :response-format (e.g. {:type \"json_object\"}).
     Returns {:content <string>, :usage {:prompt_tokens N :completion_tokens N :total_tokens N}}."))

(defprotocol EmbeddingProvider
  (embed [provider text]
    "Generate an embedding for a single text string.
     Returns {:embedding <float-array>, :usage {:prompt_tokens N :completion_tokens N :total_tokens N}}.")
  (embed-batch [provider texts]
    "Generate embeddings for multiple texts.
     Returns {:embeddings [<float-array> ...], :usage {:prompt_tokens N :completion_tokens N :total_tokens N}}."))
