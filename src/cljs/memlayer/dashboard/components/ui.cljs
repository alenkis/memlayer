(ns memlayer.dashboard.components.ui
  (:require [reagent.core :as r]))

(defn- gen-id [prefix]
  (str prefix "-" (random-uuid)))

;; ---------------------------------------------------------------------------
;; Form group
;; ---------------------------------------------------------------------------

(defn form-group
  "Wraps a label and form control with automatic html-for/id association.
   Usage: [form-group {:label \"Email\" :id \"email\"}
            [input {:id \"email\" :value v :on-change f}]]"
  [{:keys [label id class required? help-text error]} & children]
  (let [ctrl-id  (or id (gen-id "field"))
        help-id  (when help-text (str ctrl-id "-help"))
        error-id (when error (str ctrl-id "-error"))]
    (into
     [:div {:class (str "space-y-1 " class)}
      [:label {:html-for ctrl-id
               :class    "block text-sm font-medium text-gray-700 dark:text-gray-300"}
       label
       (when required?
         [:span {:class "text-red-500 ml-0.5" :aria-hidden "true"} "*"])]]
     (concat
      children
      [(when help-text
         [:p {:id help-id :class "text-xs text-gray-500 dark:text-gray-400"} help-text])
       (when error
         [:p {:id error-id :class "text-xs text-red-600 dark:text-red-400" :role "alert"} error])]))))

;; ---------------------------------------------------------------------------
;; Primitives
;; ---------------------------------------------------------------------------

(defn card [{:keys [class]} & children]
  (into [:div {:class (str "bg-white dark:bg-gray-800 rounded-lg shadow-sm border border-gray-200 dark:border-gray-700 p-6 " class)}]
        children))

(defn button [{:keys [on-click class disabled? variant aria-label type]
               :or   {variant :primary type "button"}} & children]
  (let [base "inline-flex items-center justify-center px-4 py-2 rounded-md text-sm font-medium transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-offset-2"
        variants {:primary   "bg-indigo-600 text-white hover:bg-indigo-700 focus:ring-indigo-500 dark:bg-indigo-500 dark:hover:bg-indigo-600"
                  :secondary "bg-white text-gray-700 border border-gray-300 hover:bg-gray-50 focus:ring-indigo-500 dark:bg-gray-700 dark:text-gray-200 dark:border-gray-600 dark:hover:bg-gray-600"
                  :danger    "bg-red-600 text-white hover:bg-red-700 focus:ring-red-500 dark:bg-red-500 dark:hover:bg-red-600"
                  :ghost     "text-gray-600 hover:bg-gray-100 dark:text-gray-300 dark:hover:bg-gray-700"}
        disabled-cls "opacity-50 cursor-not-allowed"]
    (into [:button (cond-> {:type     type
                            :on-click on-click
                            :disabled disabled?
                            :class    (str base " " (get variants variant) " "
                                           (when disabled? disabled-cls) " " class)}
                     aria-label (assoc :aria-label aria-label))]
          children)))

(defn input [_props]
  (let [stable-id (gen-id "input")]
    (fn [{:keys [value on-change placeholder class type id aria-label aria-describedby disabled?]
          :or   {type "text"}}]
      [:input (cond-> {:type        type
                       :id          (or id stable-id)
                       :value       (or value "")
                       :on-change   (fn [e] (when on-change (on-change (.. e -target -value))))
                       :placeholder placeholder
                       :disabled    disabled?
                       :class       (str "block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm px-3 py-2 border bg-white dark:bg-gray-700 dark:text-gray-100 dark:placeholder-gray-400 " class)}
                aria-label       (assoc :aria-label aria-label)
                aria-describedby (assoc :aria-describedby aria-describedby))])))

(defn textarea [_props]
  (let [stable-id (gen-id "textarea")]
    (fn [{:keys [value on-change on-submit placeholder class rows id aria-label aria-describedby]
          :or   {rows 4}}]
      [:textarea (cond-> {:id          (or id stable-id)
                          :value       (or value "")
                          :on-change   (fn [e] (when on-change (on-change (.. e -target -value))))
                          :placeholder placeholder
                          :rows        rows
                          :class       (str "block w-full rounded-md border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm px-3 py-2 border bg-white dark:bg-gray-700 dark:text-gray-100 dark:placeholder-gray-400 " class)}
                   aria-label       (assoc :aria-label aria-label)
                   aria-describedby (assoc :aria-describedby aria-describedby)
                   on-submit        (assoc :on-key-down
                                           (fn [e]
                                             (when (and (= (.-key e) "Enter")
                                                        (or (.-metaKey e) (.-ctrlKey e)))
                                               (.preventDefault e)
                                               (on-submit)))))])))

(defn select [_props]
  (let [stable-id (gen-id "select")]
    (fn [{:keys [value on-change options class placeholder aria-label id]}]
      [:div {:class (str "relative " class)}
       [:select (cond-> {:id        (or id stable-id)
                         :value     (or value "")
                         :on-change (fn [e] (when on-change (on-change (.. e -target -value))))
                         :class     "block w-full appearance-none rounded-md border border-gray-300 dark:border-gray-600 shadow-sm focus:border-indigo-500 focus:ring-indigo-500 sm:text-sm pl-3 pr-9 py-2 bg-white dark:bg-gray-700 dark:text-gray-100 dark:placeholder-gray-400 cursor-pointer"}
                  aria-label (assoc :aria-label aria-label))
        (when placeholder
          [:option {:value ""} placeholder])
        (for [{:keys [value label]} options]
          ^{:key value}
          [:option {:value value} label])]
       [:div {:class "pointer-events-none absolute inset-y-0 right-0 flex items-center pr-3.5"}
        [:svg {:class "h-4 w-4 text-gray-400" :viewBox "0 0 20 20" :fill "currentColor"
               :aria-hidden "true"}
         [:path {:fill-rule "evenodd" :clip-rule "evenodd"
                 :d "M5.23 7.21a.75.75 0 011.06.02L10 11.168l3.71-3.938a.75.75 0 111.08 1.04l-4.25 4.5a.75.75 0 01-1.08 0l-4.25-4.5a.75.75 0 01.02-1.06z"}]]]])))

;; ---------------------------------------------------------------------------
;; Modal with focus trap
;; ---------------------------------------------------------------------------

(def ^:private focusable-selector
  "a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex=\"-1\"])")

(defn- trap-focus! [container-el e]
  (when container-el
    (let [focusable (.querySelectorAll container-el focusable-selector)
          n         (.-length focusable)]
      (when (pos? n)
        (let [first-el (aget focusable 0)
              last-el  (aget focusable (dec n))]
          (cond
            (and (.-shiftKey e) (= (.-activeElement js/document) first-el))
            (do (.preventDefault e) (.focus last-el))

            (and (not (.-shiftKey e)) (= (.-activeElement js/document) last-el))
            (do (.preventDefault e) (.focus first-el))))))))

(defn modal [_props & _children]
  (let [dialog-ref    (atom nil)
        prev-focus    (atom nil)
        title-id      (gen-id "modal-title")]
    (r/create-class
     {:display-name "ui/modal"

      :component-did-update
      (fn [this [_ prev-props]]
        (let [[_ curr-props] (r/argv this)]
          (when (and (:open? curr-props) (not (:open? prev-props)))
            (reset! prev-focus (.-activeElement js/document))
            (js/setTimeout
             (fn []
               (when-let [el @dialog-ref]
                 (when-let [focusable (.querySelector el focusable-selector)]
                   (.focus focusable))))
             50))
          (when (and (not (:open? curr-props)) (:open? prev-props))
            (when-let [el @prev-focus]
              (.focus el)))))

      :component-did-mount
      (fn [this]
        (let [[_ props] (r/argv this)]
          (when (:open? props)
            (reset! prev-focus (.-activeElement js/document))
            (js/setTimeout
             (fn []
               (when-let [el @dialog-ref]
                 (when-let [focusable (.querySelector el focusable-selector)]
                   (.focus focusable))))
             50))))

      :component-will-unmount
      (fn [_]
        (when-let [el @prev-focus]
          (.focus el)))

      :reagent-render
      (fn [{:keys [open? on-close title]} & children]
        (when open?
          [:div {:class      "fixed inset-0 z-50 overflow-y-auto"
                 :on-key-down (fn [e]
                                (case (.-key e)
                                  "Escape" (on-close)
                                  "Tab"    (trap-focus! @dialog-ref e)
                                  nil))}
           [:div {:class "flex min-h-screen items-center justify-center p-4"}
            [:div {:class    "fixed inset-0 bg-black bg-opacity-25 dark:bg-opacity-50 transition-opacity"
                   :on-click on-close
                   :aria-hidden "true"}]
            [:div {:ref            #(reset! dialog-ref %)
                   :role           "dialog"
                   :aria-modal     "true"
                   :aria-labelledby title-id
                   :class          "relative bg-white dark:bg-gray-800 rounded-lg shadow-xl max-w-2xl w-full p-6"}
             [:div {:class "flex items-center justify-between mb-4"}
              [:h3 {:id    title-id
                    :class "text-lg font-semibold text-gray-900 dark:text-gray-100"} title]
              [:button {:on-click   on-close
                        :aria-label "Close"
                        :class      "text-gray-400 hover:text-gray-600 dark:hover:text-gray-300"}
               "\u00D7"]]
             (into [:div] children)]]]))})))

;; ---------------------------------------------------------------------------
;; Feedback
;; ---------------------------------------------------------------------------

(defn alert [{:keys [type message]}]
  (let [styles {:error   "bg-red-50 border-red-200 text-red-800 dark:bg-red-900/30 dark:border-red-800 dark:text-red-300"
                :success "bg-green-50 border-green-200 text-green-800 dark:bg-green-900/30 dark:border-green-800 dark:text-green-300"
                :info    "bg-blue-50 border-blue-200 text-blue-800 dark:bg-blue-900/30 dark:border-blue-800 dark:text-blue-300"
                :warning "bg-yellow-50 border-yellow-200 text-yellow-800 dark:bg-yellow-900/30 dark:border-yellow-800 dark:text-yellow-300"}]
    [:div {:role      "alert"
           :aria-live (if (= type :error) "assertive" "polite")
           :class     (str "rounded-md border p-4 text-sm " (get styles type (:info styles)))}
     message]))

(defn badge [{:keys [class]} & children]
  (into [:span {:class (str "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium " class)}]
        children))

(defn loading-spinner []
  [:div {:class "flex items-center justify-center p-8"
         :role  "status"}
   [:div {:class "animate-spin rounded-full h-8 w-8 border-b-2 border-indigo-600"}]
   [:span {:class "sr-only"} "Loading..."]])

(defn copy-button
  "Read-only input with a copy-to-clipboard button."
  [_props]
  (let [copied? (r/atom false)]
    (fn [{:keys [value class label]}]
      [:div {:class (str "flex items-center gap-2 " class)}
       [:input {:type       "text"
                :value      (or value "")
                :read-only  true
                :aria-label (or label "Copyable value")
                :class      "flex-1 font-mono text-sm px-3 py-2 rounded-md border border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-gray-100 select-all"}]
       [:button {:on-click   (fn []
                               (-> (js/navigator.clipboard.writeText (or value ""))
                                   (.then (fn []
                                            (reset! copied? true)
                                            (js/setTimeout #(reset! copied? false) 2000)))))
                 :aria-label "Copy to clipboard"
                 :class      "inline-flex items-center px-3 py-2 rounded-md text-sm font-medium border transition-colors cursor-pointer focus:outline-none focus:ring-2 focus:ring-offset-2 focus:ring-indigo-500 bg-white dark:bg-gray-700 border-gray-300 dark:border-gray-600 text-gray-700 dark:text-gray-200 hover:bg-gray-50 dark:hover:bg-gray-600"}
        (if @copied? "Copied!" "Copy")]])))

;; ---------------------------------------------------------------------------
;; Error boundary
;; ---------------------------------------------------------------------------

(defn error-boundary
  "React error boundary. Catches render errors in children and shows fallback.
   Usage: [error-boundary {:fallback [:p \"Something went wrong\"]} [child]]"
  [{:keys [_fallback]} & _children]
  (let [error (r/atom nil)]
    (r/create-class
     {:display-name "error-boundary"

      :get-derived-state-from-error
      (fn [e]
        (reset! error e)
        #js {})

      :component-did-catch
      (fn [_this e _info]
        (js/console.error "Error boundary caught:" e))

      :reagent-render
      (fn [{:keys [fallback]} & children]
        (if @error
          (or fallback
              [:div {:class "text-center py-8 text-gray-500 dark:text-gray-400"
                     :role  "alert"}
               [:p "Something went wrong rendering this component."]])
          (into [:<>] children)))})))
