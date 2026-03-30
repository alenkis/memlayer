(ns memlayer.dashboard.views.browser
  (:require [re-frame.core :as rf]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.layer-badge :as layer-badge]
            [memlayer.dashboard.components.loading :as loading]))

(defn search-bar []
  (let [memories @(rf/subscribe [:memories])
        params (:params memories)]
    [:div {:class "flex gap-4 items-end"}
     [ui/form-group {:label "Search" :id "browser-search"}
      [ui/input {:id          "browser-search"
                 :value       (:query params)
                 :placeholder "Search memories semantically..."
                 :on-change   #(rf/dispatch [:set-memory-params {:query %}])}]]
     [:div {:class "w-40"}
      [ui/form-group {:label "Layer" :id "browser-layer"}
       [ui/select {:id          "browser-layer"
                   :value       (when (:layer params) (name (:layer params)))
                   :placeholder "All layers"
                   :on-change   #(rf/dispatch [:set-memory-params
                                               {:layer (when (seq %) (keyword "layer" %))}])
                   :options     [{:value "domain"  :label "Domain"}
                                 {:value "concept" :label "Concept"}
                                 {:value "fact"    :label "Fact"}
                                 {:value "episode" :label "Episode"}]}]]]
     [ui/button {:on-click #(rf/dispatch [:fetch-memories])
                 :variant  :primary}
      "Search"]]))

(defn- open-memory! [memory]
  (rf/dispatch [:fetch-memory (:id memory)])
  (rf/dispatch [:fetch-memory-relationships (:id memory)]))

(defn memory-row [memory]
  [:tr {:class       "hover:bg-gray-50 dark:hover:bg-gray-700/50 cursor-pointer"
        :tab-index   0
        :role        "link"
        :on-click    #(open-memory! memory)
        :on-key-down (fn [e]
                       (when (contains? #{"Enter" " "} (.-key e))
                         (.preventDefault e)
                         (open-memory! memory)))}
   [:td {:class "px-4 py-3 text-sm text-gray-900 dark:text-gray-100 max-w-md truncate"}
    (:content memory)]
   [:td {:class "px-4 py-3"}
    [layer-badge/layer-badge (:layer memory)]]
   [:td {:class "px-4 py-3 text-sm text-gray-500 dark:text-gray-400"}
    (:source memory)]])

(defn memory-table []
  (let [memories @(rf/subscribe [:memories])]
    (cond
      (:loading? memories) [loading/spinner]
      (empty? (:items memories)) [loading/empty-state "No memories found"]
      :else
      [:div {:class "overflow-x-auto"}
       [:table {:class "min-w-full divide-y divide-gray-200 dark:divide-gray-700"}
        [:thead {:class "bg-gray-50 dark:bg-gray-800/50"}
         [:tr
          [:th {:scope "col" :class "px-4 py-3 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Content"]
          [:th {:scope "col" :class "px-4 py-3 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Layer"]
          [:th {:scope "col" :class "px-4 py-3 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Source"]]]
        [:tbody {:class "bg-white dark:bg-gray-800 divide-y divide-gray-200 dark:divide-gray-700"}
         (for [memory (:items memories)]
           ^{:key (:id memory)}
           [memory-row memory])]]])))

(defn memory-detail-modal []
  (let [memory @(rf/subscribe [:selected-memory])]
    [ui/modal {:open?    (some? memory)
               :on-close #(rf/dispatch [:close-memory-detail])
               :title    "Memory Detail"}
     (when memory
       [:div {:class "space-y-4"}
        [:dl
         [:div {:class "mb-3"}
          [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "Content"]
          [:dd {:class "mt-1 text-gray-900 dark:text-gray-100"} (:content memory)]]
         [:div {:class "grid grid-cols-2 gap-4"}
          [:div
           [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "Layer"]
           [:dd {:class "mt-1"} [layer-badge/layer-badge (:layer memory)]]]
          [:div
           [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "Source"]
           [:dd {:class "mt-1 text-gray-900 dark:text-gray-100"} (:source memory)]]
          [:div
           [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "Namespace"]
           [:dd {:class "mt-1 text-gray-900 dark:text-gray-100"} (or (:namespace memory) "default")]]
          [:div
           [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "ID"]
           [:dd {:class "mt-1 text-xs font-mono text-gray-500 dark:text-gray-400"} (:id memory)]]]]
        (when-let [rels (:relationships memory)]
          [:div
           [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "Relationships"]
           (if (seq rels)
             [:dd
              [:ul {:class "mt-1 space-y-1"}
               (for [rel rels]
                 ^{:key (:id rel)}
                 [:li {:class "text-sm text-gray-600 dark:text-gray-400"}
                  (str (name (:type rel)) " -> " (:target-id rel))])]]
             [:dd {:class "mt-1 text-sm text-gray-500 dark:text-gray-400"} "No relationships"])])])]))

(defn page []
  (rf/dispatch [:fetch-memories])
  (fn []
    [:div {:class "space-y-6"}
     [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Memory Browser"]
     [ui/card {}
      [search-bar]]
     [ui/card {:class "p-0"}
      [memory-table]]
     [memory-detail-modal]]))
