(ns memlayer.dashboard.views.pipeline
  (:require [reagent.core :as r]
            [re-frame.core :as rf]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.layer-badge :as layer-badge]
            [memlayer.dashboard.components.loading :as loading]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- time-ago [iso-string]
  (when iso-string
    (let [then (.getTime (js/Date. iso-string))
          now  (.getTime (js/Date.))
          diff (- now then)
          secs (quot diff 1000)
          mins (quot secs 60)
          hrs  (quot mins 60)]
      (cond
        (< secs 60)  (str secs "s ago")
        (< mins 60)  (str mins "m ago")
        (< hrs 24)   (str hrs "h ago")
        :else        (str (quot hrs 24) "d ago")))))

(defn- format-datetime
  "Format ISO string as local date/time with seconds."
  [iso-string]
  (when iso-string
    (let [d (js/Date. iso-string)]
      (str (.getFullYear d) "-"
           (.padStart (str (inc (.getMonth d))) 2 "0") "-"
           (.padStart (str (.getDate d)) 2 "0") " "
           (.padStart (str (.getHours d)) 2 "0") ":"
           (.padStart (str (.getMinutes d)) 2 "0") ":"
           (.padStart (str (.getSeconds d)) 2 "0")))))

(defn- format-duration [ms]
  (when ms
    (cond
      (< ms 1000)   (str ms "ms")
      (< ms 60000)  (str (.toFixed (/ ms 1000) 1) "s")
      :else          (str (.toFixed (/ ms 60000) 1) "m"))))

(defn- decision-badges
  "Render action counts as colored badges."
  [decisions]
  (when (seq decisions)
    (let [grouped (frequencies (map :type decisions))
          order   ["CREATE" "UPDATE" "DELETE" "NOOP"]]
      [:div {:class "flex flex-wrap gap-1"}
       (for [action order
             :let [n (get grouped action)]
             :when (and n (pos? n))]
         ^{:key action}
         [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium "
                             (case action
                               "CREATE" "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300"
                               "UPDATE" "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
                               "DELETE" "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300"
                               "NOOP"   "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400"
                               "bg-gray-100 text-gray-600"))}
          (str n " " action)])])))

(defn- status-badge [status]
  (let [[cls label] (case status
                      "completed" ["bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300" "Completed"]
                      "in-flight" ["bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300" "In Flight"]
                      "timeout"   ["bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-300" "Timeout"]
                      "error"     ["bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300" "Error"]
                      ["bg-gray-100 text-gray-700 dark:bg-gray-900/30 dark:text-gray-300" status])]
    [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium " cls)}
     label]))

;; ---------------------------------------------------------------------------
;; Status / activity helpers (for live DAG)
;; ---------------------------------------------------------------------------

