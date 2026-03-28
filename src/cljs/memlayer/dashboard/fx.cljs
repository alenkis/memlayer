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
  "Build an http-xhrio GET effect map with API key auth."
  [path api-key on-success on-failure]
  {:method          :get
   :uri             (config/api-url path)
   :headers         (cond-> {"Accept" "application/edn"}
                      api-key (assoc "X-API-Key" api-key))
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn api-post
  "Build an http-xhrio POST effect map with API key auth."
  [path body api-key on-success on-failure]
  {:method          :post
   :uri             (config/api-url path)
   :headers         (cond-> {"Accept" "application/edn"}
                      api-key (assoc "X-API-Key" api-key))
   :params          body
   :format          (ajax/json-request-format)
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

;; ---------------------------------------------------------------------------
;; Dashboard API helpers (authenticated)
;; ---------------------------------------------------------------------------

(defn dashboard-get
  "Build an http-xhrio GET effect map for dashboard endpoints with Bearer auth."
  [path id-token on-success on-failure]
  {:method          :get
   :uri             (config/api-url (str "/account" path))
   :headers         {"Accept"        "application/edn"
                     "Authorization" (str "Bearer " id-token)}
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn dashboard-post
  "Build an http-xhrio POST effect map for dashboard endpoints with Bearer auth."
  [path body id-token on-success on-failure]
  {:method          :post
   :uri             (config/api-url (str "/account" path))
   :headers         {"Accept"        "application/edn"
                     "Authorization" (str "Bearer " id-token)}
   :params          body
   :format          (ajax/json-request-format)
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn dashboard-delete
  "Build an http-xhrio DELETE effect map for dashboard endpoints with Bearer auth."
  [path id-token on-success on-failure]
  {:method          :delete
   :uri             (config/api-url (str "/account" path))
   :headers         {"Accept"        "application/edn"
                     "Authorization" (str "Bearer " id-token)}
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})

(defn dashboard-put
  "Build an http-xhrio PUT effect map for dashboard endpoints with Bearer auth."
  [path body id-token on-success on-failure]
  {:method          :put
   :uri             (config/api-url (str "/account" path))
   :headers         {"Accept"        "application/edn"
                     "Authorization" (str "Bearer " id-token)}
   :params          body
   :format          (ajax/json-request-format)
   :response-format (edn-response-format)
   :on-success      on-success
   :on-failure      on-failure})
