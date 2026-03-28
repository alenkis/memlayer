(ns memlayer.dashboard.views.dashboard
  (:require [re-frame.core :as rf]
            [reagent.core :as r]
            [memlayer.dashboard.components.ui :as ui]
            [memlayer.dashboard.components.layer-badge :as layer-badge]
            [memlayer.dashboard.routes :as routes]
            [memlayer.domain.colors :as colors]))

;; ---------------------------------------------------------------------------
;; Getting Started (onboarding)
;; ---------------------------------------------------------------------------

(defn- step-number [n]
  [:div {:class       "flex items-center justify-center w-8 h-8 rounded-full bg-indigo-100 dark:bg-indigo-900/50 text-indigo-700 dark:text-indigo-300 text-sm font-bold flex-shrink-0"
         :aria-hidden "true"}
   (str n)])

(defn- code-block
  "Pre-formatted code block with copy button."
  [_props]
  (let [copied? (r/atom false)]
    (fn [{:keys [value]}]
      [:div {:class "relative group"}
       [:pre {:class "font-mono text-sm bg-gray-900 text-gray-100 rounded-lg p-4 pr-16 overflow-x-auto whitespace-pre-wrap break-all"}
        value]
       [:button {:on-click (fn []
                             (-> (js/navigator.clipboard.writeText (or value ""))
                                 (.then (fn []
                                          (reset! copied? true)
                                          (js/setTimeout #(reset! copied? false) 2000)))))
                 :aria-label "Copy code to clipboard"
                 :class    "absolute top-2 right-2 px-2 py-1 rounded text-xs font-medium transition-colors bg-gray-700 hover:bg-gray-600 text-gray-300 cursor-pointer"}
        (if @copied? "Copied!" "Copy")]])))

(def ^:private client-configs
  [{:id    :claude-code
    :label "Claude Code"
    :type  :command}
   {:id    :cursor
    :label "Cursor"
    :type  :json
    :path  ".cursor/mcp.json"}
   {:id    :windsurf
    :label "Windsurf"
    :type  :json
    :path  ".windsurf/mcp.json"
    :url-key "serverUrl"}
   {:id    :vscode
    :label "VS Code"
    :type  :json-vscode}
   {:id    :claude-desktop
    :label "Claude Desktop"
    :type  :json}])

(defn- mcp-json-config [api-key & {:keys [url-key] :or {url-key "url"}}]
  (str "{\n"
       "  \"mcpServers\": {\n"
       "    \"memlayer\": {\n"
       "      \"" url-key "\": \"https://api.memlayer.dev/mcp\",\n"
       "      \"headers\": {\n"
       "        \"X-API-Key\": \"" api-key "\"\n"
       "      }\n"
       "    }\n"
       "  }\n"
       "}"))

(defn- vscode-json-config [api-key]
  (str "{\n"
       "  \"servers\": {\n"
       "    \"memlayer\": {\n"
       "      \"type\": \"http\",\n"
       "      \"url\": \"https://api.memlayer.dev/mcp\",\n"
       "      \"headers\": {\n"
       "        \"X-API-Key\": \"" api-key "\"\n"
       "      }\n"
       "    }\n"
       "  }\n"
       "}"))

(defn- claude-code-command [api-key]
  (str "claude mcp add memlayer \\\n"
       "  https://api.memlayer.dev/mcp \\\n"
       "  --transport http \\\n"
       "  --header \"X-API-Key: " api-key "\""))

(defn- config-for-client [client api-key]
  (case (:type client)
    :command (claude-code-command api-key)
    :json    (mcp-json-config api-key :url-key (or (:url-key client) "url"))
    :json-vscode (vscode-json-config api-key)))

(defn- setup-tabs [_api-key]
  (let [active-client (r/atom :claude-code)]
    (fn [api-key]
      (let [client (first (filter #(= @active-client (:id %)) client-configs))]
        [:div
         ;; Tab bar
         [:div {:class "flex gap-1 mb-3 flex-wrap" :role "tablist" :aria-label "MCP client configuration"}
          (doall (for [{:keys [id label]} client-configs]
                   ^{:key id}
                   [:button {:on-click      #(reset! active-client id)
                             :role          "tab"
                             :aria-selected (= id @active-client)
                             :class         (str "px-3 py-1.5 rounded-md text-sm font-medium transition-colors cursor-pointer "
                                                 (if (= id @active-client)
                                                   "bg-indigo-100 text-indigo-700 dark:bg-indigo-900/50 dark:text-indigo-300"
                                                   "text-gray-600 hover:bg-gray-100 dark:text-gray-400 dark:hover:bg-gray-700"))}
                    label]))]
         ;; Config content
         [:div {:role "tabpanel"}
          (when (and client (#{:json :json-vscode} (:type client)))
            [:p {:class "text-sm text-gray-500 dark:text-gray-400 mb-2"}
             (str "Create " (or (:path client) ".mcp.json") " in your project root:")])
          [code-block {:value (config-for-client client api-key)}]]]))))

(defn- getting-started []
  (let [api-key @(rf/subscribe [:auth/active-api-key])]
    [:div {:class "space-y-6 max-w-3xl"}
     [:div
      [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Welcome to MemLayer"]
      [:p {:class "text-gray-500 dark:text-gray-400 mt-1"} "Set up persistent memory for your AI agent in under 2 minutes."]]

     ;; Step 1: API Key
     [ui/card {}
      [:div {:class "flex items-start gap-4"}
       [step-number 1]
       [:div {:class "flex-1 min-w-0"}
        [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-1"} "Your API key"]
        [:p {:class "text-sm text-gray-500 dark:text-gray-400 mb-3"} "This key was created for you automatically."]
        (if api-key
          [ui/copy-button {:value api-key}]
          [:p {:class "text-sm text-gray-400 dark:text-gray-500 italic"} "Loading..."])]]]

     ;; Step 2: Configure agent
     [ui/card {}
      [:div {:class "flex items-start gap-4"}
       [step-number 2]
       [:div {:class "flex-1 min-w-0"}
        [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-1"} "Add to your agent"]
        [:p {:class "text-sm text-gray-500 dark:text-gray-400 mb-3"} "Connect memlayer to your AI coding agent."]
        (if api-key
          [setup-tabs api-key]
          [:p {:class "text-sm text-gray-400 dark:text-gray-500 italic"} "Waiting for API key..."])]]]

     ;; Step 3: Start using
     [ui/card {}
      [:div {:class "flex items-start gap-4"}
       [step-number 3]
       [:div {:class "flex-1 min-w-0"}
        [:h3 {:class "text-lg font-semibold text-gray-900 dark:text-gray-100 mb-1"} "Start using it"]
        [:p {:class "text-sm text-gray-500 dark:text-gray-400 mb-3"}
         "Your agent now has persistent memory. It will remember important things from your conversations automatically."]
        [:div {:class "flex items-center gap-4"}
         [:a {:href  (routes/href :playground)
              :class "text-indigo-600 dark:text-indigo-400 text-sm font-medium hover:underline"}
          "Try the Playground"]
         [:a {:href  "https://memlayer.dev/getting-started/quickstart-mcp/"
              :target "_blank"
              :rel    "noopener noreferrer"
              :class  "text-gray-500 dark:text-gray-400 text-sm hover:underline"}
          "Read the docs"]]]]]]))

;; ---------------------------------------------------------------------------
;; Stats dashboard (existing)
;; ---------------------------------------------------------------------------

(defn health-indicator []
  (let [health @(rf/subscribe [:health])]
    [ui/card {:class ""}
     [:div {:class "flex items-center justify-between"}
      [:div
       [:h3 {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} "API Status"]
       [:p {:class "mt-1 text-2xl font-semibold"}
        (cond
          (:loading? health) "Checking..."
          (:error health)    [:span {:class "text-red-600 dark:text-red-400"} "Offline"]
          (:status health)   [:span {:class "text-green-600 dark:text-green-400"} "Online"]
          :else              "Unknown")]]
      [:div {:class       (str "w-3 h-3 rounded-full "
                               (cond
                                 (:error health) "bg-red-500"
                                 (:status health) "bg-green-500"
                                 :else "bg-gray-300 dark:bg-gray-600"))
             :aria-hidden "true"}]]]))

(defn stat-card [title value subtitle]
  [ui/card {}
   [:h3 {:class "text-sm font-medium text-gray-500 dark:text-gray-400"} title]
   [:p {:class "mt-1 text-3xl font-semibold text-gray-900 dark:text-gray-100"} (or value "-")]
   (when subtitle
     [:p {:class "mt-1 text-sm text-gray-500 dark:text-gray-400"} subtitle])])

(defn layer-distribution []
  (let [stats @(rf/subscribe [:memory-stats])
        by-layer (get-in stats [:data :by-layer])
        total (get-in stats [:data :namespace-total] 1)]
    [ui/card {:class "col-span-2"}
     [:h3 {:class "text-sm font-medium text-gray-500 dark:text-gray-400 mb-4"} "Memory Distribution by Layer"]
     (if (seq by-layer)
       [:div {:class "space-y-3"}
        (for [[layer-name cnt] by-layer]
          (let [layer-kw (keyword "layer" layer-name)
                color (get colors/layer-colors layer-kw "#9ca3af")
                pct (if (pos? total) (* 100 (/ cnt total)) 0)]
            ^{:key layer-name}
            [:div {:class "flex items-center gap-3"}
             [layer-badge/layer-badge layer-name]
             [:div {:class "flex-1"}
              [:div {:class "w-full bg-gray-200 dark:bg-gray-700 rounded-full h-2"}
               [:div {:class "h-2 rounded-full transition-all"
                      :style {:width (str pct "%") :background-color color}}]]]
             [:span {:class "text-sm text-gray-600 dark:text-gray-400 w-12 text-right"} cnt]]))]
       [:p {:class "text-gray-500 dark:text-gray-400 text-sm"} "No memories yet"])]))

(defn consistency-panel []
  (let [consistency-state @(rf/subscribe [:consistency])
        data (:data consistency-state)]
    [ui/card {}
     [:h3 {:class "text-sm font-medium text-gray-500 dark:text-gray-400 mb-4"} "Consistency"]
     (if data
       [:div {:class "space-y-2"}
        [:div {:class "flex justify-between text-sm"}
         [:span {:class "text-gray-700 dark:text-gray-300"} "Missing vectors"]
         [:span {:class (if (zero? (:missing-vectors data 0))
                          "text-green-600 dark:text-green-400" "text-red-600 dark:text-red-400")}
          (:missing-vectors data 0)]]
        [:div {:class "flex justify-between text-sm"}
         [:span {:class "text-gray-700 dark:text-gray-300"} "Orphan vectors"]
         [:span {:class (if (zero? (:orphan-vectors data 0))
                          "text-green-600 dark:text-green-400" "text-red-600 dark:text-red-400")}
          (:orphan-vectors data 0)]]]
       [:p {:class "text-gray-500 dark:text-gray-400 text-sm"} "Loading..."])]))

(defn- stats-dashboard []
  (let [stats @(rf/subscribe [:memory-stats])
        total (get-in stats [:data :namespace-total] 0)
        by-layer (get-in stats [:data :by-layer])]
    [:div {:class "space-y-6"}
     [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Dashboard"]
     [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-6"}
      [health-indicator]
      [stat-card "Total Memories" total nil]
      [stat-card "Layers" (count by-layer) nil]]
     [:div {:class "grid grid-cols-1 md:grid-cols-3 gap-6"}
      [layer-distribution]
      [consistency-panel]]]))

;; ---------------------------------------------------------------------------
;; Page
;; ---------------------------------------------------------------------------

(defn page []
  (rf/dispatch [:fetch-health])
  (rf/dispatch [:fetch-memory-stats])
  (rf/dispatch [:fetch-consistency])
  (fn []
    (let [stats        @(rf/subscribe [:memory-stats])
          api-key      @(rf/subscribe [:auth/active-api-key])
          global-total (get-in stats [:data :global-total] 0)]
      (if (and api-key (pos? global-total))
        [stats-dashboard]
        [getting-started]))))
