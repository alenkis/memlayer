(ns memlayer.dashboard.fx
  "HTTP effect helpers for re-frame."
  (:require [cljs.reader :as reader]
            [day8.re-frame.http-fx]
            [ajax.core :as ajax]
            [memlayer.dashboard.config :as config]))

(defn- edn-response-format
  "Custom response format that requests EDN and parses it with cljs.reader."
  []
  {:read         (fn [^js xhrio]
                   (when-let [text (not-empty (.getResponseText xhrio))]
                     (reader/read-string text)))
   :description  "EDN"
   :content-type ["application/edn"]})

(defn api-get
  "Build an http-xhrio GET effect map."
  [path on-success on-failure]
  {:method          :get
   :uri             (config/api-url path)
   :headers         {"Accept" "application/edn"}
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn api-post
  "Build an http-xhrio POST effect map."
  [path body on-success on-failure]
  {:method          :post
   :uri             (config/api-url path)
   :headers         {"Accept" "application/edn"}
   :params          body
   :format          (ajax/json-request-format)
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn api-put
  "Build an http-xhrio PUT effect map."
  [path body on-success on-failure]
  {:method          :put
   :uri             (config/api-url path)
   :headers         {"Accept" "application/edn"}
   :params          body
   :format          (ajax/json-request-format)
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn api-delete
  "Build an http-xhrio DELETE effect map."
  [path on-success on-failure]
  {:method          :delete
   :uri             (config/api-url path)
   :headers         {"Accept" "application/edn"}
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})
