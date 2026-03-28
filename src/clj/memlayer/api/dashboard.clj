(ns memlayer.api.dashboard
  "API handlers for dashboard endpoints.
   All handlers require Firebase JWT or dev-mode authentication.
   The :user-context is attached to the request by the auth middleware."
  (:require [memlayer.persistence.tokens :as tokens]
            [memlayer.operations.user-settings :as user-settings]
            [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.stratum :as strat]
            [memlayer.persistence.usage :as usage]
            [memlayer.operations.forget :as forget]
            [memlayer.schema :as schema]
            [memlayer.util.pagination :as pg]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; -- Helpers --

(defn- user-id [request]
  (get-in request [:user-context :user-id]))

;; -- GET /me --

(defn me-handler
  "GET /api/v1/account/me - Return user profile from auth context."
  [_deps]
  (fn [request]
    (let [ctx (:user-context request)]
      {:status 200
       :body   {:uid   (:user-id ctx)
                :email (:email ctx)
                :name  (:name ctx)}})))

;; -- Token management --

(defn- serialize-token [t]
  {:id         (:token/id t)
   :name       (:token/name t)
   :prefix     (:token/prefix t)
   :created-at (some-> (:token/created-at t) str)
   :revoked-at (some-> (:token/revoked-at t) str)})

(defn list-tokens-handler
  "GET /api/v1/account/tokens - List user's tokens."
  [{:keys [db]}]
  (fn [request]
    (let [uid    (user-id request)
          tkns   (tokens/list-tokens db uid)]
      {:status 200
       :body   {:tokens (mapv serialize-token tkns)}})))

(defn create-token-handler
  "POST /api/v1/account/tokens - Create a new API token."
  [{:keys [db]}]
  (fn [request]
    (let [uid        (user-id request)
          body       (:body-params request)
          token-name (or (:name body) "default")
          active-ct  (tokens/count-active-tokens db uid)]
      (if (>= active-ct 10)
        {:status 422
         :body   {:error "Maximum of 10 active tokens reached"}}
        (let [result (tokens/create-token! db uid token-name)]
          ;; Store as default token in user settings
          (user-settings/set-default-token! db uid (:token result))
          {:status 201
           :body   result})))))

(defn revoke-token-handler
  "DELETE /api/v1/account/tokens/:id - Revoke a token (soft delete)."
  [{:keys [db]}]
  (fn [request]
    (let [uid      (user-id request)
          token-id (get-in request [:path-params :id])]
      (if (tokens/revoke-token! db token-id uid)
        {:status 200
         :body   {:message "Token revoked"}}
        {:status 404
         :body   {:error "Token not found or not owned by you"}}))))

;; -- Active token --

(defn active-token-handler
  "GET /api/v1/account/active-token - Return the most recently created token's plaintext."
  [{:keys [db]}]
  (fn [request]
    (let [uid      (user-id request)
          settings (user-settings/get-settings db uid)
          token    (:user-settings/default-api-token settings)]
      {:status 200
       :body   {:token token}})))

;; -- Settings --

(defn settings-handler
  "GET /api/v1/account/settings - Return user settings."
  [{:keys [db]}]
  (fn [request]
    (let [uid      (user-id request)
          ctx      (:user-context request)
          settings (user-settings/get-settings db uid)]
      {:status 200
       :body   {:email          (:email ctx)
                :has-groq-key   (some? (:user-settings/groq-key settings))
                :has-openai-key (some? (:user-settings/openai-key settings))}})))

(defn save-keys-handler
  "POST /api/v1/account/settings/keys - Save LLM provider keys."
  [{:keys [db]}]
  (fn [request]
    (let [uid  (user-id request)
          body (:body-params request)]
      (user-settings/save-keys! db uid {:groq-api-key   (:groq-api-key body)
                                        :openai-api-key (:openai-api-key body)})
      {:status 200
       :body   {:message "Keys saved"}})))

(defn delete-keys-handler
  "DELETE /api/v1/account/settings/keys - Remove all provider keys."
  [{:keys [db]}]
  (fn [request]
    (let [uid (user-id request)]
      (user-settings/delete-keys! db uid)
      {:status 200
       :body   {:message "Keys deleted"}})))

;; -- Usage --

(def ^:private range->days
  {"7d" 7 "30d" 30 "90d" 90})

(defn usage-handler
  "GET /api/v1/account/usage?range=7d|30d|90d - Aggregate usage analytics."
  [{:keys [db stratum]}]
  (fn [request]
    (let [range-val (get-in request [:query-params "range"] "30d")
          days      (get range->days range-val 30)
          opts      {:range-days days}
          dataset   (when stratum @stratum)]
      {:status 200
       :body   {:summary      (usage/aggregate-summary db opts)
                :timeseries   (usage/aggregate-timeseries db opts)
                :by-namespace (usage/aggregate-by-namespace db opts)
                :memory-stats {:by-layer     (or (strat/count-by-layer dataset) [])
                               :by-namespace (or (strat/count-by-namespace dataset) [])
                               :by-source    (or (strat/count-by-source dataset) [])}}})))

;; -- Namespace management --

(defn list-namespaces-handler
  "GET /api/v1/account/namespaces - List namespaces with details."
  [{:keys [db]}]
  (fn [request]
    (let [{:keys [limit offset]} (pg/parse-pagination (:query-params request))
          all-ns   (dh/get-distinct-namespaces db)
          total    (count all-ns)
          page     (->> all-ns (drop offset) (take limit))
          ns-infos (mapv (fn [ns-name]
                           {:id         ns-name
                            :name       ns-name
                            :created-at nil})
                         page)]
      {:status 200
       :body   {:namespaces ns-infos
                :total      total
                :limit      limit
                :offset     offset}})))

(defn create-namespace-handler
  "POST /api/v1/account/namespaces - Create a namespace."
  [{:keys [db]}]
  (fn [request]
    (let [body    (:body-params request)
          ns-name (:name body)]
      (if (or (nil? ns-name) (not (re-matches schema/namespace-pattern ns-name)))
        {:status 400
         :body   {:error "Invalid namespace name. Must match [a-z0-9-]{1,64}."}}
        (let [existing (dh/get-distinct-namespaces db)]
          (if (some #{ns-name} existing)
            {:status 409
             :body   {:error "Namespace already exists"}}
            {:status 201
             :body   {:namespace {:id   ns-name
                                  :name ns-name}}}))))))

(defn rename-namespace-handler
  "PUT /api/v1/account/namespaces/:id - Rename a namespace."
  [{:keys [db]}]
  (fn [request]
    (let [old-name (get-in request [:path-params :id])
          body     (:body-params request)
          new-name (:name body)]
      (if (or (nil? new-name) (not (re-matches schema/namespace-pattern new-name)))
        {:status 400
         :body   {:error "Invalid namespace name. Must match [a-z0-9-]{1,64}."}}
        (let [existing (set (dh/get-distinct-namespaces db))]
          (cond
            (not (existing old-name))
            {:status 404
             :body   {:error "Namespace not found"}}

            (existing new-name)
            {:status 409
             :body   {:error "Target namespace name already exists"}}

            :else
            (do
              ;; Rename: update all memories in old namespace to new namespace
              (let [memories (dh/get-memories-by-namespace db old-name :limit 100000)]
                (doseq [m memories]
                  (dh/update-memory! db (:memory/id m) {:memory/namespace new-name})))
              {:status 200
               :body   {:namespace {:id   new-name
                                    :name new-name}}})))))))

(defn delete-namespace-handler
  "DELETE /api/v1/account/namespaces/:id - Delete a namespace and all its memories."
  [{:keys [db] :as deps}]
  (fn [request]
    (let [ns-name (get-in request [:path-params :id])
          existing (set (dh/get-distinct-namespaces db))]
      (if (not (existing ns-name))
        {:status 404
         :body   {:error "Namespace not found"}}
        (do
          (let [memories (dh/get-memories-by-namespace db ns-name :limit 100000)]
            (log/info "Deleting namespace" ns-name "with" (count memories) "memories")
            (doseq [m memories]
              (forget/forget! deps {:memory-id (:memory/id m)})))
          {:status 204
           :body   nil})))))

(defmethod ig/init-key :handler/dashboard [_ {:keys [db stratum]}]
  (let [stratum-atom (some-> stratum :dataset-atom)]
    {:me               (me-handler {})
     :list-tokens      (list-tokens-handler {:db db})
     :create-token     (create-token-handler {:db db})
     :revoke-token     (revoke-token-handler {:db db})
     :active-token     (active-token-handler {:db db})
     :settings         (settings-handler {:db db})
     :save-keys        (save-keys-handler {:db db})
     :delete-keys      (delete-keys-handler {:db db})
     :usage            (usage-handler {:stratum stratum-atom :db db})
     :list-namespaces  (list-namespaces-handler {:db db})
     :create-namespace (create-namespace-handler {:db db})
     :rename-namespace (rename-namespace-handler {:db db})
     :delete-namespace (delete-namespace-handler {:db db})}))
