(ns memlayer.dashboard.views.usage
  (:require [re-frame.core :as rf]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.chart :as chart]
            [memlayer.dashboard.components.loading :as loading]))

(def ranges
  [{:value "7d"  :label "7 days"}
   {:value "30d" :label "30 days"}
   {:value "90d" :label "90 days"}])

(def provider-colors
  {"groq"   "#f97316"
   "openai" "#3b82f6"
   "Groq"   "#f97316"
   "OpenAI" "#3b82f6"})

(defn format-tokens [n]
  (cond
    (>= n 1000000) (str (.toFixed (/ n 1000000) 1) "M")
    (>= n 1000)    (str (.toFixed (/ n 1000) 1) "K")
    :else          (str n)))

(defn pivot-timeseries
  "Pivot timeseries rows into chart-friendly format:
   [{:date \"2026-02-15\" :Groq 4500 :OpenAI 1200} ...]"
  [points]
  (let [by-date (reduce
                 (fn [acc p]
                   (let [date-str (subs (:date p) 0 10)]
                     (update acc date-str
                             (fn [row]
                               (let [row (or row {:date date-str})]
                                 (update row (keyword (:provider p))
                                         (fnil + 0) (:total-tokens p)))))))
                 {} points)]
    (sort-by :date (vals by-date))))

(defn range-selector []
  (let [current-range @(rf/subscribe [:usage/range])]
    [:div {:class "flex gap-1 bg-gray-100 dark:bg-gray-800 rounded-lg p-1"}
     (for [{:keys [value label]} ranges]
       ^{:key value}
       [:button {:on-click     #(rf/dispatch [:usage/set-range value])
                 :aria-pressed (= value current-range)
                 :class        (str "px-3 py-1 rounded-md text-sm font-medium transition-colors cursor-pointer "
                                    (if (= value current-range)
                                      "bg-white dark:bg-gray-700 text-gray-900 dark:text-gray-100 shadow-sm"
                                      "text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-200"))}
        label])]))

(defn stats-card [title value]
  [ui/card {}
   [:h3 {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} title]
   [:p {:class "mt-2 text-3xl font-semibold text-gray-900 dark:text-gray-100"} value]])

(defn usage-chart [chart-data providers]
  [ui/card {}
   [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Daily Usage"]
   (if (seq chart-data)
     [ui/error-boundary
      {:fallback [:p {:class "text-gray-500 dark:text-gray-400 text-center py-8"}
                  "Chart failed to render."]}
      [chart/bar-chart {:data   chart-data
                        :x-key  :date
                        :bars   (mapv (fn [p]
                                        {:key   (keyword p)
                                         :color (get provider-colors p "#6b7280")
                                         :label p})
                                      providers)
                        :height 300}]]
     [:p {:class "text-gray-500 dark:text-gray-400 text-center py-8"}
      "No usage data for this period."])])

(defn breakdown-table [by-operation]
  (when (seq by-operation)
    [ui/card {}
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Breakdown"]
     [:div {:class "overflow-x-auto"}
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "border-b border-gray-200 dark:border-gray-700"}
         [:th {:scope "col" :class "text-left px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Operation"]
         [:th {:scope "col" :class "text-left px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Provider"]
         [:th {:scope "col" :class "text-left px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Step"]
         [:th {:scope "col" :class "text-right px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Tokens"]
         [:th {:scope "col" :class "text-right px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Calls"]]]
       [:tbody
        (for [[i row] (map-indexed vector by-operation)]
          ^{:key i}
          [:tr {:class "border-b border-gray-200 dark:border-gray-700 last:border-0"}
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100"} (:operation row)]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100"} (:provider row)]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100"} (:step row)]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100 text-right font-mono"} (format-tokens (:total-tokens row))]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100 text-right font-mono"} (:call-count row)]])]]]]))

(defn format-cost [n]
  (if (< n 0.01)
    "<$0.01"
    (str "$" (.toFixed n 2))))

(defn namespace-table [by-namespace]
  (when (seq by-namespace)
    [ui/card {}
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-4"} "Tokens by Namespace"]
     [:div {:class "overflow-x-auto"}
      [:table {:class "w-full text-sm"}
       [:thead
        [:tr {:class "border-b border-gray-200 dark:border-gray-700"}
         [:th {:scope "col" :class "text-left px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Namespace"]
         [:th {:scope "col" :class "text-right px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Tokens"]
         [:th {:scope "col" :class "text-right px-4 py-3 text-gray-500 dark:text-gray-400 font-medium"} "Cost"]]]
       [:tbody
        (for [[i row] (map-indexed vector by-namespace)]
          ^{:key i}
          [:tr {:class "border-b border-gray-200 dark:border-gray-700 last:border-0"}
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100"} (:namespace row)]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100 text-right font-mono"} (format-tokens (:total-tokens row))]
           [:td {:class "px-4 py-3 text-gray-900 dark:text-gray-100 text-right font-mono"} (format-cost (or (:cost row) 0))]])]]]]))

(defn page []
  (rf/dispatch [:usage/fetch])
  (fn []
    (let [usage @(rf/subscribe [:usage])]
      [:div {:class "space-y-6"}
       [:div {:class "flex items-center justify-between"}
        [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Token Usage"]
        [range-selector]]
       (cond
         (:loading? usage)
         [loading/spinner]

         (:error usage)
         [ui/alert {:type :error :message "Failed to load usage data."}]

         (:data usage)
         (let [{:keys [summary timeseries by-namespace]} (:data usage)
               chart-data (pivot-timeseries (or timeseries []))
               providers  (vec (distinct (map :provider (or timeseries []))))]
           [:div {:class "space-y-6"}
            ;; Stats cards
            [:div {:class "grid grid-cols-1 md:grid-cols-4 gap-4"}
             [stats-card "Total Tokens" (format-tokens (or (:total-tokens summary) 0))]
             [stats-card "Est. Cost" (format-cost (or (:total-cost summary) 0))]
             (for [p (:by-provider summary)]
               ^{:key (:provider p)}
               [stats-card (str (:provider p) " Tokens") (format-tokens (or (:total-tokens p) 0))])]
            ;; Chart
            [usage-chart chart-data providers]
            ;; Tokens by namespace
            [namespace-table by-namespace]
            ;; Breakdown table
            [breakdown-table (:by-operation summary)]])

         :else
         [ui/alert {:type    :info
                    :message "No usage data available yet."}])])))
