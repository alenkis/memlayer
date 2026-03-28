(ns tasks
  (:require [babashka.process :refer [shell]]
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
