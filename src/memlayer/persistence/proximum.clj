(ns memlayer.persistence.proximum
  "Proximum vector index management: store embeddings, search nearest neighbors."
  (:require [proximum.core :as prox]
            [memlayer.persistence.protocols :as protocols]
            [clojure.core.async :as a]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.nio.file Files Path]
           [java.util UUID]))

(defn create-index!
  "Create or load a proximum HNSW index.
   Supports :backend :memory (default, for tests) or :backend :file (persistent).
   For file backend, loads existing index if present, otherwise creates new."
  [{:keys [dim capacity backend path] :or {dim 1536 capacity 100000 backend :memory}}]
  (let [store-config (case backend
                       :file {:backend :file
                              :path    path
                              :id      (UUID/nameUUIDFromBytes (.getBytes (str "proximum:" path)))}
                       ;; default to :memory
                       {:backend :memory
                        :id      (UUID/randomUUID)})]
    (if (and (= backend :file)
             (.exists (java.io.File. ^String path)))
      (try
        (log/info "Loading existing proximum index" {:path path})
        (prox/load store-config)
        (catch Exception e
          (log/warn "Failed to load existing index, recreating" {:path path :error (.getMessage e)})
          (let [dir-path (.toPath (File. ^String path))]
            (doseq [f (reverse (sort (iterator-seq (.iterator (Files/walk dir-path (make-array java.nio.file.FileVisitOption 0))))))]
              (Files/deleteIfExists ^Path f)))
          (prox/create-index {:type         :hnsw
                              :dim          dim
                              :store-config store-config
                              :capacity     capacity})))
      (do
        (log/info "Creating proximum index" {:dim dim :capacity capacity :backend backend})
        (prox/create-index {:type         :hnsw
                            :dim          dim
                            :store-config store-config
                            :capacity     capacity})))))

(defn store-vector!
  "Store a vector with a string key (memory-id). Returns updated index."
  [index key-str embedding]
  (assoc index key-str embedding))

(defn sync-index!
  "Persist index changes. Returns synced index."
  [index]
  (a/<!! (prox/sync! index)))

(defn search
  "Search for k nearest neighbors. Returns seq of {:id :distance}."
  [index query-vec k]
  (prox/search index query-vec k))

(defn remove-vector!
  "Remove a vector by key. Returns updated index."
  [index key-str]
  (try
    (-> (dissoc index key-str)
        sync-index!)
    (catch Exception e
      (log/warn "Could not remove vector" key-str ":" (.getMessage e))
      index)))

(defn clear-index!
  "Create a fresh empty index, discarding all stored vectors.
   When a file-backed path is provided, deletes the old index files first."
  [{:keys [dim capacity backend path] :or {dim 1536 capacity 100000}}]
  (when (and path (.exists (File. ^String path)))
    (let [dir-path (.toPath (File. ^String path))]
      (doseq [f (reverse (sort (iterator-seq (.iterator (Files/walk dir-path (make-array java.nio.file.FileVisitOption 0))))))]
        (Files/deleteIfExists ^Path f)))
    (log/info "Cleared vector index files" {:path path}))
  (create-index! (cond-> {:dim dim :capacity capacity}
                   (and backend path) (assoc :backend backend :path path))))

(defn store-and-sync!
  "Store a vector and sync. Returns updated index."
  [index key-str embedding]
  (-> index
      (store-vector! key-str embedding)
      sync-index!))

;; -- VectorStore protocol implementation --

(defrecord ProximumVectorStore [index config]
  protocols/VectorStore
  (search [_ query-vec k] (search index query-vec k))
  (upsert! [_ key embedding]
    (->ProximumVectorStore (store-and-sync! index key embedding) config))
  (remove! [_ key]
    (->ProximumVectorStore (remove-vector! index key) config))
  (clear! [_]
    (->ProximumVectorStore (clear-index! config) config)))

(defn stored-keys
  "Return all keys stored in the vector index. For introspection/consistency checks."
  [store]
  (keys (:index store)))
