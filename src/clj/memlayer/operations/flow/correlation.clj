(ns memlayer.operations.flow.correlation
  "Correlation map for request-response over a long-lived flow.
   Each request gets a UUID and a promise-chan; the flow's execute
   process resolves the promise when all results are collected."
  (:require [clojure.core.async :as a])
  (:import [java.util UUID]
           [java.util.concurrent ConcurrentHashMap]))

(defn create-correlation-map
  "Create a new ConcurrentHashMap for correlating requests to promise-chans."
  []
  (ConcurrentHashMap.))

(defn register!
  "Register a new correlation. Returns [correlation-id promise-chan]."
  [^ConcurrentHashMap cmap]
  (let [cid  (UUID/randomUUID)
        chan (a/promise-chan)]
    (.put cmap cid chan)
    [cid chan]))

(defn resolve!
  "Deliver a result for the given correlation-id. Puts result on the
   promise-chan and removes the entry from the map."
  [^ConcurrentHashMap cmap correlation-id result]
  (when-let [chan (.remove cmap correlation-id)]
    (a/put! chan result)))

(defn await-result
  "Block waiting for a correlation result with timeout.
   Returns the result or nil on timeout. Cleans up on timeout."
  [^ConcurrentHashMap cmap correlation-id chan timeout-ms]
  (let [[result _] (a/alts!! [chan (a/timeout timeout-ms)])]
    (when (nil? result)
      (.remove cmap correlation-id))
    result))

(defn drain!
  "Close all pending promise-chans. Call on shutdown."
  [^ConcurrentHashMap cmap]
  (doseq [[_ chan] cmap]
    (a/close! chan))
  (.clear cmap))
