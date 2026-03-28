(ns memlayer.dashboard.onboarding-test
  "Pure logic tests for onboarding visibility and event handler return values.
   No side effects, no HTTP, no re-frame dispatch machinery."
  (:require [cljs.test :refer [deftest is testing]]
            [memlayer.dashboard.db :as db]))

;; ---------------------------------------------------------------------------
;; The API uses EDN content negotiation (Accept: application/edn), so
;; responses contain proper Clojure keywords with hyphens (:namespace-total).
;; Tests MUST use the same format the CLJS app actually sees.
;; ---------------------------------------------------------------------------

(def ^:private api-stats-response
  "Simulates a real stats API response as seen by the CLJS app (EDN keywords)."
  {:namespace-total 3
   :global-total    3
   :active-count    3
   :namespace-count 1
   :by-layer        {"fact" 3}})

(def ^:private api-stats-one-memory
  {:namespace-total 1
   :global-total    1
   :active-count    1
   :namespace-count 1
   :by-layer        {"fact" 1}})

(def ^:private api-stats-empty
  {:namespace-total 0
   :global-total    0
   :active-count    0
   :namespace-count 0
   :by-layer        {}})

;; ---------------------------------------------------------------------------
;; Onboarding predicate (mirrors dashboard/page condition)
;; ---------------------------------------------------------------------------

(defn- show-onboarding?
  "Returns true when onboarding card should be visible.
   Replicates: (zero? global-total)"
  [db]
  (let [global-total (get-in db [:memory-stats :data :global-total] 0)]
    (zero? global-total)))

;; ---------------------------------------------------------------------------
;; Onboarding visibility
;; ---------------------------------------------------------------------------

(deftest onboarding-shows-with-default-db
  (testing "onboarding visible with default db (no memories)"
    (is (true? (show-onboarding? db/default-db)))))

(deftest onboarding-shows-when-zero-memories
  (testing "onboarding visible with zero memories"
    (let [db (assoc db/default-db :memory-stats {:data api-stats-empty :loading? false :error nil})]
      (is (true? (show-onboarding? db))))))

(deftest onboarding-hides-when-memories-exist
  (testing "onboarding hidden when global-total > 0"
    (let [db (assoc db/default-db :memory-stats {:data api-stats-response :loading? false :error nil})]
      (is (false? (show-onboarding? db))))))

(deftest onboarding-hides-with-one-memory
  (testing "onboarding hidden as soon as global-total reaches 1"
    (let [db (assoc db/default-db :memory-stats {:data api-stats-one-memory :loading? false :error nil})]
      (is (false? (show-onboarding? db))))))

;; ---------------------------------------------------------------------------
;; Event handler return values (pure fn tests)
;; ---------------------------------------------------------------------------

;; retain-success handler logic
(defn- retain-success-handler [{:keys [db]} [_ result]]
  {:db       (-> db
                 (assoc-in [:playground :retain :response] result)
                 (assoc-in [:playground :retain :loading?] false)
                 (assoc-in [:playground :retain :error] nil))
   :dispatch [:fetch-memory-stats]})

(deftest retain-success-dispatches-fetch-memory-stats
  (testing ":retain-success returns :dispatch [:fetch-memory-stats]"
    (let [cofx   {:db db/default-db}
          result (retain-success-handler cofx [:retain-success {:id "mem-1"}])]
      (is (= [:fetch-memory-stats] (:dispatch result)))
      (is (= false (get-in (:db result) [:playground :retain :loading?]))))))

;; fetch-memory-stats-success handler logic
(defn- fetch-memory-stats-success-handler [db [_ result]]
  (assoc db :memory-stats {:data result :loading? false :error nil}))

(deftest stats-success-updates-db
  (testing ":fetch-memory-stats-success stores API response"
    (let [new-db (fetch-memory-stats-success-handler db/default-db
                                                     [:fetch-memory-stats-success api-stats-response])]
      (is (= 3 (get-in new-db [:memory-stats :data :namespace-total])))
      (is (= false (get-in new-db [:memory-stats :loading?]))))))

;; ---------------------------------------------------------------------------
;; Full flow (pure)
;; ---------------------------------------------------------------------------

(deftest full-flow-retain-then-stats-hides-onboarding
  (testing "retain success → stats refetch → onboarding hides"
    (let [retain-effects (retain-success-handler
                          {:db db/default-db}
                          [:retain-success {:id "mem-1"}])]
      (is (= [:fetch-memory-stats] (:dispatch retain-effects)))

      (let [db-after-stats (fetch-memory-stats-success-handler
                            (:db retain-effects)
                            [:fetch-memory-stats-success api-stats-one-memory])]
        (is (false? (show-onboarding? db-after-stats))
            "onboarding hides after first retain + stats refresh")))))

;; ---------------------------------------------------------------------------
;; Namespace handler tests
;; ---------------------------------------------------------------------------

;; set-active-namespace handler logic
(defn- set-active-namespace-handler [{:keys [db]} [_ ns-name]]
  {:db                (assoc db :active-namespace ns-name)
   :persist-namespace ns-name
   :dispatch-n        [[:fetch-memory-stats]
                       [:fetch-memories]
                       [:fetch-graph-data]
                       [:fetch-health]
                       [:fetch-consistency]]})

(deftest set-active-namespace-refetches-all-data
  (testing ":set-active-namespace updates db and dispatches all page fetches"
    (let [result (set-active-namespace-handler {:db db/default-db}
                                               [:set-active-namespace "my-ns"])]
      (is (= "my-ns" (:active-namespace (:db result))))
      (is (= "my-ns" (:persist-namespace result)))
      (is (= [[:fetch-memory-stats] [:fetch-memories] [:fetch-graph-data]
              [:fetch-health] [:fetch-consistency]]
             (:dispatch-n result))))))

(deftest set-active-namespace-clears-to-nil
  (testing ":set-active-namespace with nil clears the namespace"
    (let [db (assoc db/default-db :active-namespace "old-ns")
          result (set-active-namespace-handler {:db db}
                                               [:set-active-namespace nil])]
      (is (nil? (:active-namespace (:db result))))
      (is (some? (:dispatch-n result))))))

;; fetch-namespaces-success handler logic (prepends default)
(defn- fetch-namespaces-success-handler [db [_ result]]
  (let [items (:namespaces result)
        has-default? (some #(= "default" (:name %)) items)
        items (if has-default?
                items
                (into [{:id "default" :name "default" :created-at nil}] items))]
    (-> db
        (assoc-in [:namespaces :items] items)
        (assoc-in [:namespaces :loading?] false))))

(deftest namespaces-success-prepends-default
  (testing "default namespace is always present in the list"
    (let [api-result {:namespaces [{:id "my-ns" :name "my-ns" :created-at nil}]}
          new-db (fetch-namespaces-success-handler db/default-db
                                                   [:fetch-namespaces-success api-result])
          items (get-in new-db [:namespaces :items])]
      (is (= 2 (count items)))
      (is (= "default" (:name (first items))))
      (is (= "my-ns" (:name (second items)))))))

(deftest namespaces-success-does-not-duplicate-default
  (testing "default is not duplicated if API already returns it"
    (let [api-result {:namespaces [{:id "default" :name "default" :created-at nil}
                                   {:id "other" :name "other" :created-at nil}]}
          new-db (fetch-namespaces-success-handler db/default-db
                                                   [:fetch-namespaces-success api-result])
          items (get-in new-db [:namespaces :items])]
      (is (= 2 (count items))))))
