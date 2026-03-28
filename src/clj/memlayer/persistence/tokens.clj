(ns memlayer.persistence.tokens
  "Token CRUD operations using datahike.
   Tokens are used for programmatic API access. The plaintext is returned
   exactly once at creation time; only the SHA-256 hash is stored."
  (:require [datahike.api :as d]
            [clojure.tools.logging :as log])
  (:import [java.util UUID Date]
           [java.security MessageDigest]
           [java.util HexFormat]))

(defn- ->conn
  "Extract raw datahike connection from a store or passthrough if already raw."
  [store-or-conn]
  (or (:conn store-or-conn) store-or-conn))

;; -- Schema --

(def schema
  [{:db/ident       :token/id
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/name
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/hash
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/prefix
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/owner
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/created-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}
   {:db/ident       :token/revoked-at
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one}])

(defn transact-schema!
  "Transact the token schema into the database."
  [conn]
  (let [conn (->conn conn)]
    (d/transact conn schema)))

;; -- Helpers --

(defn sha256
  "Compute SHA-256 hex digest of a string."
  ^String [^String s]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes s "UTF-8"))]
    (.formatHex (HexFormat/of) bytes)))

(defn generate-plaintext
  "Generate a token plaintext: \"mlk_\" + UUID v4 (no dashes)."
  []
  (str "mlk_" (.replace (str (UUID/randomUUID)) "-" "")))

;; -- CRUD --

(defn create-token!
  "Create a new token. Returns {:token plaintext :id id :prefix prefix :name name}.
   The plaintext is only available at creation time."
  [conn owner-id token-name]
  (let [conn       (->conn conn)
        plaintext  (generate-plaintext)
        token-hash (sha256 plaintext)
        prefix     (str (subs plaintext 0 12) "...")
        id         (str (UUID/randomUUID))
        now        (Date.)]
    (log/info "Creating token" token-name "for user" owner-id)
    (d/transact conn [{:token/id         id
                       :token/name       token-name
                       :token/hash       token-hash
                       :token/prefix     prefix
                       :token/owner      owner-id
                       :token/created-at now}])
    {:token  plaintext
     :id     id
     :prefix prefix
     :name   token-name}))

(defn list-tokens
  "List all tokens for an owner. Returns token entities (without hash)."
  [conn owner-id]
  (let [conn (->conn conn)]
    (->> (d/q '[:find [(pull ?e [:token/id :token/name :token/prefix
                                 :token/owner :token/created-at :token/revoked-at]) ...]
                :in $ ?owner
                :where [?e :token/owner ?owner]]
              @conn owner-id)
         (sort-by :token/created-at #(compare %2 %1))
         vec)))

(defn find-token-by-hash
  "Find a token by its SHA-256 hash. Returns the full token entity or nil."
  [conn token-hash]
  (let [conn (->conn conn)]
    (first (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?hash
                  :where [?e :token/hash ?hash]]
                @conn token-hash))))

(defn find-token-by-id
  "Find a token by its ID. Returns the full token entity or nil."
  [conn token-id]
  (let [conn (->conn conn)]
    (first (d/q '[:find [(pull ?e [*]) ...]
                  :in $ ?id
                  :where [?e :token/id ?id]]
                @conn token-id))))

(defn revoke-token!
  "Soft-delete a token by setting revoked-at. Returns true if revoked."
  [conn token-id owner-id]
  (let [conn  (->conn conn)
        token (find-token-by-id conn token-id)]
    (if (and token (= owner-id (:token/owner token)))
      (do
        (log/info "Revoking token" token-id "for user" owner-id)
        (d/transact conn [{:token/id         token-id
                           :token/revoked-at (Date.)}])
        true)
      false)))

(defn count-active-tokens
  "Count active (non-revoked) tokens for an owner."
  [conn owner-id]
  (let [conn (->conn conn)]
    (count
     (->> (d/q '[:find [(pull ?e [:token/revoked-at]) ...]
                 :in $ ?owner
                 :where [?e :token/owner ?owner]]
               @conn owner-id)
          (remove :token/revoked-at)))))
