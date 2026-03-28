(ns memlayer.local
  "Entry point for memlayer local mode.
   Runs the full application as a self-contained local process with
   file-backed storage, no auth, and bundled dashboard."
  (:gen-class)
  (:require [memlayer.system :as system]
            [memlayer.server :as server]
            [memlayer.version :as version]
            [integrant.core :as ig]
            [ring.middleware.resource :as resource]
            [ring.util.response :as response]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.util Date]))

;; GraalVM native-image serves resources via the "resource" URL protocol.
;; Ring only handles :file and :jar by default.
(defmethod response/resource-data :resource
  [^java.net.URL url]
  (let [path (.getPath url)]
    ;; Skip directories (no file extension) — let SPA fallback handle them.
    (when (re-find #"\.\w+$" path)
      (let [conn (.openConnection url)]
        {:content        (.getInputStream conn)
         :content-length (let [len (.getContentLength conn)]
                           (when (pos? len) len))
         :last-modified  (let [lm (.getLastModified conn)]
                           (when (pos? lm) (Date. lm)))}))))

(defn- spa-handler [_]
  (-> (response/resource-response "public/index.html")
      (assoc-in [:headers "Content-Type"] "text/html; charset=utf-8")))

(defn- wrap-static-and-spa
  "Wrap a handler with static file serving and SPA fallback.
   API/MCP routes pass through; everything else tries static file then SPA."
  [handler]
  (fn [request]
    (or (handler request)
        (resource/resource-request request "public")
        (spa-handler request))))

;; Local server init-key — wraps router with static file + SPA fallback
(defmethod ig/init-key :memlayer/local-server [_ {:keys [handler config]}]
  (let [port    (get-in config [:server :port])
        wrapped (wrap-static-and-spa handler)]
    (server/start! wrapped port)))

(defmethod ig/halt-key! :memlayer/local-server [_ stop-fn]
  (server/stop! stop-fn))

(defn -main [& _args]
  (let [info @version/build-info]
    (log/info (str "memlayer local " (:version info)
                   " (" (:git-sha info) ")")))
  ;; Ensure parent data directory exists (datahike/proximum create their own subdirs)
  (.mkdirs (File. (str (System/getProperty "user.home") "/.memlayer")))
  (let [system (system/start-local-system!)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable #(system/stop-system! system)))
    (log/info "memlayer local is running")))
