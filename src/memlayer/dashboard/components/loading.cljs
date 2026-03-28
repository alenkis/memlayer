(ns memlayer.dashboard.components.loading)

(defn spinner []
  [:div {:class "flex items-center justify-center p-8"
         :role  "status"}
   [:div {:class "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"}]
   [:span {:class "sr-only"} "Loading..."]])

(defn empty-state
  ([] (empty-state "No data available"))
  ([message]
   [:div {:class "flex flex-col items-center justify-center p-12 text-gray-500"}
    [:p {:class "text-lg"} message]]))
