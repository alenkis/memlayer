(ns memlayer.dashboard.views.layout
  (:require [re-frame.core :as rf]
            [reitit.frontend.easy :as rfe]
            [memlayer.dashboard.routes :as routes]
            [memlayer.dashboard.views.dashboard :as dashboard]
            [memlayer.dashboard.views.browser :as browser]
            [memlayer.dashboard.views.graph :as graph]
            [memlayer.dashboard.views.playground :as playground]
            [memlayer.dashboard.views.usage :as usage]
            [memlayer.dashboard.views.namespaces :as namespaces]
            [memlayer.dashboard.views.settings :as settings]
            [memlayer.dashboard.views.login :as login]
            [memlayer.dashboard.views.pipeline :as pipeline]
            [memlayer.dashboard.components.ui :as ui]))

(def nav-items
  [{:name :dashboard   :label "Dashboard"   :icon "M3 12l2-2m0 0l7-7 7 7M5 10v10a1 1 0 001 1h3m10-11l2 2m-2-2v10a1 1 0 01-1 1h-3m-6 0a1 1 0 001-1v-4a1 1 0 011-1h2a1 1 0 011 1v4a1 1 0 001 1m-6 0h6"}
   {:name :browser     :label "Browser"     :icon "M21 21l-6-6m2-5a7 7 0 11-14 0 7 7 0 0114 0z"}
   {:name :graph       :label "Graph"       :icon "M13.828 10.172a4 4 0 00-5.656 0l-4 4a4 4 0 105.656 5.656l1.102-1.101m-.758-4.899a4 4 0 005.656 0l4-4a4 4 0 00-5.656-5.656l-1.1 1.1"}
   {:name :playground  :label "Playground"  :icon "M10 20l4-16m4 4l4 4-4 4M6 16l-4-4 4-4"}
   {:name :usage       :label "Usage"       :icon "M9 19v-6a2 2 0 00-2-2H5a2 2 0 00-2 2v6a2 2 0 002 2h2a2 2 0 002-2zm0 0V9a2 2 0 012-2h2a2 2 0 012 2v10m-6 0a2 2 0 002 2h2a2 2 0 002-2m0 0V5a2 2 0 012-2h2a2 2 0 012 2v14a2 2 0 01-2 2h-2a2 2 0 01-2-2z"}
   {:name :namespaces  :label "Namespaces"  :icon "M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"}
   {:name :settings    :label "Settings"    :icon "M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z M15 12a3 3 0 11-6 0 3 3 0 016 0z"}])

;; Pipeline nav icon: flow/pipeline diagram
(def ^:private pipeline-icon
  "M4 4v5h.582m15.356 2A8.001 8.001 0 004.582 9m0 0H9m11 11v-5h-.581m0 0a8.003 8.003 0 01-15.357-2m15.357 2H15")

(def page-views
  {:dashboard  dashboard/page
   :browser    browser/page
   :graph      graph/page
   :playground playground/page
   :usage      usage/page
   :namespaces namespaces/page
   :settings   settings/page
   :pipeline   pipeline/page
   :login      login/page})

