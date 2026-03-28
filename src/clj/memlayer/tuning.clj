(ns memlayer.tuning
  "Centralized tuning constants for memlayer subsystems.
   Constants here are the defaults — config.edn values take precedence
   at runtime via the (or (:key tuning) tuning/constant) pattern.")

;; ---------------------------------------------------------------------------
;; Chunking
;; ---------------------------------------------------------------------------

(def ^:const chunk-char-limit 8000)
(def ^:const chunk-search-zone-ratio 0.2)

;; ---------------------------------------------------------------------------
;; Recall
;; ---------------------------------------------------------------------------

(def ^:const recall-default-limit 10)
(def ^:const recall-max-rels-per-memory 20)
(def ^:const recall-max-siblings-per-parent 5)
(def ^:const recall-graph-proximity-bonus 0.05)
(def ^:const recall-vector-oversample 3)

;; ---------------------------------------------------------------------------
;; Reflect
;; ---------------------------------------------------------------------------

(def ^:const reflect-default-batch-size 15)
(def ^:const reflect-connect-top-k 5)
(def ^:const reflect-connect-batch-size 5)
(def ^:const reflect-connect-max-pairs 100)
(def ^:const reflect-curate-batch-size 10)

;; ---------------------------------------------------------------------------
;; Provider HTTP
;; ---------------------------------------------------------------------------

(def ^:const provider-request-timeout-ms 120000)
(def ^:const provider-connect-timeout-ms 30000)
