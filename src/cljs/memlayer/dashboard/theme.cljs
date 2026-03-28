(ns memlayer.dashboard.theme
  (:require [re-frame.core :as rf]))

;; ---------------------------------------------------------------------------
;; Effects
;; ---------------------------------------------------------------------------

(rf/reg-fx
 :theme/apply
 (fn [theme]
   (if (= theme :dark)
     (.add (.-classList js/document.documentElement) "dark")
     (.remove (.-classList js/document.documentElement) "dark"))))

(rf/reg-fx
 :theme/persist
 (fn [theme]
   (try
     (.setItem js/localStorage "memlayer-theme" (name theme))
     (catch :default _))))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :theme/init
 (fn [{:keys [db]} _]
   (let [stored (try (.getItem js/localStorage "memlayer-theme") (catch :default _ nil))
         theme  (cond
                  (= stored "dark")  :dark
                  (= stored "light") :light
                  (and (exists? js/window.matchMedia)
                       (.-matches (.matchMedia js/window "(prefers-color-scheme: dark)")))
                  :dark
                  :else :light)]
     {:db           (assoc db :theme theme)
      :theme/apply  theme})))

(rf/reg-event-fx
 :theme/toggle
 (fn [{:keys [db]} _]
   (let [current (:theme db)
         next-theme (if (= current :dark) :light :dark)]
     {:db            (assoc db :theme next-theme)
      :theme/apply   next-theme
      :theme/persist next-theme})))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub :theme/current (fn [db _] (:theme db :light)))
