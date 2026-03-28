(ns memlayer.dashboard.auth
  (:require [re-frame.core :as rf]
            [ajax.core :as ajax]
            ["firebase/auth" :refer [onAuthStateChanged signInWithPopup signOut]]
            [memlayer.dashboard.firebase :as firebase]
            [memlayer.dashboard.config :as config]))

;; ---------------------------------------------------------------------------
;; Auth config check — determines if Firebase auth is needed
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :auth/check-server-config
 (fn [_ _]
   {:http-xhrio {:method          :get
                 :uri             (config/api-url "/auth/config")
                 :response-format (ajax/json-response-format {:keywords? true})
                 :on-success      [:auth/server-config-received]
                 :on-failure      [:auth/server-config-failed]}}))

;; ---------------------------------------------------------------------------
;; Firebase effects (cloud mode only)
;; ---------------------------------------------------------------------------

(rf/reg-fx
 :firebase/listen-auth-state
 (fn [_]
   (onAuthStateChanged
    firebase/auth
    (fn [user]
      (rf/dispatch [:auth/state-changed user])))))

(rf/reg-fx
 :firebase/sign-in-google
 (fn [_]
   (-> (signInWithPopup firebase/auth firebase/google-provider)
       (.catch (fn [err] (js/console.error "Google sign-in error:" err))))))

(rf/reg-fx
 :firebase/sign-out
 (fn [_]
   (-> (signOut firebase/auth)
       (.catch (fn [err] (js/console.error "Sign-out error:" err))))))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :auth/init
 (fn [_ _]
   {:dispatch [:auth/check-server-config]}))

(rf/reg-event-fx
 :auth/server-config-received
 (fn [{:keys [db]} [_ result]]
   (if (:auth-required result)
     ;; Cloud mode — use Firebase auth
     {:firebase/listen-auth-state true}
     ;; Local mode — skip Firebase, auto-authenticate
     {:db (-> db
              (assoc-in [:auth :user] {:uid "local" :email nil :display-name "Local User"})
              (assoc-in [:auth :loading?] false))
      :dispatch-n [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
                   [:fetch-namespaces] [:fetch-pipeline-status]]})))

(rf/reg-event-fx
 :auth/server-config-failed
 (fn [_ _]
   ;; Can't reach server config — fall back to Firebase auth
   {:firebase/listen-auth-state true}))

(rf/reg-event-fx
 :auth/state-changed
 (fn [{:keys [db]} [_ user]]
   (if user
     {:db (-> db
              (assoc-in [:auth :user] {:uid          (.-uid user)
                                       :email        (.-email user)
                                       :display-name (.-displayName user)
                                       :photo-url    (.-photoURL user)})
              (assoc-in [:auth :loading?] false))
      :dispatch-n [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
                   [:fetch-namespaces] [:fetch-pipeline-status]]}
     {:db (-> db
              (assoc-in [:auth :user] nil)
              (assoc-in [:auth :loading?] false))})))

(rf/reg-event-fx
 :auth/sign-in
 (fn [_ _]
   {:firebase/sign-in-google true}))

(rf/reg-event-fx
 :auth/sign-out
 (fn [{:keys [db]} _]
   {:db (assoc-in db [:auth :user] nil)
    :firebase/sign-out true}))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub :auth/user (fn [db _] (get-in db [:auth :user])))
(rf/reg-sub :auth/loading? (fn [db _] (get-in db [:auth :loading?] true)))
(rf/reg-sub :auth/authenticated?
            :<- [:auth/user]
            (fn [user _] (some? user)))
