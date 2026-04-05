(ns memlayer.api.dashboard
  "API handlers for dashboard endpoints."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.persistence.usage :as usage]
            [memlayer.operations.forget :as forget]
            [memlayer.schema :as schema]
            [memlayer.util.pagination :as pg]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

;; -- Usage --

(def ^:private range->days
  {"7d" 7 "30d" 30 "90d" 90})

(defn usage-handler
  "GET /api/v1/account/usage?range=7d|30d|90d - Aggregate usage analytics."
  [{:keys [db]}]
  (fn [request]
    (let [range-val (get-in request [:query-params "range"] "30d")
          days      (get range->days range-val 30)
          opts      {:range-days days}]
      {:status 200
       :body   {:summary      (usage/aggregate-summary db opts)
                :timeseries   (usage/aggregate-timeseries db opts)
                :by-namespace (usage/aggregate-by-namespace db opts)
                :memory-stats {:by-layer     (or (dh/count-by-layer db) {})
                               :by-namespace []}}})))

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

(defmethod ig/init-key :handler/dashboard [_ {:keys [db deps]}]
  {:usage            (usage-handler {:db db})
   :list-namespaces  (list-namespaces-handler {:db db})
   :create-namespace (create-namespace-handler {:db db})
   :rename-namespace (rename-namespace-handler {:db db})
   :delete-namespace (delete-namespace-handler (or deps {:db db}))})
