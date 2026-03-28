(ns memlayer.operations.flow.event-log
  "Bounded in-memory log of recent pipeline operations.
   Used by the dashboard pipeline page to show operation history."
  (:require [memlayer.schema :as schema])
  (:import [java.time Instant Duration]))

(def ^:private max-entries 100)

(defn create-log
  "Create a new event log (an atom wrapping a vector of entries)."
  []
  (atom []))

(defn record-start!
  "Record the start of a pipeline operation."
  [log correlation-id items namespace source]
  (let [entry {:id         (str correlation-id)
               :started-at (str (Instant/now))
               :items      (mapv (fn [item]
                                   {:content (subs (:content item "")
                                                   0
                                                   (min 200 (count (:content item ""))))
                                    :source  (:source item)})
                                 items)
               :item-count (count items)
               :namespace  (or namespace schema/default-namespace)
               :source     (or source "api")
               :status     "in-flight"}]
    (swap! log (fn [entries]
                 (let [updated (conj entries entry)]
                   (if (> (count updated) max-entries)
                     (subvec updated (- (count updated) max-entries))
                     updated))))
    entry))

(defn record-complete!
  "Record the completion of a pipeline operation."
  [log correlation-id result]
  (let [now (Instant/now)]
    (swap! log (fn [entries]
                 (mapv (fn [e]
                         (if (= (:id e) (str correlation-id))
                           (let [started (Instant/parse (:started-at e))
                                 dur-ms  (.toMillis (Duration/between started now))]
                             (assoc e
                                    :completed-at (str now)
                                    :status (if (:error result) "error" "completed")
                                    :result {:memory-ids (mapv str (:memory-ids result))
                                             :decisions  (:decisions result)
                                             :usage      (:usage result)
                                             :error      (:error result)}
                                    :duration-ms dur-ms))
                           e))
                       entries)))))

(defn record-timeout!
  "Record that an operation timed out."
  [log correlation-id]
  (let [now (Instant/now)]
    (swap! log (fn [entries]
                 (mapv (fn [e]
                         (if (= (:id e) (str correlation-id))
                           (let [started (Instant/parse (:started-at e))
                                 dur-ms  (.toMillis (Duration/between started now))]
                             (assoc e
                                    :completed-at (str now)
                                    :status "timeout"
                                    :duration-ms dur-ms))
                           e))
                       entries)))))

(defn get-operations
  "Return recent operations, most recent first."
  [log]
  (vec (rseq @log)))

(defn get-operation
  "Return a single operation by id."
  [log id]
  (some #(when (= (:id %) id) %) @log))
