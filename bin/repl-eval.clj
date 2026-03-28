#!/usr/bin/env bb
;; Self-managing Clojure socket REPL evaluator.
;; Starts its own REPL process on a random port if none is running.
;; The REPL survives after this script exits (detached via ProcessBuilder).
;;
;; Usage: bin/repl-eval.clj '(+ 1 2)'
;;        bin/repl-eval.clj '(require ...) (some-fn)'
;;        echo '(+ 1 2)' | bin/repl-eval.clj

(require '[clojure.java.io :as io]
         '[clojure.string :as str])

(def project-dir (-> (io/file *file*) .getParentFile .getParentFile .getCanonicalPath))
(def port-file (io/file project-dir ".repl-port"))
(def pid-file (io/file project-dir ".repl-pid"))
(def log-file (io/file project-dir ".repl.log"))

(defn free-port []
  (with-open [sock (java.net.ServerSocket. 0)]
    (.getLocalPort sock)))

(defn port-open? [port]
  (try
    (with-open [_ (java.net.Socket. "localhost" (int port))]
      true)
    (catch Exception _ false)))

(defn pid-alive? [pid]
  (try
    (let [proc (.start (doto (ProcessBuilder. ["kill" "-0" (str pid)])
                         (.redirectErrorStream true)))]
      (.waitFor proc)
      (zero? (.exitValue proc)))
    (catch Exception _ false)))

(defn repl-alive? []
  (when (and (.exists port-file) (.exists pid-file))
    (try
      (let [port (parse-long (str/trim (slurp port-file)))
            pid  (parse-long (str/trim (slurp pid-file)))]
        (and (pid-alive? pid) (port-open? port)))
      (catch Exception _ false))))

;; NOTE: MEM-11 is done. Build orchestration migrated to bb in MEM-46.
(defn start-repl!
  "Start a fully detached Clojure socket REPL via bin/repl-daemon.sh."
  []
  (let [port   (free-port)
        daemon (str project-dir "/bin/repl-daemon.sh")]
    (binding [*out* *err*]
      (println (str "Starting socket REPL on port " port "...")))
    ;; Launch daemon script which backgrounds clojure and writes PID
    (let [pb   (doto (ProcessBuilder. [daemon (str port) (.getPath pid-file) (.getPath log-file)])
                 (.directory (io/file project-dir))
                 (.inheritIO))
          proc (.start pb)]
      (.waitFor proc)
      (when-not (zero? (.exitValue proc))
        (binding [*out* *err*]
          (println "Error: daemon script failed"))
        (System/exit 1)))
    (spit port-file (str port))
    ;; Wait for PID file to be written and socket to be ready
    (loop [i 0]
      (cond
        (>= i 90)
        (do (binding [*out* *err*]
              (println (str "Error: REPL not ready after 90s. Check " log-file)))
            (.delete port-file)
            (.delete pid-file)
            (System/exit 1))

        (and (.exists pid-file)
             (not (pid-alive? (str/trim (slurp pid-file)))))
        (do (binding [*out* *err*]
              (println (str "Error: REPL process died. Check " log-file)))
            (.delete port-file)
            (.delete pid-file)
            (System/exit 1))

        (port-open? port)
        (let [pid (str/trim (slurp pid-file))]
          (binding [*out* *err*]
            (println (str "REPL ready (pid=" pid ", port=" port ")"))))

        :else
        (do (Thread/sleep 1000)
            (recur (inc i)))))))

(defn eval-code! [code]
  (let [port (parse-long (str/trim (slurp port-file)))]
    (with-open [sock (java.net.Socket. "localhost" (int port))
                out  (io/writer (.getOutputStream sock))
                in   (io/reader (.getInputStream sock))]
      (.write out (str code "\n:repl/quit\n"))
      (.flush out)
      (loop []
        (let [line (.readLine in)]
          (when line
            (println line)
            (recur)))))))

;; --- Main ---

(when-not (repl-alive?)
  (.delete port-file)
  (.delete pid-file)
  (start-repl!))

(let [code (if (seq *command-line-args*)
             (str/join " " *command-line-args*)
             (slurp *in*))]
  (when (str/blank? code)
    (binding [*out* *err*]
      (println "Usage: bin/repl-eval.clj '(+ 1 2)'"))
    (System/exit 1))
  (eval-code! code))
