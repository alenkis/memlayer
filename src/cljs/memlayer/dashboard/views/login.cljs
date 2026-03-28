(ns memlayer.dashboard.views.login
  (:require [re-frame.core :as rf]
            [memlayer.dashboard.components.ui :as ui]))

(defn google-icon []
  [:svg {:class "w-5 h-5" :viewBox "0 0 24 24" :aria-hidden "true"}
   [:path {:fill "#4285F4" :d "M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"}]
   [:path {:fill "#34A853" :d "M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"}]
   [:path {:fill "#FBBC05" :d "M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"}]
   [:path {:fill "#EA4335" :d "M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"}]])

(defn page []
  (let [loading?       @(rf/subscribe [:auth/loading?])
        authenticated? @(rf/subscribe [:auth/authenticated?])]
    (cond
      loading?
      [:div {:class "min-h-screen flex items-center justify-center bg-slate-100"}
       [ui/loading-spinner]]

      authenticated?
      (do (rf/dispatch [:set-route {:data {:name :dashboard}}])
          nil)

      :else
      [:div {:class "min-h-screen flex items-center justify-center bg-slate-900"}
       [:div {:class "w-full max-w-sm"}
        [:div {:class "text-center mb-8"}
         [:h1 {:class "text-3xl font-bold text-white mb-2"} "MemLayer"]
         [:p {:class "text-slate-400"} "Sign in to access the dashboard"]]
        [:div {:class "bg-slate-800 rounded-xl border border-slate-700 p-8"}
         [:button {:on-click #(rf/dispatch [:auth/sign-in])
                   :class    "w-full flex items-center justify-center gap-3 px-4 py-3 bg-white rounded-lg hover:bg-slate-100 transition-colors text-slate-800 font-medium cursor-pointer"}
          [google-icon]
          "Sign in with Google"]]]])))
