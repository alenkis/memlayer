(ns memlayer.dashboard.views.tokens
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [clojure.string :as str]
            [memlayer.dashboard.components.ui :as ui]))

(defn create-token-form []
  (let [token-name (r/atom "")]
    (fn []
      (let [new-token @(rf/subscribe [:tokens/new-token])]
        [ui/card {}
         [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Create Token"]
         [:div {:class "flex gap-3 items-end"}
          [ui/form-group {:label "Token name" :id "token-name"}
           [ui/input {:id          "token-name"
                      :value       @token-name
                      :placeholder "Token name (e.g., production-api)"
                      :on-change   #(reset! token-name %)}]]
          [ui/button {:on-click  (fn []
                                   (when (seq (str/trim @token-name))
                                     (rf/dispatch [:tokens/create (str/trim @token-name)])
                                     (reset! token-name "")))
                      :disabled? (empty? (str/trim @token-name))
                      :variant   :primary}
           "Create"]]
         (when new-token
           [:div {:class "mt-4"}
            [ui/alert {:type :success
                       :message [:div
                                 [:p {:class "font-medium mb-2"}
                                  "Token created! Copy it now -- it won't be shown again."]
                                 [:code {:class "block p-3 bg-green-100 dark:bg-green-900 border border-green-300 dark:border-green-700 rounded font-mono text-sm break-all select-all"}
                                  (:token new-token)]
                                 [:button {:on-click #(rf/dispatch [:tokens/dismiss-new])
                                           :class    "mt-2 text-sm text-green-700 dark:text-green-300 hover:underline cursor-pointer"}
                                  "Dismiss"]]}]])]))))

(defn format-date [iso-str]
  (when iso-str
    (.toLocaleDateString (js/Date. iso-str) js/undefined
                         #js {:year "numeric" :month "short" :day "numeric"})))

(defn token-row [token]
  [:li {:class "px-6 py-4 flex items-center justify-between border-b border-gray-200 dark:border-gray-700 last:border-b-0"}
   [:div
    [:div {:class "font-medium text-gray-900 dark:text-gray-100"} (:name token)]
    [:div {:class "text-sm text-gray-500 dark:text-gray-400 font-mono"} (:prefix token)]
    [:div {:class "text-xs text-gray-400 dark:text-gray-500 mt-1"}
     (str "Created " (format-date (:created-at token)))
     (when (:revoked-at token)
       [:span {:class "ml-2 text-red-600 dark:text-red-400"}
        (str "Revoked " (format-date (:revoked-at token)))])]]
   (if (:revoked-at token)
     [:span {:class "px-3 py-1.5 text-sm text-gray-400 dark:text-gray-500"} "Revoked"]
     [ui/button {:variant  :danger
                 :on-click (fn []
                             (when (js/confirm "Are you sure you want to revoke this token? This cannot be undone.")
                               (rf/dispatch [:tokens/revoke (:id token)])))}
      "Revoke"])])

(defn token-list []
  (let [tokens @(rf/subscribe [:tokens])]
    [ui/card {:class "p-0"}
     [:div {:class "px-6 py-4 border-b border-gray-200 dark:border-gray-700"}
      [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} "Your Tokens"]]
     (cond
       (:loading? tokens)
       [:div {:class "p-6 text-center text-gray-500 dark:text-gray-400"} "Loading..."]

       (empty? (:items tokens))
       [:div {:class "p-6 text-center text-gray-500 dark:text-gray-400"} "No tokens yet"]

       :else
       [:ul {:class "list-none"}
        (for [token (:items tokens)]
          ^{:key (:id token)}
          [token-row token])])]))

(defn page []
  (rf/dispatch [:tokens/fetch])
  (fn []
    [:div {:class "space-y-6"}
     [:div
      [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "API Tokens"]
      [:p {:class "text-gray-500 dark:text-gray-400 mt-1"}
       "Create and manage API tokens for programmatic access"]]
     [create-token-form]
     [token-list]]))
