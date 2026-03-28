(ns memlayer.dashboard.views.settings
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [memlayer.dashboard.components.ui :as ui]))

(defn general-tab []
  (let [groq-key  (r/atom "")
        openai-key (r/atom "")]
    (fn []
      (let [settings  @(rf/subscribe [:settings])
            data      (:data settings)
            user      @(rf/subscribe [:auth/user])]
        (if (:loading? settings)
          [:div {:class "p-6 text-gray-500 dark:text-gray-400"} "Loading..."]
          [:div {:class "space-y-6 max-w-2xl"}
           ;; Profile
           [ui/card {}
            [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Profile"]
            [:dl
             [:dt {:class "text-sm font-medium text-gray-500 dark:text-gray-400 mb-1"} "Email"]
             [:dd {:class "text-gray-900 dark:text-gray-100"} (or (:email user) (:email data) "Not set")]]]

           ;; LLM Provider Keys
           [ui/card {}
            [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "LLM Provider Keys"]
            [:p {:class "text-sm text-gray-500 dark:text-gray-400 mb-4"}
             "Provide your own API keys to use memlayer. Keys are stored securely and never displayed after saving."]
            [:div {:class "space-y-4"}
             ;; Groq
             [:div
              [:div {:class "flex items-center justify-between mb-1"}
               [:label {:html-for "groq-key"
                        :class    "block text-sm font-medium text-gray-700 dark:text-gray-300"} "Groq API Key"]
               [:span {:class (str "text-xs px-2 py-0.5 rounded-full font-medium "
                                   (if (:has-groq-key data)
                                     "bg-green-100 text-green-800 dark:bg-green-900/50 dark:text-green-300"
                                     "bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400"))}
                (if (:has-groq-key data) "Configured" "Not set")]]
              [:div {:class "flex gap-2"}
               [ui/input {:id          "groq-key"
                          :type        "password"
                          :value       @groq-key
                          :placeholder (if (:has-groq-key data) "••••••••••••" "gsk_...")
                          :on-change   #(reset! groq-key %)}]
               [ui/button {:on-click  (fn []
                                        (when (seq @groq-key)
                                          (rf/dispatch [:settings/save-keys {:groq-api-key @groq-key}])
                                          (reset! groq-key "")))
                           :disabled? (empty? @groq-key)
                           :variant   :primary}
                "Save"]]]
             ;; OpenAI
             [:div
              [:div {:class "flex items-center justify-between mb-1"}
               [:label {:html-for "openai-key"
                        :class    "block text-sm font-medium text-gray-700 dark:text-gray-300"} "OpenAI API Key"]
               [:span {:class (str "text-xs px-2 py-0.5 rounded-full font-medium "
                                   (if (:has-openai-key data)
                                     "bg-green-100 text-green-800 dark:bg-green-900/50 dark:text-green-300"
                                     "bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400"))}
                (if (:has-openai-key data) "Configured" "Not set")]]
              [:div {:class "flex gap-2"}
               [ui/input {:id          "openai-key"
                          :type        "password"
                          :value       @openai-key
                          :placeholder (if (:has-openai-key data) "••••••••••••" "sk-...")
                          :on-change   #(reset! openai-key %)}]
               [ui/button {:on-click  (fn []
                                        (when (seq @openai-key)
                                          (rf/dispatch [:settings/save-keys {:openai-api-key @openai-key}])
                                          (reset! openai-key "")))
                           :disabled? (empty? @openai-key)
                           :variant   :primary}
                "Save"]]]
             ;; Delete all
             [:div {:class "pt-2"}
              [ui/button {:on-click #(when (js/confirm "Remove all LLM provider keys?")
                                       (rf/dispatch [:settings/delete-keys]))
                          :variant  :danger}
               "Remove All Keys"]]]]])))))

(defn page []
  (rf/dispatch [:settings/fetch])
  (fn []
    [:div {:class "space-y-6"}
     [:div
      [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Settings"]
      [:p {:class "text-gray-500 dark:text-gray-400 mt-1"} "Manage your profile and LLM provider keys"]]
     [general-tab]]))
