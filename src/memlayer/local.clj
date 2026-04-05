(ns memlayer.local
  "Entry point for memlayer local mode.
   Runs the full application as a self-contained local process with
   file-backed storage, no auth, and bundled dashboard."
  (:gen-class)
  (:require [memlayer.cli :as cli]
            [memlayer.system :as system]
            [memlayer.server :as server]
            [memlayer.middleware.idle-timeout :as idle]
            [memlayer.version :as version]
            [integrant.core :as ig]
            [ring.middleware.resource :as resource]
            [ring.util.response :as response]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.util Date]
           [java.lang ProcessHandle]))

(def ^:private memlayer-dir
  (str (System/getProperty "user.home") "/.memlayer"))

(def ^:private pid-file-path
  (str memlayer-dir "/server.pid"))

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
    (let [resp (handler request)]
      (if (or (nil? resp) (= 404 (:status resp)))
        (or (resource/resource-request request "public")
            (spa-handler request))
        resp))))

;; Local server init-key — wraps router with static file + SPA fallback + activity tracking
(defmethod ig/init-key :memlayer/local-server [_ {:keys [handler config]}]
  (let [port    (get-in config [:server :port])
        wrapped (-> handler
                    idle/wrap-activity-tracking
                    wrap-static-and-spa)]
    (server/start! wrapped port)))

(defmethod ig/halt-key! :memlayer/local-server [_ stop-fn]
  (server/stop! stop-fn))

(defn- write-pid-file! []
  (let [pid (.pid (ProcessHandle/current))]
    (spit pid-file-path (str pid))
    (log/info "Wrote PID file:" pid-file-path "PID:" pid)))

(defn- remove-pid-file! []
  (let [f (File. ^String pid-file-path)]
    (when (.exists f)
      (.delete f)
      (log/info "Removed PID file"))))

(def ^:private shutting-down? (atom false))

(defn- halt-with-deadline!
  "Halt the system with a 5-second deadline. If halt hangs, force-exit."
  [system]
  (when (= ::timeout (deref (future (system/stop-system! system)) 5000 ::timeout))
    (log/warn "Shutdown deadline exceeded, forcing exit")
    (.halt (Runtime/getRuntime) 1)))

(defn -main [& args]
  (let [info @version/build-info]
    (log/info (str "memlayer local " (:version info)
                   " (" (:git-sha info) ")")))
  (let [options          (cli/parse-and-validate! args "memlayer server - Local HTTP server with dashboard")
        config-overrides (cli/cli->config-overrides options)
        timeout-ms       (or (some-> (:idle-timeout options) (* 60 1000))
                             (some-> (System/getenv "MEMLAYER_IDLE_TIMEOUT_MINUTES")
                                     parse-long
                                     (* 60 1000)))]
    ;; Ensure parent data directory exists (datahike/proximum create their own subdirs)
    (.mkdirs (File. ^String memlayer-dir))
    (let [system    (system/start-local-system! config-overrides)
          shutdown! (fn []
                      (when (compare-and-set! shutting-down? false true)
                        (log/info "Shutting down memlayer...")
                        (remove-pid-file!)
                        (halt-with-deadline! system)))
          stop-idle (idle/start-idle-watcher!
                     (fn []
                       (shutdown!)
                       (.halt (Runtime/getRuntime) 0))
                     timeout-ms)]
      (write-pid-file!)
      (.addShutdownHook (Runtime/getRuntime)
                        (Thread. ^Runnable (fn []
                                             (stop-idle)
                                             (shutdown!))))
      (log/info "memlayer local is running"))))
