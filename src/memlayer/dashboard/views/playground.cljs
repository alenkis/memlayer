(ns memlayer.dashboard.views.playground
  (:require [re-frame.core :as rf]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.layer-badge :as layer-badge]
            [memlayer.dashboard.components.loading :as loading]))

(defn- progress-bar [percentage]
  [:div {:class          "w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2.5"
         :role           "progressbar"
         :aria-valuenow  percentage
         :aria-valuemin  0
         :aria-valuemax  100
         :aria-label     "Upload progress"}
   [:div {:class "bg-indigo-600 h-2.5 rounded-full transition-all duration-300"
          :style {:width (str percentage "%")}}]])

(defn- handle-file-select [e]
  (when-let [file (-> e .-target .-files (aget 0))]
    (rf/dispatch [:file-upload/select-file file])))

(defn retain-panel []
  (let [retain @(rf/subscribe [:retain-state])
        upload @(rf/subscribe [:file-upload])
        status (:status upload)
        active? (#{:uploading :processing} status)
        file-selected? (#{:selected :uploading :processing :complete :error} status)]
    [:div {:class "space-y-4"}
     ;; Collapsible file upload section
     [:div {:class "border border-gray-200 dark:border-gray-700 rounded-md"}
      [:button {:class         "w-full flex items-center justify-between px-4 py-3 text-sm font-medium text-gray-700 dark:text-gray-300 hover:bg-gray-50 dark:hover:bg-gray-800 cursor-pointer"
                :aria-expanded (not= :closed status)
                :aria-controls "file-upload-panel"
                :on-click      #(rf/dispatch [:file-upload/toggle])}
       [:span "Upload a file"]
       [:span {:class "text-gray-400" :aria-hidden "true"} (if (not= :closed status) "\u25BE" "\u25B8")]]
      (when (not= :closed status)
        [:div {:id "file-upload-panel" :class "px-4 pb-3 space-y-2"}
         [:div {:class "flex items-center gap-4"}
          [:label {:class (str "inline-flex items-center justify-center px-4 py-2 rounded-md text-sm font-medium "
                               "bg-white text-gray-700 border border-gray-300 hover:bg-gray-50 cursor-pointer "
                               "dark:bg-gray-700 dark:text-gray-200 dark:border-gray-600 dark:hover:bg-gray-600"
                               (when active? " opacity-50 cursor-not-allowed"))}
           [:input {:type      "file"
                    :accept    ".txt,.md,.csv,.json,.xml,.html,.clj,.cljs,.edn,.py,.js,.ts,.rs,.go,.java"
                    :class     "hidden"
                    :disabled  active?
                    :on-change handle-file-select}]
           "Choose File"]
          (when (:file-name upload)
            [:div {:class "flex items-center gap-2"}
             [:span {:class "text-sm text-gray-600 dark:text-gray-400"} (:file-name upload)]
             [:button {:class      "text-gray-400 hover:text-red-500 text-sm cursor-pointer"
                       :aria-label "Remove selected file"
                       :on-click   #(rf/dispatch [:file-upload/clear])}
              "\u2715"]])]])]

     ;; Content textarea — hidden when a file is selected
     (if file-selected?
       [:div {:class "flex items-center gap-2 py-3 px-4 bg-gray-50 dark:bg-gray-900 rounded-md border border-gray-200 dark:border-gray-700"}
        [:span {:class "text-sm text-gray-600 dark:text-gray-400"}
         (str "File \"" (:file-name upload) "\" ("
              (let [kb (/ (:file-size upload) 1024)]
                (if (< kb 1024)
                  (str (.toFixed kb 0) " KB")
                  (str (.toFixed (/ kb 1024) 1) " MB")))
              ") will be ingested. Clear the file to type content manually.")]]
       [ui/form-group {:label "Content" :id "retain-content"}
        [ui/textarea {:id          "retain-content"
                      :value       (get-in retain [:request :content])
                      :placeholder "Enter information to remember..."
                      :rows        6
                      :on-change   #(rf/dispatch [:set-retain-field :content %])
                      :on-submit   #(rf/dispatch [:retain!])}]])

     ;; Source
     [ui/form-group {:label "Source" :id "retain-source"}
      [ui/input {:id          "retain-source"
                 :value       (get-in retain [:request :source])
                 :placeholder "playground"
                 :on-change   #(rf/dispatch [:set-retain-field :source %])}]]

     ;; Action buttons
     [:div {:class "flex items-center gap-4"}
      (if file-selected?
        ;; Ingest mode
        [ui/button {:on-click  #(rf/dispatch [:file-upload/ingest!])
                    :disabled? (or active? (nil? (:file-name upload)))
                    :variant   :primary}
         (case status
           :uploading  "Connecting..."
           :processing "Processing..."
           "Ingest")]
        ;; Retain mode
        [ui/button {:on-click  #(rf/dispatch [:retain!])
                    :disabled? (:loading? retain)
                    :variant   :primary}
         (if (:loading? retain) "Retaining..." "Retain")])
      (when (= :complete status)
        [ui/button {:on-click #(rf/dispatch [:file-upload/reset])
                    :variant  :secondary}
         "Reset"])
      (when (:error retain)
        [ui/alert {:type :error :message (str "Error: " (get-in retain [:error :status-text] "Unknown error"))}])]

     ;; Upload progress
     (when active?
       [:div {:class "space-y-2"}
        [progress-bar (:percentage upload)]
        [:div {:class "flex justify-between text-xs text-gray-500 dark:text-gray-400"}
         [:span (str (:percentage upload) "%")]
         (when (:chunks-retained upload)
           [:span (str (:chunks-retained upload) " chunks processed")])]])

     ;; Upload error
     (when (= :error status)
       [ui/alert {:type :error :message (str "Error: " (:error upload))}])

     ;; Upload result
     (when-let [result (:result upload)]
       [ui/card {:class "bg-gray-50 dark:bg-gray-900"}
        [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300 mb-2"} "Ingestion Complete"]
        [:div {:class "space-y-3"}
         [:div {:class "flex gap-6"}
          [:div {:class "text-sm text-gray-900 dark:text-gray-100"}
           [:span {:class "font-medium text-green-600 dark:text-green-400"} (str (:created result))]
           " created"]
          [:div {:class "text-sm text-gray-900 dark:text-gray-100"}
           [:span {:class "font-medium text-blue-600 dark:text-blue-400"} (str (:updated result))]
           " updated"]
          [:div {:class "text-sm text-gray-900 dark:text-gray-100"}
           [:span {:class "font-medium"} (str (:total-chunks result))]
           " total chunks"]]
         (when (seq (:memory-ids result))
           [:button {:class    "inline-flex items-center gap-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 cursor-pointer"
                     :on-click #(rf/dispatch [:graph/navigate-with-highlights (:memory-ids result)])}
            "View in Graph \u2192"])]])

     ;; Retain result
     (when (and (not file-selected?) (not (#{:uploading :processing :complete} status)))
       (when-let [response (:response retain)]
         [ui/card {:class "bg-gray-50 dark:bg-gray-900"}
          [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300 mb-2"} "Result"]
          [:div {:class "space-y-2"}
           (when-let [decisions (:decisions response)]
             [:div
              [:span {:class "text-sm font-medium text-gray-900 dark:text-gray-100"} "Decisions:"]
              [:ul {:class "mt-1 space-y-1"}
               (for [[i d] (map-indexed vector decisions)]
                 ^{:key i}
                 [:li {:class "text-sm"}
                  [:span {:class (str "font-medium "
                                      (case (:type d)
                                        "CREATE" "text-green-600 dark:text-green-400"
                                        "UPDATE" "text-blue-600 dark:text-blue-400"
                                        "FORGET" "text-red-600 dark:text-red-400"
                                        "NOOP"   "text-gray-500 dark:text-gray-400"
                                        ""))}
                   (:type d)]
                  " - "
                  [:span {:class "text-gray-600 dark:text-gray-400"} (:content d)]])]])
           [:div {:class "flex items-center gap-4 mt-2"}
            (when (seq (:memory-ids response))
              [:button {:class    "inline-flex items-center gap-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 cursor-pointer"
                        :on-click #(rf/dispatch [:graph/navigate-with-highlights (:memory-ids response)])}
               "View in Graph \u2192"])
            (when (:operation-id response)
              [:button {:class    "inline-flex items-center gap-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300 cursor-pointer"
                        :on-click #(rf/dispatch [:pipeline/navigate-to-operation (:operation-id response)])}
               "View in Pipeline \u2192"])]]]))]))

(defn recall-panel []
  (let [recall @(rf/subscribe [:recall-state])]
    [:div {:class "space-y-4"}
     [:div {:class "flex gap-4 items-end"}
      [:div {:class "flex-1"}
       [ui/form-group {:label "Query" :id "recall-query"}
        [ui/input {:id          "recall-query"
                   :value       (get-in recall [:params :query])
                   :placeholder "What would you like to recall?"
                   :on-change   #(rf/dispatch [:set-recall-field :query %])}]]]
      [:div {:class "w-24"}
       [ui/form-group {:label "Limit" :id "recall-limit"}
        [ui/input {:id        "recall-limit"
                   :value     (str (get-in recall [:params :limit]))
                   :type      "number"
                   :on-change #(rf/dispatch [:set-recall-field :limit (js/parseInt % 10)])}]]]
      [ui/button {:on-click  #(rf/dispatch [:recall!])
                  :disabled? (:loading? recall)
                  :variant   :primary}
       (if (:loading? recall) "Recalling..." "Recall")]]
     (when (:error recall)
       [ui/alert {:type :error :message (str "Error: " (get-in recall [:error :status-text] "Unknown error"))}])
     (when-let [results (:results recall)]
       [:div {:class "space-y-4"}
        [:div {:class "flex items-center justify-between"}
         [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300"}
          (str "Results (" (:count results) " memories)")]
         [:span {:class "text-xs text-gray-500 dark:text-gray-400"}
          (str "Query: \"" (:query results) "\"")]]
        (if (seq (:memories results))
          [:div {:class "space-y-2"}
           (for [memory (:memories results)]
             ^{:key (:memory-id memory)}
             [ui/card {:class "p-4"}
              [:div {:class "flex items-start justify-between"}
               [:div {:class "flex-1"}
                [:p {:class "text-sm text-gray-900 dark:text-gray-100"} (:content memory)]
                [:div {:class "flex gap-3 mt-2"}
                 [layer-badge/layer-badge (:layer memory)]
                 [:span {:class "text-xs text-gray-500 dark:text-gray-400"}
                  (str "distance: " (.toFixed (or (:distance memory) 0) 4))]]]]])]
          [loading/empty-state "No matching memories found"])])]))

(defn tab-button [tab label current-tab]
  [:button {:on-click      #(rf/dispatch [:set-playground-tab tab])
            :role          "tab"
            :aria-selected (= tab current-tab)
            :class         (str "px-4 py-2 text-sm font-medium rounded-t-md cursor-pointer "
                                (if (= tab current-tab)
                                  "bg-white dark:bg-gray-800 text-indigo-600 dark:text-indigo-400 border-b-2 border-indigo-600 dark:border-indigo-400"
                                  "text-gray-500 dark:text-gray-400 hover:text-gray-700 dark:hover:text-gray-300"))}
   label])

(defn page []
  (fn []
    (let [active-tab @(rf/subscribe [:playground-tab])]
      [:div {:class "space-y-6"}
       [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Playground"]
       [:div {:class "border-b border-gray-200 dark:border-gray-700"}
        [:div {:class "flex gap-2" :role "tablist" :aria-label "Playground mode"}
         [tab-button :retain "Retain" active-tab]
         [tab-button :recall "Recall" active-tab]]]
       [ui/card {}
        [:div {:role "tabpanel"}
         (case active-tab
           :retain [retain-panel]
           :recall [recall-panel]
           [retain-panel])]]])))