(defn- proc-activity [status delta]
  (cond
    (#{"exit" "unreachable"} status) :error
    (= status "paused")              :paused
    (and delta (pos? delta))         :active
    :else                            :idle))

(def ^:private activity-styles
  {:active   {:dot   "bg-green-500 animate-pulse"
              :text  "text-green-600 dark:text-green-400"
              :label "Active"
              :ring  "ring-1 ring-green-300 dark:ring-green-700"}
   :ready    {:dot   "bg-green-500"
              :text  "text-green-600 dark:text-green-400"
              :label "Done"
              :ring  ""}
   :awaiting {:dot   "bg-gray-400"
              :text  "text-gray-500 dark:text-gray-400"
              :label "Awaiting"
              :ring  ""}
   :paused   {:dot   "bg-yellow-500"
              :text  "text-yellow-600 dark:text-yellow-400"
              :label "Paused"
              :ring  "ring-1 ring-yellow-300 dark:ring-yellow-700"}
   :error    {:dot   "bg-red-500"
              :text  "text-red-600 dark:text-red-400"
              :label "Error"
              :ring  "ring-1 ring-red-300 dark:ring-red-700"}})

(defn- resolve-activity
  "Refine the raw :idle activity into :ready or :awaiting based on
   whether the process has handled any messages."
  [activity proc-count]
  (if (= activity :idle)
    (if (and proc-count (pos? proc-count)) :ready :awaiting)
    activity))

;; ---------------------------------------------------------------------------
;; Live pipeline DAG (compact, used in both list and detail views)
;; ---------------------------------------------------------------------------

(defn- mini-stage [label activity]
  (let [s (get activity-styles activity (:awaiting activity-styles))]
    [:div {:class (str "flex items-center gap-1.5 px-2 py-1 rounded border border-gray-200 dark:border-gray-700 bg-white dark:bg-gray-800 "
                       (:ring s))}
     [:span {:class (str "inline-block w-1.5 h-1.5 rounded-full " (:dot s))}]
     [:span {:class "text-xs text-gray-700 dark:text-gray-300 whitespace-nowrap"} label]]))

(defn- mini-arrow []
  [:span {:class "text-gray-300 dark:text-gray-600 text-xs"} "\u2192"])

(defn- stat-cell [label value]
  [:div {:class "text-center"}
   [:div {:class "text-lg font-semibold text-gray-900 dark:text-gray-100"} value]
   [:div {:class "text-xs text-gray-500 dark:text-gray-400"} label]])

(defn- live-pipeline-bar [processes deltas operations]
  (when (seq processes)
    (let [total-processed (->> processes (map :count) (filter some?) (reduce + 0))
          completed-ops   (count (filter #(= "completed" (:status %)) operations))
          errored-ops     (count (filter #(= "error" (:status %)) operations))
          in-flight-ops   (count (filter #(= "in-flight" (:status %)) operations))
          last-op-time    (->> operations (map :started-at) (filter some?) first)]
      [:div {:class "space-y-3"}
       ;; Stats row
       [:div {:class "flex flex-wrap items-center gap-6 pb-3 border-b border-gray-100 dark:border-gray-700"}
        [stat-cell "Operations" (str (count operations))]
        [stat-cell "Completed" (str completed-ops)]
        (when (pos? in-flight-ops)
          [stat-cell "In Flight" (str in-flight-ops)])
        (when (pos? errored-ops)
          [stat-cell "Errors" [:span {:class "text-red-600 dark:text-red-400"} (str errored-ops)]])
        [stat-cell "Messages Processed" (str total-processed)]
        (when last-op-time
          [stat-cell "Last Operation" (time-ago last-op-time)])]
       ;; Pipeline stages
       [:div {:class "flex items-center gap-1 flex-wrap"}
        (doall
         (mapcat
          (fn [i proc]
            (let [delta    (get deltas (:pid proc))
                  activity (resolve-activity (proc-activity (:status proc) delta) (:count proc))]
              (if (zero? i)
                [^{:key (:pid proc)} [mini-stage (:label proc) activity]]
                [^{:key (str "arr-" i)} [mini-arrow]
                 ^{:key (:pid proc)} [mini-stage (:label proc) activity]])))
          (range) processes))]])))

;; ---------------------------------------------------------------------------
;; Operation list view
;; ---------------------------------------------------------------------------

(defn- operation-row [op]
  [:tr {:class    "hover:bg-gray-50 dark:hover:bg-gray-800/50 cursor-pointer transition-colors"
        :on-click #(rf/dispatch [:pipeline/select-operation op])}
   [:td {:class "px-4 py-3 text-xs font-mono text-gray-500 dark:text-gray-400"
         :title (:id op)}
    (when (:id op) (subs (:id op) 0 (min 8 (count (:id op)))))]
   [:td {:class "px-4 py-3 text-sm text-gray-500 dark:text-gray-400 whitespace-nowrap"}
    (format-datetime (:started-at op))]
   [:td {:class "px-4 py-3 text-sm text-gray-900 dark:text-gray-100 font-medium"}
    (:source op)]
   [:td {:class "px-4 py-3 text-sm text-gray-900 dark:text-gray-100 text-center"}
    (:item-count op)]
   [:td {:class "px-4 py-3"}
    [status-badge (:status op)]]
   [:td {:class "px-4 py-3"}
    (or (decision-badges (get-in op [:result :decisions]))
        [:span {:class "text-sm text-gray-400"} "\u2014"])]
   [:td {:class "px-4 py-3 text-sm text-gray-500 dark:text-gray-400 text-right whitespace-nowrap"}
    (or (format-duration (:duration-ms op)) "\u2014")]])

(defn- operations-table [operations]
  (if (seq operations)
    [:div {:class "overflow-x-auto"}
     [:table {:class "w-full"}
      [:thead
       [:tr {:class "border-b border-gray-200 dark:border-gray-700"}
        [:th {:class "px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "ID"]
        [:th {:class "px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Time"]
        [:th {:class "px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Source"]
        [:th {:class "px-4 py-2 text-center text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Memories"]
        [:th {:class "px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Status"]
        [:th {:class "px-4 py-2 text-left text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Decisions"]
        [:th {:class "px-4 py-2 text-right text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Duration"]]]
      [:tbody {:class "divide-y divide-gray-100 dark:divide-gray-800"}
       (for [op operations]
         ^{:key (:id op)}
         [operation-row op])]]]
    [loading/empty-state "No pipeline operations yet. Try retaining a memory from the Playground."]))

;; ---------------------------------------------------------------------------
;; Operation detail view
;; ---------------------------------------------------------------------------

(defn- detail-header [op]
  [:div {:class "space-y-4 min-w-0"}
   [:button {:class    "inline-flex items-center gap-1.5 text-sm text-gray-500 dark:text-gray-400 hover:text-gray-900 dark:hover:text-gray-100 transition-colors"
             :on-click #(rf/dispatch [:pipeline/clear-selection])}
    [:span "\u2190"]
    "Back to Operations"]
   [:div {:class "flex flex-wrap items-start justify-between gap-3"}
    [:div {:class "space-y-1 min-w-0"}
     [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 break-all"}
      (str "Operation " (:id op))]
     [:div {:class "flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-gray-500 dark:text-gray-400"}
      [:span (format-datetime (:started-at op))]
      (when (:duration-ms op)
        [:span (str "Duration: " (format-duration (:duration-ms op)))])
      [:span (str "Source: " (:source op))]
      [:span (str "Namespace: " (:namespace op))]]]
    [status-badge (:status op)]]])

;; ---------------------------------------------------------------------------
;; Stage detail modal — shows per-stage data from flow/ping :recent-ops
;; ---------------------------------------------------------------------------

(def ^:private kv-label "text-xs font-medium text-gray-500 dark:text-gray-400")
(def ^:private kv-value "text-sm text-gray-900 dark:text-gray-100 mt-0.5")

(defn- kv-row [label value]
  [:div
   [:dt {:class kv-label} label]
   [:dd {:class kv-value} value]])

(defn- token-summary [tokens]
  (when (map? tokens)
    [:div {:class "flex items-center gap-3 text-xs text-gray-600 dark:text-gray-400 mt-1"}
     (when-let [t (:total-tokens tokens)]
       [:span (str t " tokens")])
     (when-let [p (:prompt-tokens tokens)]
       [:span (str "prompt: " p)])
     (when-let [c (:completion-tokens tokens)]
       [:span (str "completion: " c)])]))

(defn- content-block [text]
  [:p {:class "text-sm text-gray-700 dark:text-gray-300 bg-gray-50 dark:bg-gray-900 rounded p-2"}
   text])

(defn- items-list [label items]
  (when (seq items)
    [:div
     [:span {:class kv-label} label]
     [:div {:class "space-y-1.5 mt-1"}
      (for [[i item] (map-indexed vector items)]
        ^{:key i}
        [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm"}
         [:p {:class "text-gray-900 dark:text-gray-100"} (:content item)]
         (when (:source item)
           [:span {:class "text-xs text-gray-500 dark:text-gray-400"} (str "source: " (:source item))])])]]))

(defn- action-badge [action]
  (when action
    [:span {:class (str "inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium "
                        (case action
                          "CREATE" "bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-300"
                          "UPDATE" "bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-300"
                          "DELETE" "bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-300"
                          "NOOP"   "bg-gray-100 text-gray-600 dark:bg-gray-800 dark:text-gray-400"
                          "bg-gray-100 text-gray-600"))}
     action]))

;; -- Per-operation renderers (one op at a time) --

(defn- op-prepare-context [op]
  [:div {:class "space-y-2"}
   (when (:rejected op)
     [:div {:class "bg-red-50 dark:bg-red-900/20 border border-red-200 dark:border-red-800 rounded-lg p-3"}
      [:span {:class "text-sm font-medium text-red-700 dark:text-red-300"}
       (str "Rejected: " (name (or (:reject-reason op) :unknown)))]])
   [:dl {:class "grid grid-cols-3 gap-x-4 gap-y-2"}
    [kv-row "Namespace" (or (:namespace op) "default")]
    [kv-row "Source" (or (:source op) "\u2014")]
    [kv-row "Context" (str (or (:context-count op) 0)
                           " / " (or (:context-limit op) "\u2014")
                           " memories used")]]
   [items-list (str "Input (" (or (:item-count op) 0) " items)") (:items op)]
   (let [ctx-mems (:context-memories op)]
     (when (seq ctx-mems)
       [:div
        [:span {:class kv-label} "Context (existing memories fed to LLM)"]
        [:div {:class "space-y-1.5 mt-1"}
         (for [[i m] (map-indexed vector ctx-mems)]
           ^{:key i}
           [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm text-gray-700 dark:text-gray-300"}
            (:content m)])]]))])

(defn- op-batch-extract [op]
  [:div {:class "space-y-2"}
   [:dl {:class "grid grid-cols-2 gap-x-6 gap-y-2"}
    [kv-row "Input Items" (or (:input-items op) "\u2014")]
    [kv-row "Extracted" (or (:extracted op) "\u2014")]]
   ;; Show input → extracted mapping
   (when (or (seq (:items op)) (seq (:memories op)))
     [:div {:class "space-y-2"}
      (when (seq (:items op))
        [:div
         [:span {:class kv-label} "Input"]
         [:div {:class "space-y-1 mt-1"}
          (for [[i item] (map-indexed vector (:items op))]
            ^{:key i}
            [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm"}
             [:p {:class "text-gray-900 dark:text-gray-100"} (:content item)]
             (when (:source item)
               [:span {:class "text-xs text-gray-500 dark:text-gray-400"} (str "source: " (:source item))])])]])
      (when (seq (:memories op))
        [:div
         [:span {:class kv-label} "Extracted Memories"]
         [:div {:class "space-y-1 mt-1"}
          (for [[i mem] (map-indexed vector (:memories op))]
            ^{:key i}
            [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm"}
             [:div {:class "flex items-center gap-2 mb-1"}
              (when (:layer mem) [layer-badge/layer-badge (:layer mem)])
              (when (:importance mem)
                [:span {:class "text-xs text-gray-500 dark:text-gray-400"}
                 (str "importance: " (:importance mem))])]
             [:p {:class "text-gray-900 dark:text-gray-100"} (:content mem)]])]])])
   [token-summary (:tokens op)]])

(defn- op-embed-and-dedup [op]
  [:div {:class "space-y-2"}
   (when-let [content (:content op)]
     [:div {:class "flex items-start gap-2"}
      [:div {:class "flex-1 min-w-0"}
       [content-block content]]
      (when (:layer op) [:div {:class "shrink-0"} [layer-badge/layer-badge (:layer op)]])])
   (let [candidates (:candidates op)]
     (when (seq candidates)
       [:div
        [:span {:class kv-label} (str "Duplicates (" (count candidates) ")")]
        [:div {:class "space-y-1 mt-1"}
         (for [[i c] (map-indexed vector candidates)]
           ^{:key i}
           [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm flex items-start justify-between gap-3"}
            [:p {:class "text-gray-700 dark:text-gray-300 italic flex-1"} (:content c)]
            [:span {:class "text-xs font-mono text-gray-500 dark:text-gray-400 shrink-0"}
             (str "d=" (.toFixed (:distance c) 4))]])]]))
   [token-summary (:tokens op)]])

(defn- op-decide [op]
  [:div {:class "space-y-2"}
   [:div {:class "flex items-center gap-2"}
    [action-badge (:action op)]
    (when (:layer op) [layer-badge/layer-badge (:layer op)])]
   (when-let [content (:content op)]
     [content-block content])
   (when-let [reasoning (:reasoning op)]
     [:p {:class "text-xs text-gray-500 dark:text-gray-400 italic"} reasoning])
   (when-let [merged (:merged-content op)]
     [:div
      [:span {:class kv-label} "Merged Content"]
      [content-block merged]])
   (when-let [target (:target op)]
     [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm"}
      [:span {:class (str kv-label " block mb-1")} "Update Target"]
      [:p {:class "text-gray-700 dark:text-gray-300 italic"} (:content target)]
      (when (:distance target)
        [:span {:class "text-xs font-mono text-gray-500 dark:text-gray-400"}
         (str "d=" (.toFixed (:distance target) 4))])])
   [token-summary (:tokens op)]])

(defn- op-execute [op proc]
  [:div {:class "space-y-2"}
   [:div {:class "flex items-center gap-3"}
    (when (pos? (or (:creates op) 0))
      [:span {:class "text-xs font-medium text-green-700 dark:text-green-400"}
       (str (:creates op) " created")])
    (when (pos? (or (:updates op) 0))
      [:span {:class "text-xs font-medium text-blue-700 dark:text-blue-400"}
       (str (:updates op) " updated")])
    (when (pos? (or (:noops op) 0))
      [:span {:class "text-xs text-gray-500 dark:text-gray-400"}
       (str (:noops op) " no-op")])
    (when (pos? (or (:forgets op) 0))
      [:span {:class "text-xs font-medium text-red-700 dark:text-red-400"}
       (str (:forgets op) " forgotten")])]
   (when-let [results (seq (:results op))]
     [:div {:class "space-y-1"}
      (for [[i r] (map-indexed vector results)]
        ^{:key i}
        [:div {:class "bg-gray-50 dark:bg-gray-900 rounded p-2 text-sm flex items-start gap-2"}
         [action-badge (:type r)]
         [:div {:class "flex-1 min-w-0"}
          [:p {:class "text-gray-700 dark:text-gray-300 truncate"} (:content r)]
          (when (:memory-id r)
            [:code {:class "text-xs text-gray-500 dark:text-gray-400"}
             (:memory-id r)])]])])
   (when-let [cost (:cost op)]
     [:div {:class "flex items-center gap-3 text-xs text-gray-600 dark:text-gray-400"}
      (when-let [total (:total-cost cost)]
        [:span {:class "font-medium"} (str "$" (.toFixed total 4))])
      (when-let [ec (:embedding-cost cost)]
        [:span (str "embed: $" (.toFixed ec 4))])
      (when-let [cc (:chat-cost cost)]
        [:span (str "chat: $" (.toFixed cc 4))])])
   (when proc
     (let [in-flight (get-in proc [:state :in-flight-batches])]
       (when (and in-flight (pos? in-flight))
         [:span {:class "text-xs text-blue-600 dark:text-blue-400"}
          (str in-flight " batch" (when (> in-flight 1) "es") " in flight")])))])

;; -- Stage modal content (renders list of recent ops) --

(defn- stage-modal-content [proc deltas]
  (let [delta      (get deltas (:pid proc))
        activity   (resolve-activity (proc-activity (:status proc) delta) (:count proc))
        pid-kw     (keyword (:pid proc))
        recent-ops (get-in proc [:state :recent-ops])
        status     (get activity-styles activity (:awaiting activity-styles))]
    [:div {:class "space-y-4"}
     ;; Status bar
     [:div {:class "flex items-center gap-4 text-sm"}
      [:div {:class "flex items-center gap-1.5"}
       [:span {:class (str "inline-block w-2.5 h-2.5 rounded-full " (:dot status))}]
       [:span {:class (str "font-medium " (:text status))} (:label status)]]
      [:span {:class "text-gray-400"} "\u00B7"]
      [:span {:class "text-gray-600 dark:text-gray-400"} (str "Processed: " (or (:count proc) 0))]
      (when (and delta (pos? delta))
        [:<>
         [:span {:class "text-gray-400"} "\u00B7"]
         [:span {:class "text-green-600 dark:text-green-400"} (str "+" delta " recent")]])]
     (when (:description proc)
       [:p {:class "text-sm text-gray-500 dark:text-gray-400 italic"} (:description proc)])
     ;; Recent operations list
     (if (seq recent-ops)
       [:div {:class "border-t border-gray-200 dark:border-gray-700 pt-4"}
        [:h4 {:class "text-xs font-medium text-gray-500 dark:text-gray-400 uppercase mb-3"}
         (str "Recent Operations (" (count recent-ops) ")")]
        [:div {:class "space-y-3"}
         (for [[i op] (map-indexed vector recent-ops)]
           ^{:key i}
           [:div {:class (str "rounded-lg border border-gray-100 dark:border-gray-700 p-3 "
                              (when (zero? i) "bg-gray-50/50 dark:bg-gray-800/50"))}
            (case pid-kw
              :prepare-context [op-prepare-context op]
              :batch-extract   [op-batch-extract op]
              :embed-and-dedup [op-embed-and-dedup op]
              :decide          [op-decide op]
              :execute         [op-execute op (when (zero? i) proc)]
              [:p {:class "text-sm text-gray-500"} "No stage-specific data."])])]]
       [:p {:class "text-sm text-gray-500 dark:text-gray-400 italic mt-2"}
        "No operations processed yet."])]))

(defn- detail-pipeline-dag [_processes _deltas _op]
  (let [selected-stage (r/atom nil)]
    (fn [processes deltas _op]
      [ui/card {}
       [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300 mb-3"} "Pipeline Status"]
       (if (seq processes)
         [:div
          [:div {:class "flex items-start gap-0 overflow-x-auto py-2"}
           (doall
            (mapcat
             (fn [i proc]
               (let [delta    (get deltas (:pid proc))
                     activity (resolve-activity (proc-activity (:status proc) delta) (:count proc))
                     styles   (get activity-styles activity (:awaiting activity-styles))]
                 (concat
                  (when (pos? i)
                    [^{:key (str "arrow-" i)}
                     [:div {:class "flex items-center self-center px-1 shrink-0"}
                      [:span {:class (str "text-lg " (if (and delta (pos? delta))
                                                       "text-green-400 dark:text-green-500"
                                                       "text-gray-300 dark:text-gray-600"))}
                       "\u2192"]]])
                  [^{:key (:pid proc)}
                   [:div {:class    (str "bg-white dark:bg-gray-800 rounded-lg border p-3 w-40 shrink-0 cursor-pointer transition-all "
                                         "border-gray-200 dark:border-gray-700 hover:border-gray-300 dark:hover:border-gray-600 " (:ring styles))
                          :on-click #(reset! selected-stage (:pid proc))}
                    [:div {:class "flex items-center gap-1.5 mb-1"}
                     [:span {:class (str "inline-block w-2 h-2 rounded-full " (:dot styles))}]
                     [:span {:class (str "text-xs font-medium " (:text styles))} (:label styles)]]
                    [:h4 {:class "text-sm font-medium text-gray-900 dark:text-gray-100 truncate"} (:label proc)]
                    [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-0.5"} (str "Processed: " (or (:count proc) 0))]]])))
             (range) processes))]
          ;; Stage detail modal
          (when-let [pid @selected-stage]
            (when-let [proc (some #(when (= (:pid %) pid) %) processes)]
              [ui/modal {:open?    true
                         :on-close #(reset! selected-stage nil)
                         :title    (:label proc)}
               [stage-modal-content proc deltas]]))]
         [:p {:class "text-sm text-gray-500 dark:text-gray-400"} "Pipeline not running."])])))

(def ^:private decisions-display-limit 20)

(defn- detail-decisions [op]
  (let [decisions (get-in op [:result :decisions])
        total     (count decisions)
        showing   (take decisions-display-limit decisions)
        hidden    (- total (count showing))]
    (when (seq decisions)
      [ui/card {}
       [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300 mb-2"}
        (str "Decisions (" total ")")]
       [:div {:class "divide-y divide-gray-100 dark:divide-gray-800"}
        (for [[i d] (map-indexed vector showing)]
          ^{:key i}
          [:div {:class "flex items-center gap-2 py-1.5"}
           [action-badge (:type d)]
           [:span {:class "text-sm text-gray-900 dark:text-gray-100 truncate"} (:content d)]])]
       (when (pos? hidden)
         [:p {:class "text-xs text-gray-500 dark:text-gray-400 mt-2 italic"}
          (str "+" hidden " more decision" (when (> hidden 1) "s") " not shown")])])))

(defn- usage-stat [label value]
  [:div {:class "flex flex-col items-center px-3 py-1.5"}
   [:span {:class "text-sm font-semibold text-gray-900 dark:text-gray-100"} value]
   [:span {:class "text-xs text-gray-500 dark:text-gray-400"} label]])

(defn- detail-output [op]
  (let [memory-ids (get-in op [:result :memory-ids])
        usage      (get-in op [:result :usage])]
    (when (or (seq memory-ids) usage)
      [ui/card {}
       [:h4 {:class "text-sm font-medium text-gray-700 dark:text-gray-300 mb-3"} "Output"]
       [:div {:class "space-y-4"}
        (when (seq memory-ids)
          [:div
           [:span {:class "text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Memory IDs"]
           [:pre {:class "mt-1.5 bg-gray-50 dark:bg-gray-900 rounded-lg p-3 text-xs font-mono text-gray-700 dark:text-gray-300 overflow-x-auto"}
            (interpose "\n" memory-ids)]])
        (when usage
          [:div
           [:span {:class "text-xs font-medium text-gray-500 dark:text-gray-400 uppercase"} "Usage"]
           [:div {:class "flex items-center gap-0 mt-1.5 bg-gray-50 dark:bg-gray-900 rounded-lg divide-x divide-gray-200 dark:divide-gray-700"}
            (when-let [t (:total-tokens usage)]
              [usage-stat "tokens" (str t)])
            (when-let [p (:prompt-tokens usage)]
              [usage-stat "prompt" (str p)])
            (when-let [c (:completion-tokens usage)]
              [usage-stat "completion" (str c)])
            (when-let [cost (:estimated-cost usage)]
              (when-let [total (:total-cost cost)]
                [usage-stat "cost" (str "$" (.toFixed total 4))]))]])
        (when (seq memory-ids)
          [:button {:class    "inline-flex items-center gap-1.5 text-sm font-medium text-indigo-600 dark:text-indigo-400 hover:text-indigo-800 dark:hover:text-indigo-300"
                    :on-click #(rf/dispatch [:graph/navigate-with-highlights memory-ids])}
           "View in Graph \u2192"])]])))

(defn- operation-detail [op processes deltas]
  [:div {:class "space-y-4"}
   [detail-header op]
   [detail-pipeline-dag processes deltas op]
   [detail-decisions op]
   [detail-output op]])

;; ---------------------------------------------------------------------------
;; Page component (Form-3 with lifecycle)
;; ---------------------------------------------------------------------------

(defn page []
  (r/create-class
   {:display-name "pipeline-page"

    :component-did-mount
    (fn [_]
      (rf/dispatch [:pipeline/start-polling])
      (rf/dispatch [:pipeline/fetch-operations]))

    :component-will-unmount
    (fn [_]
      (rf/dispatch [:pipeline/stop-polling])
      (rf/dispatch [:pipeline/clear-selection]))

    :reagent-render
    (fn []
      (let [pipeline     @(rf/subscribe [:pipeline])
            active-ns    @(rf/subscribe [:active-namespace])
            processes    (get-in pipeline [:data :processes])
            deltas       (:deltas pipeline)
            all-ops      (:operations pipeline)
            operations   (if active-ns
                           (filterv #(= (:namespace %) active-ns) all-ops)
                           all-ops)
            ops-loading? (:ops-loading? pipeline)
            selected-op  (:selected-op pipeline)]
        [:div {:class "space-y-6 min-w-0"}
         (if selected-op
           ;; Detail view
           [operation-detail selected-op processes deltas]
           ;; List view
           [:div {:class "space-y-6"}
            [:div {:class "flex items-center justify-between"}
             [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Pipeline"]
             (when (seq processes)
               [:div {:class "flex items-center gap-2 text-xs text-gray-400 dark:text-gray-500"}
                [:span {:class "inline-block w-1.5 h-1.5 rounded-full bg-green-500 animate-pulse"}]
                "Live"])]

            ;; Live pipeline status bar
            (when (seq processes)
              [ui/card {}
               [:div {:class "space-y-3"}
                [:h3 {:class "text-sm font-medium text-gray-700 dark:text-gray-300"} "Pipeline Status"]
                [live-pipeline-bar processes deltas operations]]])

            ;; Operations list
            [ui/card {}
             [:div {:class "flex items-center justify-between mb-4"}
              [:h3 {:class "text-sm font-medium text-gray-700 dark:text-gray-300"} "Recent Operations"]
              [ui/button {:on-click #(rf/dispatch [:pipeline/fetch-operations])
                          :variant  :secondary
                          :class    "text-xs"}
               "Refresh"]]
             (if (and ops-loading? (nil? operations))
               [loading/spinner]
               [operations-table operations])]])]))}))
