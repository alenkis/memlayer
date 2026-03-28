(ns memlayer.dashboard.subs
  (:require [re-frame.core :as rf]))

;; -- Routing --

(rf/reg-sub :route (fn [db _] (:route db)))

(rf/reg-sub
 :current-page
 :<- [:route]
 (fn [route _]
   (get-in route [:data :name] :dashboard)))

;; -- Health --

(rf/reg-sub :health (fn [db _] (:health db)))

;; -- Pipeline --

(rf/reg-sub :pipeline-status (fn [db _] (:pipeline-status db)))
(rf/reg-sub :pipeline (fn [db _] (:pipeline db)))
(rf/reg-sub :pipeline/operations (fn [db _] (get-in db [:pipeline :operations])))
(rf/reg-sub :pipeline/ops-loading? (fn [db _] (get-in db [:pipeline :ops-loading?])))
(rf/reg-sub :pipeline/selected-op (fn [db _] (get-in db [:pipeline :selected-op])))

;; -- Stats --

(rf/reg-sub :memory-stats (fn [db _] (:memory-stats db)))
(rf/reg-sub :consistency (fn [db _] (:consistency db)))

;; -- Memories --

(rf/reg-sub :memories (fn [db _] (:memories db)))
(rf/reg-sub :selected-memory (fn [db _] (:selected-memory db)))

;; -- Graph --

(rf/reg-sub :graph (fn [db _] (:graph db)))
(rf/reg-sub :reflect-loading? (fn [db _] (:reflect-loading? db)))

(rf/reg-sub
 :visible-graph-memories
 :<- [:graph]
 (fn [graph _]
   (let [visible (:visible-layers graph)]
     (filter #(contains? visible (keyword "layer" (:layer %)))
             (:memories graph)))))

(rf/reg-sub
 :graph/zoom-level
 :<- [:graph]
 (fn [graph _]
   (:zoom-level graph)))

(rf/reg-sub
 :graph/recall-state
 :<- [:graph]
 (fn [graph _]
   (:recall graph)))

;; -- Playground --

(rf/reg-sub :playground (fn [db _] (:playground db)))
(rf/reg-sub :playground-tab (fn [db _] (get-in db [:playground :active-tab])))
(rf/reg-sub :retain-state (fn [db _] (get-in db [:playground :retain])))
(rf/reg-sub :recall-state (fn [db _] (get-in db [:playground :recall])))
(rf/reg-sub :file-upload (fn [db _] (get-in db [:playground :file-upload])))

;; -- Namespaces --

(rf/reg-sub :namespaces (fn [db _] (:namespaces db)))
(rf/reg-sub :active-namespace (fn [db _] (:active-namespace db)))
(rf/reg-sub :namespaces/create-modal (fn [db _] (get-in db [:namespaces :create-modal])))
(rf/reg-sub :namespaces/delete-target (fn [db _] (get-in db [:namespaces :delete-target])))
(rf/reg-sub :namespaces/rename-target (fn [db _] (get-in db [:namespaces :rename-target])))

;; -- Tokens --

(rf/reg-sub :tokens (fn [db _] (:tokens db)))
(rf/reg-sub :tokens/new-token (fn [db _] (get-in db [:tokens :new-token])))

;; -- Settings --

(rf/reg-sub :settings (fn [db _] (:settings db)))
(rf/reg-sub :settings/active-tab (fn [db _] (get-in db [:settings :active-tab] :general)))
(rf/reg-sub :settings/keys-form (fn [db _] (get-in db [:settings :keys-form])))

;; -- Usage --

(rf/reg-sub :usage (fn [db _] (:usage db)))
(rf/reg-sub :usage/range (fn [db _] (get-in db [:usage :range] "30d")))
