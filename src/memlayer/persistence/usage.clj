(ns memlayer.persistence.usage
  "Usage event persistence and model pricing in Datahike.
   Records LLM call usage (tokens, model, cost) and provides aggregation
   queries for the dashboard. Model pricing is stored in Datahike so rate
   changes are tracked via immutable history."
  (:require [datahike.api :as d]
            [memlayer.schema :as schema]
            [clojure.tools.logging :as log])
  (:import [java.util UUID Date Calendar]
           [java.time ZoneOffset]
           [java.time.format DateTimeFormatter]))

(defn- ->conn
  "Extract raw datahike connection from a store or passthrough if already raw."
  [store-or-conn]
  (or (:conn store-or-conn) store-or-conn))

;; -- Schema --

(def schema
  [;; Usage events
   {:db/ident       :usage/id
    :db/valueType   :db.type/uuid
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/operation
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :usage/step
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/provider
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one
    :db/index       true}
   {:db/ident       :usage/model
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/namespace
    :db/valueType   :db.type/string
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/prompt-tokens
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/completion-tokens
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/total-tokens
    :db/valueType   :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/cost-usd
    :db/valueType   :db.type/double
    :db/cardinality :db.cardinality/one}
   {:db/ident       :usage/timestamp
    :db/valueType   :db.type/instant
    :db/cardinality :db.cardinality/one
    :db/index       true}
   ;; Model pricing
   {:db/ident       :model-pricing/model
    :db/valueType   :db.type/string
    :db/unique      :db.unique/identity
    :db/cardinality :db.cardinality/one}
   {:db/ident       :model-pricing/prompt
    :db/valueType   :db.type/double
    :db/cardinality :db.cardinality/one}
   {:db/ident       :model-pricing/completion
    :db/valueType   :db.type/double
    :db/cardinality :db.cardinality/one}])

(defn transact-schema!
  "Transact the usage + model-pricing schema into the database."
  [conn]
  (let [conn (->conn conn)]
    (d/transact conn schema)))

;; -- Model Pricing --

(def default-pricing
  [{:model-pricing/model      "text-embedding-3-small"
    :model-pricing/prompt     0.02
    :model-pricing/completion 0.0}
   {:model-pricing/model      "llama-3.3-70b-versatile"
    :model-pricing/prompt     0.59
    :model-pricing/completion 0.79}])

(defn seed-default-pricing!
  "Transact default model pricing. Idempotent — upserts via :db.unique/identity."
  [conn]
  (let [conn (->conn conn)]
    (d/transact conn default-pricing)))

(defn get-pricing
  "Look up current pricing for a model. Returns {:prompt N :completion N} or nil."
  [conn model]
  (let [conn (->conn conn)]
    (when-let [e (d/q '[:find (pull ?e [:model-pricing/prompt :model-pricing/completion]) .
                        :in $ ?model
                        :where [?e :model-pricing/model ?model]]
                      @conn model)]
      {:prompt     (:model-pricing/prompt e)
       :completion (:model-pricing/completion e)})))

(defn update-pricing!
  "Upsert pricing for a model. Old rates preserved in Datahike history."
  [conn model {:keys [prompt completion]}]
  (let [conn (->conn conn)]
    (d/transact conn [(cond-> {:model-pricing/model model}
                        prompt     (assoc :model-pricing/prompt (double prompt))
                        completion (assoc :model-pricing/completion (double completion)))])))

(defn estimate-cost
  "Estimate cost in USD for a given model and token counts.
   Looks up pricing from Datahike. Returns 0.0 for unknown models."
  [conn model prompt-tokens completion-tokens]
  (let [conn (->conn conn)]
    (if-let [{:keys [prompt completion]} (get-pricing conn model)]
      (+ (* (or prompt-tokens 0) (/ prompt 1e6))
         (* (or completion-tokens 0) (/ completion 1e6)))
      0.0)))

;; -- Usage Event Recording --

(defn record-usage!
  "Record a single LLM usage event."
  [conn {:keys [operation step provider model namespace
                prompt-tokens completion-tokens total-tokens cost-usd]}]
  (let [conn (->conn conn)]
    (d/transact conn [{:usage/id                (UUID/randomUUID)
                       :usage/operation         operation
                       :usage/step              step
                       :usage/provider          (or provider "unknown")
                       :usage/model             (or model "unknown")
                       :usage/namespace         (or namespace schema/default-namespace)
                       :usage/prompt-tokens     (long (or prompt-tokens 0))
                       :usage/completion-tokens (long (or completion-tokens 0))
                       :usage/total-tokens      (long (or total-tokens 0))
                       :usage/cost-usd          (double (or cost-usd 0.0))
                       :usage/timestamp         (Date.)}])))

