(ns memlayer.provider.openai
  "OpenAI embedding transport. Implements EmbeddingProvider protocol."
  (:require [clojure.tools.logging :as log]
            [memlayer.provider.llm :as llm-provider]
            [memlayer.provider.http :as provider-http]))

(defn- request-embedding
  "Call OpenAI embeddings API with retry. Returns the raw response body parsed as map."
  [{:keys [api-key base-url embedding-model http-client]} input]
  (provider-http/provider-request!
   {:url         (str base-url "/embeddings")
    :api-key     api-key
    :http-client http-client
    :body        {:model embedding-model :input input}
    :label       "OpenAI embedding"}))

(defn- extract-usage [response embedding-model]
  (let [u (:usage response)]
    {:prompt-tokens     (or (:prompt-tokens u) 0)
     :completion-tokens 0
     :total-tokens      (or (:total-tokens u) 0)
     :model             embedding-model
     :provider          "openai"}))

(defrecord OpenAIEmbeddingProvider [api-key base-url embedding-model http-client]
  llm-provider/EmbeddingProvider
  (embed [this text]
    (log/debug "Generating embedding for text of length" (count text))
    (let [response  (request-embedding this text)
          embedding (-> response :data first :embedding)]
      {:embedding (float-array embedding)
       :usage     (extract-usage response embedding-model)}))
  (embed-batch [this texts]
    (log/debug "Generating embeddings for" (count texts) "texts")
    (let [response (request-embedding this texts)]
      {:embeddings (->> (:data response)
                        (sort-by :index)
                        (mapv (comp float-array :embedding)))
       :usage      (extract-usage response embedding-model)})))

(defn create-client
  "Create an OpenAIEmbeddingProvider from config."
  [{:keys [api-key base-url embedding-model]}]
  (->OpenAIEmbeddingProvider api-key base-url embedding-model
                             (provider-http/build-client)))

(defn parse-embedding-response
  "Parse an embedding response body into a vector of float-arrays.
   Useful for testing with canned responses."
  [response-body]
  (->> (:data response-body)
       (sort-by :index)
       (mapv (comp float-array :embedding))))