(defn nav-icon [path-d]
  [:svg {:class "w-5 h-5" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
         :xmlns "http://www.w3.org/2000/svg"
         :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2" :d path-d}]])

(defn sun-icon []
  [:svg {:class "w-5 h-5" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
         :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M12 3v1m0 16v1m9-9h-1M4 12H3m15.364 6.364l-.707-.707M6.343 6.343l-.707-.707m12.728 0l-.707.707M6.343 17.657l-.707.707M16 12a4 4 0 11-8 0 4 4 0 018 0z"}]])

(defn moon-icon []
  [:svg {:class "w-5 h-5" :fill "none" :stroke "currentColor" :viewBox "0 0 24 24"
         :aria-hidden "true"}
   [:path {:stroke-linecap "round" :stroke-linejoin "round" :stroke-width "2"
           :d "M20.354 15.354A9 9 0 018.646 3.646 9.003 9.003 0 0012 21a9.003 9.003 0 008.354-5.646z"}]])

(defn theme-toggle []
  (let [theme @(rf/subscribe [:theme/current])]
    [:button {:on-click   #(rf/dispatch [:theme/toggle])
              :aria-label (if (= theme :dark) "Switch to light mode" "Switch to dark mode")
              :class      "text-gray-400 hover:text-white transition-colors p-1 rounded cursor-pointer focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500"}
     (if (= theme :dark)
       [sun-icon]
       [moon-icon])]))

(defn namespace-selector []
  (let [namespaces @(rf/subscribe [:namespaces])
        active-ns  @(rf/subscribe [:active-namespace])
        items      (:items namespaces)
        options    (mapv (fn [ns-info]
                           {:value (:name ns-info) :label (:name ns-info)})
                         items)]
    [:div {:class "flex items-center gap-2"}
     [:label {:html-for "ns-selector"
              :class    "text-sm font-medium text-gray-700 dark:text-gray-300"} "Namespace"]
     [ui/select {:id         "ns-selector"
                 :value      (or active-ns "default")
                 :on-change  #(rf/dispatch [:set-active-namespace %])
                 :options    options
                 :aria-label "Namespace"
                 :class      "w-48"}]]))

(defn user-panel []
  (let [user @(rf/subscribe [:auth/user])]
    (when user
      [:div {:class "px-3 py-4 border-t border-gray-700"}
       [:div {:class "flex items-center justify-between"}
        [:div {:class "text-sm text-gray-300 truncate max-w-[160px]"} (:email user)]
        [:button {:on-click #(rf/dispatch [:auth/sign-out])
                  :class    "text-gray-400 hover:text-white text-xs whitespace-nowrap ml-2 cursor-pointer"}
         "Sign out"]]])))

(defn- pipeline-nav-item [current-page]
  [:a {:href  (routes/href :pipeline)
       :class (str "flex items-center px-3 py-2 rounded-md text-sm font-medium transition-colors "
                   (if (= :pipeline current-page)
                     "bg-gray-800 text-white"
                     "text-gray-300 hover:bg-gray-700 hover:text-white"))}
   [nav-icon pipeline-icon]
   [:span {:class "ml-3"} "Pipeline"]])

(defn sidebar []
  (let [current-page @(rf/subscribe [:current-page])]
    [:div {:class "flex flex-col w-64 bg-gray-900 min-h-screen"}
     [:div {:class "flex items-center justify-between h-16 px-6"}
      [:h1 {:class "text-xl font-bold text-white"} "MemLayer"]
      [theme-toggle]]
     [:nav {:class "flex-1 px-3 py-4 space-y-1"
            :aria-label "Main navigation"}
      (for [{:keys [name label icon]} nav-items]
        ^{:key name}
        [:a (cond-> {:href  (routes/href name)
                     :class (str "flex items-center px-3 py-2 rounded-md text-sm font-medium transition-colors "
                                 (if (= name current-page)
                                   "bg-gray-800 text-white"
                                   "text-gray-300 hover:bg-gray-700 hover:text-white"))}
              (= name current-page) (assoc :aria-current "page"))
         [nav-icon icon]
         [:span {:class "ml-3"} label]])
      [pipeline-nav-item current-page]]
     [user-panel]]))

(defn- main-content [current-page]
  [(get page-views current-page dashboard/page)])

(defn layout []
  (let [current-page   @(rf/subscribe [:current-page])
        auth-loading?  @(rf/subscribe [:auth/loading?])
        authenticated? @(rf/subscribe [:auth/authenticated?])]
    (cond
      auth-loading?
      [:div {:class "flex items-center justify-center min-h-screen bg-gray-50 dark:bg-gray-900"}
       [ui/loading-spinner]]

      (and (not authenticated?) (not= current-page :login))
      [login/page]

      (= current-page :login)
      (if authenticated?
        (do (rfe/push-state :dashboard) nil)
        [login/page])

      :else
      [:div {:class "flex h-screen overflow-hidden bg-gray-50 dark:bg-gray-900"}
       [sidebar]
       [:div {:class "flex-1 flex flex-col min-h-0 min-w-0"}
        [:div {:class "flex items-center justify-end px-8 py-3 border-b border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 shrink-0"}
         [:div {:class (when (= current-page :usage) "invisible")}
          [namespace-selector]]]
        [:main {:class "flex-1 flex flex-col min-h-0 min-w-0 overflow-auto p-8"}
         [main-content current-page]]]])))
