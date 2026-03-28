(ns memlayer.dashboard.auth
  (:require [re-frame.core :as rf]
            [ajax.core :as ajax]
            ["firebase/auth" :refer [onAuthStateChanged signInWithPopup signOut]]
            [memlayer.dashboard.firebase :as firebase]
            [memlayer.dashboard.config :as config]))

;; ---------------------------------------------------------------------------
;; Firebase effects
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

(rf/reg-fx
 :firebase/get-id-token
 (fn [{:keys [on-success]}]
   (let [user (.-currentUser firebase/auth)]
     (when user
       (-> (.getIdToken user)
           (.then (fn [token]
                    (rf/dispatch (conj on-success token))))
           (.catch (fn [err] (js/console.error "getIdToken error:" err))))))))

;; ---------------------------------------------------------------------------
;; Events
;; ---------------------------------------------------------------------------

(rf/reg-event-fx
 :auth/init
 (fn [_ _]
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
      :firebase/get-id-token {:on-success [:auth/token-received]}}
     {:db (-> db
              (assoc-in [:auth :user] nil)
              (assoc-in [:auth :loading?] false)
              (assoc-in [:auth :id-token] nil)
              (assoc-in [:auth :active-api-key] nil))})))

(rf/reg-event-fx
 :auth/sign-in
 (fn [_ _]
   {:firebase/sign-in-google true}))

(rf/reg-event-fx
 :auth/sign-out
 (fn [{:keys [db]} _]
   {:db (-> db
            (assoc-in [:auth :user] nil)
            (assoc-in [:auth :id-token] nil)
            (assoc-in [:auth :active-api-key] nil))
    :firebase/sign-out true}))

(rf/reg-event-fx
 :auth/token-received
 (fn [{:keys [db]} [_ token]]
   {:db (assoc-in db [:auth :id-token] token)
    :dispatch [:auth/fetch-active-token]}))

(rf/reg-event-fx
 :auth/fetch-active-token
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio {:method          :get
                     :uri             (config/api-url "/account/active-token")
                     :headers         {"Authorization" (str "Bearer " id-token)}
                     :response-format (ajax/json-response-format {:keywords? true})
                     :on-success      [:auth/active-token-success]
                     :on-failure      [:auth/active-token-failure]}}))))

(rf/reg-event-fx
 :auth/active-token-success
 (fn [{:keys [db]} [_ result]]
   (if (:token result)
     {:db         (assoc-in db [:auth :active-api-key] (:token result))
      :dispatch-n [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
                   [:fetch-namespaces] [:fetch-pipeline-status]]}
     ;; No token exists — auto-create a default one
     {:dispatch [:auth/auto-create-token]})))

(rf/reg-event-fx
 :auth/auto-create-token
 (fn [{:keys [db]} _]
   (let [id-token (get-in db [:auth :id-token])]
     (when id-token
       {:http-xhrio {:method          :post
                     :uri             (config/api-url "/account/tokens")
                     :headers         {"Authorization" (str "Bearer " id-token)}
                     :params          {:name "default"}
                     :format          (ajax/json-request-format)
                     :response-format (ajax/json-response-format {:keywords? true})
                     :on-success      [:auth/auto-create-token-success]
                     :on-failure      [:auth/auto-create-token-failure]}}))))

(rf/reg-event-fx
 :auth/auto-create-token-success
 (fn [{:keys [db]} [_ result]]
   {:db         (assoc-in db [:auth :active-api-key] (:token result))
    :dispatch-n [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
                 [:fetch-namespaces] [:fetch-pipeline-status]]}))

(rf/reg-event-db
 :auth/auto-create-token-failure
 (fn [db [_ error]]
   (js/console.error "Failed to auto-create API token:" (clj->js error))
   (assoc-in db [:auth :api-key-error] "Failed to create API token. Please try again.")))

(rf/reg-event-db
 :auth/active-token-failure
 (fn [db [_ error]]
   (js/console.error "Failed to fetch active token:" (clj->js error))
   (assoc-in db [:auth :api-key-error] "Failed to fetch API token. Please try again.")))

(rf/reg-event-fx
 :auth/retry-api-key
 (fn [{:keys [db]} _]
   {:db       (update db :auth dissoc :api-key-error)
    :dispatch [:auth/fetch-active-token]}))

;; ---------------------------------------------------------------------------
;; Subscriptions
;; ---------------------------------------------------------------------------

(rf/reg-sub :auth/user (fn [db _] (get-in db [:auth :user])))
(rf/reg-sub :auth/loading? (fn [db _] (get-in db [:auth :loading?] true)))
(rf/reg-sub :auth/authenticated?
            :<- [:auth/user]
            (fn [user _] (some? user)))
(rf/reg-sub :auth/id-token (fn [db _] (get-in db [:auth :id-token])))
(rf/reg-sub :auth/active-api-key (fn [db _] (get-in db [:auth :active-api-key])))
(rf/reg-sub :auth/api-key-error (fn [db _] (get-in db [:auth :api-key-error])))
