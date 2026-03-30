(ns memlayer.mcp.lifecycle
  "Server lifecycle management for MCP clients.
   Auto-starts the memlayer server if not already running.
   Discovers server via health check on localhost."
  (:require [memlayer.mcp.client :as client]
            [clojure.tools.logging :as log])
  (:import [java.io File]
           [java.lang ProcessBuilder ProcessBuilder$Redirect Process ProcessHandle]))

(def ^:private memlayer-dir
  (str (System/getProperty "user.home") "/.memlayer"))

(def ^:private pid-file-path
  (str memlayer-dir "/server.pid"))

(defn- read-pid-file
  "Read the PID from the server PID file. Returns nil if not found."
  []
  (let [f (File. ^String pid-file-path)]
    (when (.exists f)
      (try
        (Long/parseLong (.trim (slurp f)))
        (catch Exception _ nil)))))

(defn- process-alive?
  "Check if a process with the given PID is alive."
  [pid]
  (.isPresent (ProcessHandle/of pid)))

(defn- remove-stale-pid-file! []
  (let [f (File. ^String pid-file-path)]
    (when (.exists f)
      (.delete f)
      (log/info "Removed stale PID file"))))

(defn- resolve-memlayer-command
  "Find the memlayer command. Tries the jar path passed as system property,
   then falls back to 'memlayer' on PATH."
  []
  (if-let [jar-path (System/getProperty "memlayer.jar-path")]
    ;; Running from jar — spawn java with the jar
    (let [java (or (System/getenv "JAVA_CMD")
                   (when-let [jh (System/getenv "JAVA_HOME")]
                     (str jh "/bin/java"))
                   "java")]
      [java "--add-modules" "jdk.incubator.vector"
       "--enable-native-access=ALL-UNNAMED"
       "-cp" jar-path "memlayer.local"])
    ;; Running from source or installed — use memlayer command
    ["memlayer" "server"]))

(defn- spawn-server!
  "Spawn memlayer server as a detached background process."
  [port]
  (log/info "Starting memlayer server on port" port)
  (.mkdirs (File. ^String memlayer-dir))
  (let [cmd     (resolve-memlayer-command)
        log-file (File. (str memlayer-dir "/server.log"))
        env-port (str port)
        ^ProcessBuilder pb (doto (ProcessBuilder. ^java.util.List cmd)
                             (.redirectErrorStream true)
                             (.redirectOutput (ProcessBuilder$Redirect/appendTo log-file)))]
    ;; Set MEMLAYER_PORT in the child process environment
    (.put (.environment pb) "MEMLAYER_PORT" env-port)
    (let [^Process proc (.start pb)]
      (log/info "Server process started, PID:" (.pid proc))
      (.pid proc))))

(defn- wait-for-server
  "Poll health check until server is ready. Returns true if ready, false on timeout."
  [base-url timeout-ms]
  (let [start   (System/currentTimeMillis)
        deadline (+ start timeout-ms)]
    (loop []
      (if (client/health-check base-url)
        (do (log/info "Server is ready")
            true)
        (if (> (System/currentTimeMillis) deadline)
          (do (log/error "Server did not become ready within" timeout-ms "ms")
              false)
          (do (Thread/sleep 500)
              (recur)))))))

(defn ensure-server!
  "Ensure the memlayer server is running. Starts it if necessary.
   Returns the base URL of the running server, or throws if it can't be started."
  [port]
  (let [base-url (str "http://localhost:" port)]
    ;; Fast path: server already running
    (if (client/health-check base-url)
      (do (log/info "Server already running on port" port)
          base-url)
      ;; Check for stale PID file
      (do
        (when-let [pid (read-pid-file)]
          (if (process-alive? pid)
            ;; PID is alive but health check failed — server might be starting
            (if (wait-for-server base-url 10000)
              (do (log/info "Server came up (PID" pid "was already running)")
                  base-url)
              (throw (ex-info "Server process alive but not responding"
                              {:pid pid :port port})))
            ;; Stale PID
            (remove-stale-pid-file!)))
        ;; Spawn new server
        (spawn-server! port)
        (if (wait-for-server base-url 30000)
          base-url
          (throw (ex-info "Failed to start memlayer server"
                          {:port port})))))))

(defn try-restart-server!
  "Attempt to restart the server after a connection failure.
   Returns the base URL if successful, nil otherwise."
  [port]
  (let [base-url (str "http://localhost:" port)]
    (log/warn "Server connection lost, attempting restart...")
    (remove-stale-pid-file!)
    (spawn-server! port)
    (when (wait-for-server base-url 30000)
      base-url)))