(defn record-from-provider!
  "Record a usage event from a provider response map with operation context.
   Looks up pricing from Datahike and computes cost automatically."
  [conn {:keys [operation step namespace]} provider-usage]
  (let [conn              (->conn conn)
        model             (:model provider-usage)
        prompt-tokens     (:prompt-tokens provider-usage 0)
        completion-tokens (:completion-tokens provider-usage 0)
        cost              (estimate-cost conn model prompt-tokens completion-tokens)]
    (record-usage! conn {:operation         operation
                         :step              step
                         :provider          (:provider provider-usage)
                         :model             model
                         :namespace         namespace
                         :prompt-tokens     prompt-tokens
                         :completion-tokens completion-tokens
                         :total-tokens      (:total-tokens provider-usage 0)
                         :cost-usd          cost})))

(defn record-from-provider-safe!
  "Like record-from-provider! but catches and logs errors instead of throwing.
   Usage recording is non-critical — a schema mismatch or transact failure
   should never crash the calling operation."
  [conn context provider-usage]
  (let [conn (->conn conn)]
    (try
      (record-from-provider! conn context provider-usage)
      (catch Exception e
        (log/warn "Usage recording failed (non-fatal)" {:context context :error (.getMessage e)})))))

;; -- Aggregation Queries --

(defn- cutoff-date
  "Compute a cutoff Date by subtracting range-days from now."
  [range-days]
  (let [cal (Calendar/getInstance)]
    (.add cal Calendar/DAY_OF_YEAR (- range-days))
    (.getTime cal)))

(defn- query-in-range
  "Query all usage events within range-days. Returns pulled entities."
  [conn range-days]
  (let [conn   (->conn conn)
        cutoff (cutoff-date range-days)]
    (d/q '[:find [(pull ?e [*]) ...]
           :in $ ?cutoff
           :where
           [?e :usage/id _]
           [?e :usage/timestamp ?ts]
           [(<= ?cutoff ?ts)]]
         @conn cutoff)))

(defn aggregate-summary
  "Aggregate usage within range.
   Returns {:total-tokens N
            :total-cost N
            :by-provider [{:provider s :total-tokens N :cost N}]
            :by-operation [{:operation s :provider s :step s :total-tokens N :call-count N}]}"
  [conn {:keys [range-days]}]
  (let [conn        (->conn conn)
        events      (query-in-range conn range-days)
        total-tokens (reduce + 0 (map :usage/total-tokens events))
        total-cost   (reduce + 0.0 (map :usage/cost-usd events))
        by-provider  (->> events
                          (group-by :usage/provider)
                          (mapv (fn [[provider evts]]
                                  {:provider     provider
                                   :total-tokens (reduce + 0 (map :usage/total-tokens evts))
                                   :cost         (reduce + 0.0 (map :usage/cost-usd evts))})))
        by-operation (->> events
                          (group-by (juxt :usage/operation :usage/provider :usage/step))
                          (mapv (fn [[[op prov step] evts]]
                                  {:operation    op
                                   :provider     prov
                                   :step         step
                                   :total-tokens (reduce + 0 (map :usage/total-tokens evts))
                                   :call-count   (count evts)})))]
    {:total-tokens  total-tokens
     :total-cost    total-cost
     :by-provider   by-provider
     :by-operation  by-operation}))

(defn aggregate-timeseries
  "Aggregate daily token usage within range.
   Returns [{:date \"2026-03-10\" :provider \"openai\" :total-tokens N} ...]"
  [conn {:keys [range-days]}]
  (let [conn   (->conn conn)
        events (query-in-range conn range-days)
        fmt    (DateTimeFormatter/ofPattern "yyyy-MM-dd")]
    (->> events
         (group-by (fn [e]
                     [(.format (.toLocalDate (.atZone (.toInstant ^Date (:usage/timestamp e)) ZoneOffset/UTC)) fmt)
                      (:usage/provider e)]))
         (mapv (fn [[[date provider] evts]]
                 {:date         date
                  :provider     provider
                  :total-tokens (reduce + 0 (map :usage/total-tokens evts))}))
         (sort-by (juxt :date :provider)))))

(defn aggregate-by-namespace
  "Aggregate token usage by namespace within range.
   Returns [{:namespace \"default\" :total-tokens N :cost N} ...]"
  [conn {:keys [range-days]}]
  (let [conn   (->conn conn)
        events (query-in-range conn range-days)]
    (->> events
         (group-by :usage/namespace)
         (mapv (fn [[ns-name evts]]
                 {:namespace    ns-name
                  :total-tokens (reduce + 0 (map :usage/total-tokens evts))
                  :cost         (reduce + 0.0 (map :usage/cost-usd evts))}))
         (sort-by :total-tokens >))))
