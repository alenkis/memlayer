(ns memlayer.dashboard.core
  (:require [reagent.dom.client :as rdc]
            [re-frame.core :as rf]
            [memlayer.dashboard.events]
            [memlayer.dashboard.subs]
            [memlayer.dashboard.auth]
            [memlayer.dashboard.theme]
            [memlayer.dashboard.routes :as routes]
            [memlayer.dashboard.views.layout :as layout]))

(defonce root (rdc/create-root (.getElementById js/document "app")))

(defn ^:dev/after-load after-load []
  (rf/clear-subscription-cache!)
  (rdc/render root [layout/layout]))

(defn init []
  (rf/dispatch-sync [:initialize-db])
  (rf/dispatch [:auth/init])
  (rf/dispatch [:theme/init])
  (routes/init!)
  (after-load))
