(ns memlayer.api.reflect
  "HTTP handler for the reflect endpoint."
  (:require [memlayer.operations.reflect :as reflect]
            [integrant.core :as ig])
  (:import [java.time Instant]
           [java.util Date]))

(defn- parse-since
  "Parse `since` param: ISO-8601 string or epoch millis integer. nil = all time."
  [v]
  (cond
    (nil? v)     (Date. 0)
    (integer? v) (Date. (long v))
    (string? v)  (Date/from (Instant/parse v))
    :else        (Date. 0)))

(defn handler
  "POST /api/v1/reflect handler."
  [deps]
  (fn [request]
    (let [body   (:body-params request)
          params {:dry-run   (:dry-run body)
                  :namespace (:namespace body)
                  :phases    (:phases body)
                  :since     (parse-since (:since body))}
          result (reflect/reflect! deps params)]
      {:status 200 :body result})))

(defmethod ig/init-key :handler/reflect [_ {:keys [deps]}]
  (handler deps))
