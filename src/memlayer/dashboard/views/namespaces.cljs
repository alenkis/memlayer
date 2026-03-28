(ns memlayer.dashboard.views.namespaces
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [clojure.string :as str]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.loading :as loading]))

(defn format-date [iso-str]
  (when iso-str
    (.toLocaleDateString (js/Date. iso-str) js/undefined
                         #js {:year "numeric" :month "short" :day "numeric"})))

(defn namespace-row [ns-info]
  (let [renaming?    (r/atom false)
        rename-value (r/atom (:name ns-info))
        is-default?  (= "default" (:name ns-info))]
    (fn [ns-info]
      [:tr {:class "border-b border-gray-200 dark:border-gray-700 last:border-b-0 hover:bg-gray-50 dark:hover:bg-gray-700/50 transition-colors"}
       [:td {:class "px-4 py-3"}
        (if @renaming?
          [:input {:type        "text"
                   :value       @rename-value
                   :auto-focus  true
                   :aria-label  "New namespace name"
                   :on-change   (fn [e] (reset! rename-value (.. e -target -value)))
                   :on-blur     (fn []
                                  (let [trimmed (str/trim @rename-value)]
                                    (when (and (seq trimmed) (not= trimmed (:name ns-info)))
                                      (rf/dispatch [:namespaces/rename (:name ns-info) trimmed])))
                                  (reset! renaming? false))
                   :on-key-down (fn [e]
                                  (case (.-key e)
                                    "Enter"  (.blur (.-target e))
                                    "Escape" (do (reset! rename-value (:name ns-info))
                                                 (reset! renaming? false))
                                    nil))
                   :class       "px-2 py-1 text-sm border border-gray-300 dark:border-gray-600 rounded bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 focus:outline-none focus:ring-2 focus:ring-indigo-500 w-full max-w-xs"}]
          [:span {:class "text-sm font-medium text-gray-900 dark:text-gray-100"}
           (:name ns-info)])]
       [:td {:class "px-4 py-3 text-sm text-gray-500 dark:text-gray-400"}
        (format-date (:created-at ns-info))]
       [:td {:class "px-4 py-3"}
        (when-not is-default?
          [:div {:class "flex items-center gap-2 justify-end"}
           [ui/button {:variant  :ghost
                       :on-click (fn []
                                   (reset! rename-value (:name ns-info))
                                   (reset! renaming? true))}
            "Rename"]
           [ui/button {:variant  :danger
                       :on-click #(rf/dispatch [:namespaces/set-delete-target ns-info])}
            "Delete"]])]])))

(defn create-modal []
  (let [modal @(rf/subscribe [:namespaces/create-modal])]
    [ui/modal {:open?    (:open? modal)
               :on-close #(rf/dispatch [:namespaces/close-create-modal])
               :title    "Create namespace"}
     [:div {:class "space-y-4"}
      [ui/form-group {:label     "Namespace name"
                      :id        "ns-create-name"
                      :help-text "Lowercase letters, digits, and hyphens only. No leading or trailing hyphens."}
       [ui/input {:id          "ns-create-name"
                  :value       (:name modal)
                  :placeholder "my-namespace"
                  :on-change   #(rf/dispatch [:namespaces/set-create-name %])}]]
      [:div {:class "flex justify-end gap-2"}
       [ui/button {:variant  :secondary
                   :on-click #(rf/dispatch [:namespaces/close-create-modal])}
        "Cancel"]
       [ui/button {:variant   :primary
                   :on-click  #(when (seq (str/trim (:name modal)))
                                 (rf/dispatch [:namespaces/create (str/trim (:name modal))]))
                   :disabled? (empty? (str/trim (or (:name modal) "")))}
        "Create"]]]]))

(defn delete-modal []
  (let [target @(rf/subscribe [:namespaces/delete-target])]
    [ui/modal {:open?    (some? target)
               :on-close #(rf/dispatch [:namespaces/clear-delete-target])
               :title    "Delete namespace"}
     [:div {:class "space-y-4"}
      [:p {:class "text-sm text-gray-900 dark:text-gray-100"}
       "Are you sure you want to delete "
       [:strong (:name target)]
       "? All memories in this namespace will be permanently deleted. This cannot be undone."]
      [:div {:class "flex justify-end gap-2"}
       [ui/button {:variant  :secondary
                   :on-click #(rf/dispatch [:namespaces/clear-delete-target])}
        "Cancel"]
       [ui/button {:variant  :danger
                   :on-click #(rf/dispatch [:namespaces/delete (:name target)])}
        "Delete"]]]]))

(defn page []
  (rf/dispatch [:fetch-namespaces])
  (fn []
    (let [ns-data @(rf/subscribe [:namespaces])]
      [:div {:class "space-y-6"}
       [:div {:class "flex items-center justify-between"}
        [:div
         [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Namespaces"]
         [:p {:class "text-gray-500 dark:text-gray-400 mt-1"} "Manage your memory namespaces"]]
        [ui/button {:variant  :primary
                    :on-click #(rf/dispatch [:namespaces/open-create-modal])}
         "Create namespace"]]

       (cond
         (:loading? ns-data)
         [loading/spinner]

         (empty? (:items ns-data))
         [ui/card {}
          [loading/empty-state "No namespaces yet. Create one to get started."]]

         :else
         [ui/card {:class "p-0 overflow-hidden"}
          [:table {:class "w-full"}
           [:thead
            [:tr {:class "border-b border-gray-200 dark:border-gray-700 bg-gray-50 dark:bg-gray-800/50"}
             [:th {:scope "col" :class "text-left px-4 py-3 text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider"} "Name"]
             [:th {:scope "col" :class "text-left px-4 py-3 text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider"} "Created"]
             [:th {:scope "col" :class "text-right px-4 py-3 text-xs font-medium text-gray-500 dark:text-gray-400 uppercase tracking-wider"} "Actions"]]]
           [:tbody
            (for [ns-info (:items ns-data)]
              ^{:key (or (:id ns-info) (:name ns-info))}
              [namespace-row ns-info])]]])

       [create-modal]
       [delete-modal]])))
