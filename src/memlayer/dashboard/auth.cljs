(ns memlayer.dashboard.auth
  (:require [re-frame.core :as rf]))

;; Always authenticated — single local user.

(rf/reg-event-fx
 :auth/init
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:auth :user] {:uid "local" :email nil :display-name "Local User"})
            (assoc-in [:auth :loading?] false))
    :dispatch-n [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
                 [:fetch-namespaces] [:fetch-pipeline-status]]}))

(rf/reg-event-fx
 :auth/sign-out
 (fn [_ _] {}))

;; Subscriptions

(rf/reg-sub :auth/user (fn [db _] (get-in db [:auth :user])))
(rf/reg-sub :auth/loading? (fn [db _] (get-in db [:auth :loading?] false)))
(rf/reg-sub :auth/authenticated?
            :<- [:auth/user]
            (fn [user _] (some? user)))
