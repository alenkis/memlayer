(ns memlayer.api.memories
  "API handlers for memory CRUD operations (dashboard)."
  (:require [memlayer.persistence.datahike :as dh]
            [memlayer.operations.forget :as forget]
            [memlayer.util.pagination :as pg]
            [integrant.core :as ig])
  (:import [java.util UUID]))

(defn- serialize-memory
  "Convert a datahike memory entity to API response format."
  [m]
  (cond-> {:id         (str (:memory/id m))
           :content    (:memory/content m)
           :layer      (name (:memory/layer m))
           :source     (:memory/source m)
           :namespace  (:memory/namespace m)
           :parent-id  (some-> (:memory/parent-id m) str)}
    (:memory/display-title m) (assoc :display-title (:memory/display-title m))))

(defn- serialize-relationship [r]
  (cond-> {:id        (str (:relationship/id r))
           :source-id (str (:relationship/source-id r))
           :target-id (str (:relationship/target-id r))
           :type      (name (:relationship/type r))}
    (:relationship/description r) (assoc :description (:relationship/description r))))

(defn- ->uuid [s]
  (try (UUID/fromString s) (catch Exception _ nil)))

(defn list-handler
  "GET /api/v1/memories - List memories with optional filters."
  [{:keys [db]}]
  (fn [request]
    (let [params            (:query-params request)
          namespace         (get params "namespace")
          layer-str         (get params "layer")
          layer             (when layer-str (keyword "layer" layer-str))
          {:keys [limit offset]} (pg/parse-pagination params)
          memories          (dh/get-all-memories db
                                                 :namespace namespace
                                                 :layer layer
                                                 :limit limit
                                                 :offset offset)
          total             (dh/count-all-memories db
                                                   :namespace namespace
                                                   :layer layer)]
      {:status 200
       :body   {:memories (mapv serialize-memory memories)
                :total    total
                :limit    limit
                :offset   offset}})))

(defn get-handler
  "GET /api/v1/memories/:id - Get single memory."
  [{:keys [db]}]
  (fn [request]
    (let [id-str (get-in request [:path-params :id])
          id     (->uuid id-str)]
      (if-let [memory (when id (dh/get-memory db id))]
        {:status 200 :body (serialize-memory memory)}
        {:status 404 :body {:error "Memory not found"}}))))

(defn children-handler
  "GET /api/v1/memories/:id/children - Get child memories."
  [{:keys [db]}]
  (fn [request]
    (if-let [id (->uuid (get-in request [:path-params :id]))]
      (let [{:keys [limit offset]} (pg/parse-pagination (:query-params request))]
        {:status 200
         :body   {:children (mapv serialize-memory
                                  (dh/get-children db id :limit limit :offset offset))
                  :total    (dh/count-children db id)
                  :limit    limit
                  :offset   offset}})
      {:status 400 :body {:error "Invalid ID"}})))

(defn relationships-handler
  "POST /api/v1/relationships - Fetch relationships for given memory IDs."
  [{:keys [db]}]
  (fn [request]
    (let [body       (:body-params request)
          memory-ids (keep ->uuid (:memory-ids body))
          rels       (dh/get-relationships db memory-ids)]
      {:status 200
       :body   {:relationships (mapv serialize-relationship (or rels []))}})))

(defn delete-handler
  "DELETE /api/v1/memories/:id - Delete a memory and its embedding.
   Delegates to the forget operation (retract, preserves history)."
  [deps]
  (fn [request]
    (if-let [id (->uuid (get-in request [:path-params :id]))]
      (let [result (forget/forget! deps {:memory-id id})]
        (if (pos? (:memories-removed result))
          {:status 204 :body nil}
          {:status 404 :body {:error "Memory not found"}}))
      {:status 404 :body {:error "Memory not found"}})))

(defn memory-history-handler
  "GET /api/v1/memories/:id/history - Get memory change history."
  [{:keys [db]}]
  (fn [request]
    (if-let [id (->uuid (get-in request [:path-params :id]))]
      (let [{:keys [limit offset]} (pg/parse-pagination (:query-params request))
            all-history (dh/get-memory-history db id)
            total       (count all-history)
            page        (->> all-history (drop offset) (take limit) vec)]
        {:status 200
         :body   {:history page
                  :total   total
                  :limit   limit
                  :offset  offset}})
      {:status 400 :body {:error "Invalid ID"}})))

(defmethod ig/init-key :handler/memories [_ {:keys [db deps]}]
  {:list          (list-handler {:db db})
   :get           (get-handler {:db db})
   :delete        (delete-handler deps)
   :children      (children-handler {:db db})
   :relationships (relationships-handler {:db db})
   :history       (memory-history-handler {:db db})})
