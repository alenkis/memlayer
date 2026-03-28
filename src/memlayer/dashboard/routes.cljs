(ns memlayer.dashboard.routes
  (:require [reitit.frontend :as rf]
            [reitit.frontend.easy :as rfe]
            [re-frame.core :as re-frame]))

(def routes
  ["/"
   ["" {:name :dashboard}]
   ["login" {:name :login}]
   ["browser" {:name :browser}]
   ["graph" {:name :graph}]
   ["playground" {:name :playground}]
   ["usage" {:name :usage}]
   ["namespaces" {:name :namespaces}]
   ["settings" {:name :settings}]
   ["pipeline" {:name :pipeline}]])

(defn on-navigate [match]
  (when match
    (re-frame/dispatch [:set-route match])))

(defn init! []
  (rfe/start!
   (rf/router routes)
   on-navigate
   {:use-fragment false}))

(defn href [route-name]
  (rfe/href route-name))
