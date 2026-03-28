(ns memlayer.operations.user-settings
  "User settings persistence using datahike.
   Stores per-user LLM provider keys and default API token.
   NOTE: API keys are stored in plaintext. This is an accepted risk for now —
   early-stage users manage their own tokens on OpenAI/Anthropic directly.
   See `deferred encrypt` in memlayer-dashboard.allium for the planned
   AES-256-GCM encryption approach."
  (:require [memlayer.persistence.datahike :as dh]
            [clojure.tools.logging :as log])
  (:import [java.util Date]))

;; -- Schema --

(def schema
  [{:db/ident       :user-settings/user-id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user-settings/groq-key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user-settings/openai-key
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user-settings/default-api-token
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :user-settings/updated-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn transact-schema!
  "Transact the user-settings schema into the database."
  [conn]
  (dh/transact-user-settings-schema! conn schema))

;; -- CRUD --

(defn get-settings
  "Get user settings for a user-id. Returns nil if not found."
  [conn user-id]
  (dh/get-user-settings conn user-id))

(defn save-keys!
  "Save LLM provider keys. Uses merge semantics: only provided (non-nil) keys
   are overwritten. Omitted keys preserve existing values."
  [conn user-id {:keys [groq-api-key openai-api-key]}]
  (let [existing (get-settings conn user-id)
        entity   (cond-> {:user-settings/user-id    user-id
                          :user-settings/updated-at (Date.)}
                   groq-api-key   (assoc :user-settings/groq-key groq-api-key)
                   openai-api-key (assoc :user-settings/openai-key openai-api-key)
                   ;; Preserve existing keys if not provided
                   (and (nil? groq-api-key) (:user-settings/groq-key existing))
                   (assoc :user-settings/groq-key (:user-settings/groq-key existing))
                   (and (nil? openai-api-key) (:user-settings/openai-key existing))
                   (assoc :user-settings/openai-key (:user-settings/openai-key existing)))]
    (log/info "Saving API keys for user" user-id)
    (dh/save-user-settings! conn entity)))

(defn delete-keys!
  "Remove all provider keys for a user."
  [conn user-id]
  (when-let [existing (get-settings conn user-id)]
    (let [eid (dh/find-user-settings-eid conn user-id)]
      (when eid
        (log/info "Deleting API keys for user" user-id)
        (let [retractions (cond-> []
                            (:user-settings/groq-key existing)
                            (conj [:db/retract eid :user-settings/groq-key (:user-settings/groq-key existing)])
                            (:user-settings/openai-key existing)
                            (conj [:db/retract eid :user-settings/openai-key (:user-settings/openai-key existing)]))]
          (when (seq retractions)
            (dh/retract-user-settings-attrs!
             conn (conj retractions
                        {:user-settings/user-id    user-id
                         :user-settings/updated-at (Date.)}))))))))

(defn set-default-token!
  "Store the plaintext of the most recently created token."
  [conn user-id token-plaintext]
  (dh/save-user-settings! conn {:user-settings/user-id          user-id
                                :user-settings/default-api-token token-plaintext
                                :user-settings/updated-at        (Date.)}))
