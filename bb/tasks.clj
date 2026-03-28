(ns tasks
  (:require [babashka.process :refer [shell process]]
            [clojure.java.io :as io]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; .env loading
;; ---------------------------------------------------------------------------

(defn load-dotenv
  "Parse .env file into {\"KEY\" \"val\"} map. Handles comments, blank lines,
   quoted values, and `export` prefix. Returns empty map if .env doesn't exist."
  []
  (let [f (io/file ".env")]
    (if (.exists f)
      (->> (slurp f)
           str/split-lines
           (remove #(or (str/blank? %) (str/starts-with? (str/trim %) "#")))
           (map (fn [line]
                  (let [line (str/replace line #"^export\s+" "")
                        [k v] (str/split line #"=" 2)
                        v (or v "")]
                    [(str/trim k)
                     (-> v str/trim (str/replace #"^[\"']|[\"']$" ""))])))
           (into {}))
      {})))

(defn shell-env
  "Run shell command with .env vars merged into environment."
  [& args]
  (let [env (load-dotenv)
        [opts & cmd] (if (map? (first args)) args (cons {} args))]
    (apply shell (merge {:extra-env env} opts) cmd)))

;; ---------------------------------------------------------------------------
;; File staleness
;; ---------------------------------------------------------------------------

(defn stale?
  "True if target file is missing or older than any of the dependency files."
  [target deps]
  (let [target-file (io/file target)]
    (or (not (.exists target-file))
        (let [target-mtime (.lastModified target-file)]
          (some (fn [dep]
                  (let [f (io/file dep)]
                    (and (.exists f)
                         (> (.lastModified f) target-mtime))))
                deps)))))

;; ---------------------------------------------------------------------------
;; Terraform helpers
;; ---------------------------------------------------------------------------

(defn tf-output
  "Capture a single terraform output value. Returns \"NOT_SET\" on failure."
  [key]
  (try
    (-> (shell {:out :string :dir "infra"} "terraform" "output" "-raw" key)
        :out
        str/trim)
    (catch Exception _e "NOT_SET")))

(defn infra-env-shell!
  "Run a command in the infra/ dir after injecting 1Password secrets."
  [& cmd-parts]
  (shell {:dir "infra"} "bash" "-c"
         (str "op inject -f -i .env.1p -o .env && "
              "set -a && . .env && set +a && "
              (str/join " " cmd-parts))))

;; ---------------------------------------------------------------------------
;; Release
;; ---------------------------------------------------------------------------

(defn release!
  "Create and push a version tag. Usage: bb release v0.4.0"
  []
  (let [version (first *command-line-args*)]
    (when-not version
      (println "Usage: bb release <version>  (e.g. bb release v0.4.0)")
      (System/exit 1))
    (when-not (re-matches #"v\d+\.\d+\.\d+" version)
      (println "Error: VERSION must match vMAJOR.MINOR.PATCH (e.g., v0.4.0)")
      (System/exit 1))
    (when (zero? (:exit (shell {:continue true} "git" "rev-parse" version)))
      (println (str "Error: tag " version " already exists"))
      (System/exit 1))
    (let [branch (-> (shell {:out :string} "git" "branch" "--show-current")
                     :out str/trim)]
      (when (not= branch "main")
        (println (str "Error: releases must be created from main (currently on " branch ")"))
        (System/exit 1)))
    (println (str "Creating tag " version "..."))
    (shell "git" "tag" "-a" version "-m" (str "Release " version))
    (println (str "Pushing tag " version " to origin..."))
    (shell "git" "push" "origin" version)
    (println (str "Release " version " triggered."))))

;; ---------------------------------------------------------------------------
;; Env generation
;; ---------------------------------------------------------------------------

(defn env!
  "Generate .env from 1Password."
  []
  (if (.exists (io/file ".env"))
    (println ".env already exists. Remove it first to regenerate.")
    (do (shell "op" "inject" "-i" ".env.1p" "-o" ".env")
        (println ".env created successfully."))))

;; ---------------------------------------------------------------------------
;; Distribution
;; ---------------------------------------------------------------------------

(defn dist!
  "Copy uberjar + launcher into dist/."
  []
  (let [dist-dir (io/file "dist")]
    (.mkdirs dist-dir)
    (io/copy (io/file "target/memlayer.jar") (io/file "dist/memlayer.jar"))
    (io/copy (io/file "bin/memlayer") (io/file "dist/memlayer"))
    (shell "chmod" "+x" "dist/memlayer")
    (println "Distribution ready in dist/")))
