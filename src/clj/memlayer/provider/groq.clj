(ns memlayer.provider.groq
  "Groq chat completion transport. Implements ChatProvider protocol."
  (:require [memlayer.provider.llm :as llm-provider]
            [memlayer.provider.http :as provider-http]))

(defrecord GroqChatProvider [api-key base-url model http-client]
  llm-provider/ChatProvider
  (chat-completion [_ messages opts]
    (let [response
          (provider-http/provider-request!
           {:url         (str base-url "/chat/completions")
            :api-key     api-key
            :http-client http-client
            :body        (cond-> {:model    model
                                  :messages messages}
                           (:response-format opts)
                           (assoc :response-format (:response-format opts)))
            :label       "Groq chat completion"})
          u (:usage response)]
      {:content (-> response :choices first :message :content)
       :usage   {:prompt-tokens     (or (:prompt-tokens u) 0)
                 :completion-tokens (or (:completion-tokens u) 0)
                 :total-tokens      (or (:total-tokens u) 0)
                 :model             model
                 :provider          "groq"}})))

(defn create-client
  "Create a GroqChatProvider from config."
  [{:keys [api-key base-url model]}]
  (->GroqChatProvider api-key base-url model
                      (provider-http/build-client)))
