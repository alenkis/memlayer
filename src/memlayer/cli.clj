(ns memlayer.cli
  "Shared CLI option specs and parsing for memlayer entry points.
   All flag parsing happens here — bin/memlayer only handles
   env-file loading, mode dispatch, and JVM flags."
  (:require [clojure.tools.cli :as cli]))

(def common-cli-options
  "CLI option specs shared across all entry points."
  [["-p" "--port PORT" "Server port"
    :parse-fn parse-long
    :validate [pos-int? "Must be a positive integer"]]
   ["-n" "--namespace NAMESPACE" "Default namespace for memory operations"]
   [nil "--idle-timeout MINUTES" "Idle shutdown timeout in minutes"
    :parse-fn parse-long
    :validate [pos-int? "Must be a positive integer"]]
   ["-h" "--help" "Show this help message"]])

(defn parse-args
  "Parse CLI args with tools.cli. Returns a map with :options, :arguments,
   :errors, and :summary. Does not exit — callers decide what to do."
  [args]
  (cli/parse-opts args common-cli-options))

(defn parse-and-validate!
  "Parse CLI args. On --help, prints usage and exits 0.
   On errors, prints errors to stderr and exits 1.
   Returns the parsed options map."
  [args banner-text]
  (let [{:keys [options errors summary]} (parse-args args)]
    (when (:help options)
      (println banner-text)
      (println)
      (println summary)
      (System/exit 0))
    (when (seq errors)
      (binding [*out* *err*]
        (doseq [e errors] (println e))
        (println)
        (println summary))
      (System/exit 1))
    options))

(defn cli->config-overrides
  "Transform parsed CLI options into a config override map matching
   the nested config.edn structure. Only includes keys that were
   explicitly provided."
  [options]
  (cond-> {}
    (:port options) (assoc-in [:server :port] (:port options))))
