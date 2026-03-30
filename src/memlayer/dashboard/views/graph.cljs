(ns memlayer.dashboard.views.graph
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [re-frame.core :as rf]
            [memlayer.dashboard.components.loading :as loading]
            [memlayer.domain.colors :as colors]
            ["d3-force" :as d3-force]
            ["d3-zoom" :as d3-zoom]
            ["d3-selection" :as d3-selection]))

;; Callback atom — set by force-graph-inner so recall panel can select nodes
(defonce ^:private select-node-fn! (atom nil))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- truncate-label [s n]
  (if (> (count s) n)
    (str (subs s 0 n) "...")
    s))

(def ^:private char-width
  "Approximate character width for font-size 8."
  4.5)

(def ^:private line-height
  "Vertical spacing between wrapped lines."
  10)

(defn- wrap-label-for-circle
  "Word-wrap text to fit inside a circle of given radius.
   Returns a vector of {:text line-string :dy y-offset} maps."
  [text r]
  (let [padding   4
        inner-r   (- r padding)
        ;; Maximum lines that fit vertically
        max-lines 3
        ;; For n lines, compute the y offsets so they center in the circle
        ;; and the available width (chord) at each offset
        half-h    (* (dec max-lines) (/ line-height 2))
        offsets   (mapv #(- (* % line-height) half-h) (range max-lines))
        widths    (mapv (fn [dy]
                          (let [r2 (* inner-r inner-r)
                                dy2 (* dy dy)]
                            (if (> dy2 r2)
                              0
                              (* 2 (Math/sqrt (- r2 dy2))))))
                        offsets)
        max-chars (mapv #(max 2 (int (/ % char-width))) widths)
        words     (str/split (or text "") #"\s+")
        ;; Greedily pack words into lines respecting per-line char limits
        pack      (fn [words max-chars]
                    (loop [ws words, line-idx 0, lines [], current ""]
                      (cond
                        (>= line-idx (count max-chars))
                        (if (seq current)
                          (let [last-line (peek lines)
                                truncated (str (subs last-line 0 (min (count last-line)
                                                                      (- (nth max-chars (dec (count max-chars))) 1)))
                                               "\u2026")]
                            (conj (pop lines) truncated))
                          lines)

                        (empty? ws)
                        (if (seq current) (conj lines current) lines)

                        :else
                        (let [w      (first ws)
                              limit  (nth max-chars line-idx)
                              trial  (if (seq current) (str current " " w) w)]
                          (if (<= (count trial) limit)
                            (recur (rest ws) line-idx lines trial)
                            (if (seq current)
                              ;; Start new line with current word
                              (recur ws (inc line-idx) (conj lines current) "")
                              ;; Single word too long — truncate it
                              (recur (rest ws) (inc line-idx)
                                     (conj lines (str (subs w 0 (max 1 (- limit 1))) "\u2026"))
                                     "")))))))
        lines (pack words max-chars)
        n     (count lines)
        ;; Re-center based on actual number of lines
        half  (* (dec n) (/ line-height 2))]
    (mapv (fn [i line]
            {:text line :dy (- (* i line-height) half)})
          (range) lines)))

(defn- memory->node [memory]
  (let [layer-kw (keyword "layer" (:layer memory))]
    (cond-> {:id            (:id memory)
             :content       (:content memory)
             :display-title (:display-title memory)
             :layer         (:layer memory)
             :r             24
             :color         (get colors/layer-colors layer-kw "#9ca3af")}
      (:parent-id memory) (assoc :parent-id (:parent-id memory)))))

(defn- rel->link [rel]
  {:id       (:id rel)
   :source   (:source-id rel)
   :target   (:target-id rel)
   :rel-type (:type rel)})

(defn- edge-midpoint
  "Compute the midpoint between source and target positions."
  [src-pos tgt-pos]
  {:x (/ (+ (:x src-pos) (:x tgt-pos)) 2)
   :y (/ (+ (:y src-pos) (:y tgt-pos)) 2)})

(defn- shorten-line
  "Shorten a line by `gap` px at the target end (for arrowhead clearance)."
  [x1 y1 x2 y2 gap]
  (let [dx (- x2 x1)
        dy (- y2 y1)
        len (Math/sqrt (+ (* dx dx) (* dy dy)))]
    (if (< len 0.01)
      {:x2 x2 :y2 y2}
      (let [ratio (/ (- len gap) len)]
        {:x2 (+ x1 (* dx ratio))
         :y2 (+ y1 (* dy ratio))}))))

(defn- adaptive-max-zoom
  "Limit max zoom based on node count to avoid zooming into sparse graphs."
  [node-count]
  (cond
    (<= node-count 10) 2.0
    (<= node-count 30) 3.0
    (<= node-count 50) 5.0
    :else              8.0))

;; ---------------------------------------------------------------------------
;; Clustering
;; ---------------------------------------------------------------------------

(def ^:private cluster-threshold 50)
(def ^:private min-children-to-cluster 3)

(defn- build-layer-clusters
  "Zoom < 0.4: one node per visible layer with a count label.
   Expanded layer clusters show their individual nodes."
  [memories relationships expanded-clusters]
  (let [by-layer      (group-by :layer memories)
        expanded?     (fn [layer] (contains? expanded-clusters (str "cluster:layer:" layer)))
        ;; Individual nodes for expanded layers
        expanded-nodes (vec (mapcat (fn [[layer mems]]
                                      (when (expanded? layer)
                                        (mapv memory->node mems)))
                                    by-layer))
        ;; Cluster nodes for collapsed layers
        cluster-nodes  (vec (keep (fn [[layer mems]]
                                    (when-not (expanded? layer)
                                      (let [layer-kw (keyword "layer" layer)
                                            cnt      (count mems)
                                            size     (+ 40 (min (* cnt 2) 30))]
                                        {:id       (str "cluster:layer:" layer)
                                         :content  (str cnt " " layer " memories")
                                         :layer    layer
                                         :r        size
                                         :color    (get colors/layer-colors layer-kw "#9ca3af")
                                         :cluster? true})))
                                  by-layer))
        all-nodes      (into expanded-nodes cluster-nodes)
        node-ids       (into #{} (map :id all-nodes))
        ;; Map unclustered memories to their layer cluster (for link routing)
        mem->cluster   (into {} (keep (fn [m]
                                        (let [cid (str "cluster:layer:" (:layer m))]
                                          (when (not (expanded? (:layer m)))
                                            [(:id m) cid]))))
                             memories)
        seen           (atom #{})
        links          (->> relationships
                            (keep (fn [rel]
                                    (let [from (or (get mem->cluster (:source-id rel))
                                                   (:source-id rel))
                                          to   (or (get mem->cluster (:target-id rel))
                                                   (:target-id rel))]
                                      (when (and (not= from to)
                                                 (contains? node-ids from)
                                                 (contains? node-ids to))
                                        (let [k (str from "-" to)]
                                          (when-not (contains? @seen k)
                                            (swap! seen conj k)
                                            {:id     k
                                             :source from
                                             :target to}))))))
                            vec)]
    {:nodes all-nodes :links links}))

(defn- build-parent-clusters
  "Zoom 0.4-0.7 with >50 nodes: group children under parent nodes.
   Expanded clusters show their individual children instead."
  [memories relationships expanded-clusters]
  (let [by-parent   (group-by :parent-id memories)
        clusterable (into {}
                          (keep (fn [[pid children]]
                                  (when (and pid
                                             (>= (count children) min-children-to-cluster)
                                             (not (contains? expanded-clusters (str "cluster:" pid))))
                                    [pid children])))
                          by-parent)
        clustered-ids (into #{} (mapcat (fn [[_ children]] (map :id children))) clusterable)
        unclustered (remove #(contains? clustered-ids (:id %)) memories)
        unclustered-nodes (mapv memory->node unclustered)
        cluster-nodes (mapv (fn [[pid children]]
                              (let [first-child (first children)
                                    layer-kw    (keyword "layer" (:layer first-child))
                                    cnt         (count children)
                                    size        (+ 40 (min (* cnt 2) 30))]
                                {:id       (str "cluster:" pid)
                                 :content  (str cnt " memories")
                                 :layer    (:layer first-child)
                                 :r        size
                                 :color    (get colors/layer-colors layer-kw "#9ca3af")
                                 :cluster? true}))
                            clusterable)
        all-nodes (into unclustered-nodes cluster-nodes)
        member->cluster (into {} (mapcat (fn [[pid children]]
                                           (map (fn [c] [(:id c) (str "cluster:" pid)]) children))
                                         clusterable))
        node-ids (into #{} (map :id all-nodes))
        seen     (atom #{})
        links    (->> relationships
                      (keep (fn [rel]
                              (let [from (or (get member->cluster (:source-id rel)) (:source-id rel))
                                    to   (or (get member->cluster (:target-id rel)) (:target-id rel))]
                                (when (and (not= from to)
                                           (contains? node-ids from)
                                           (contains? node-ids to))
                                  (let [k (str from "-" to)]
                                    (when-not (contains? @seen k)
                                      (swap! seen conj k)
                                      {:id     k
                                       :source from
                                       :target to}))))))
                      vec)]
    {:nodes all-nodes :links links}))

(def ^:private min-nodes-to-cluster 10)

(defn- compute-display-data
  "Compute nodes and links based on zoom level and clustering thresholds.
   Skip clustering entirely when the graph has fewer than min-nodes-to-cluster
   nodes — clustering a handful of nodes hurts more than it helps."
  [memories relationships zoom-level expanded-clusters]
  (let [n (count memories)]
    (cond
      (and (< zoom-level 0.4) (>= n min-nodes-to-cluster))
      (build-layer-clusters memories relationships expanded-clusters)

      (and (< zoom-level 0.7) (> n cluster-threshold))
      (build-parent-clusters memories relationships expanded-clusters)

      :else
      (let [nodes    (mapv memory->node memories)
            node-ids (into #{} (map :id nodes))
            links    (->> relationships
                          (filterv #(and (contains? node-ids (:source-id %))
                                         (contains? node-ids (:target-id %))))
                          (mapv rel->link))]
        {:nodes nodes :links links}))))

;; ---------------------------------------------------------------------------
;; Simulation management
;; ---------------------------------------------------------------------------

(defn- create-simulation!
  "Create a d3-force simulation from nodes and links. Returns the simulation."
  [js-nodes js-links on-tick]
  (let [link-force (-> (d3-force/forceLink js-links)
                       (.id (fn [d] (.-id d)))
                       (.distance 168)
                       (.strength 0.2))
        charge     (-> (d3-force/forceManyBody)
                       (.strength -480)
                       (.distanceMax 720))
        center-x   (-> (d3-force/forceX 400) (.strength 0.03))
        center-y   (-> (d3-force/forceY 300) (.strength 0.03))
        collide    (-> (d3-force/forceCollide)
                       (.radius (fn [d] (+ (.-r d) 20))))]
    (-> (d3-force/forceSimulation js-nodes)
        (.force "link" link-force)
        (.force "charge" charge)
        (.force "x" center-x)
        (.force "y" center-y)
        (.force "collide" collide)
        (.alphaDecay 0.02)
        (.on "tick" on-tick))))

(defn- restart-simulation!
  "Restart simulation with new nodes/links, warm-starting at given alpha."
  [sim js-nodes js-links alpha]
  (let [link-force (-> (d3-force/forceLink js-links)
                       (.id (fn [d] (.-id d)))
                       (.distance 168)
                       (.strength 0.2))]
    (-> sim
        (.nodes js-nodes)
        (.force "link" link-force)
        (.force "collide" (-> (d3-force/forceCollide)
                              (.radius (fn [d] (+ (.-r d) 24)))))
        (.alpha alpha)
        (.restart))))

(defn- stop-simulation! [sim]
  (when sim (.stop sim)))

;; ---------------------------------------------------------------------------
;; Layer toggles
;; ---------------------------------------------------------------------------

(defn layer-toggles []
  (let [graph @(rf/subscribe [:graph])
        visible (:visible-layers graph)]
    [:div {:class "flex gap-2"}
     (for [[layer-kw label] colors/layer-names]
       (let [active? (contains? visible layer-kw)
             color (get colors/layer-colors layer-kw)]
         ^{:key layer-kw}
         [:button {:on-click     #(rf/dispatch [:toggle-layer-visibility layer-kw])
                   :aria-pressed active?
                   :class        (str "px-3 py-1 rounded-full text-xs font-medium border transition-colors cursor-pointer "
                                      (if active?
                                        "text-white"
                                        "text-gray-500 dark:text-gray-400 bg-white dark:bg-gray-800 border-gray-300 dark:border-gray-600"))
                   :style        (when active? {:background-color color :border-color color})}
          label]))]))

;; ---------------------------------------------------------------------------
;; Shared zoom state (singleton — one graph instance at a time)
;; ---------------------------------------------------------------------------

(defonce ^:private zoom-behavior-ref (atom nil))

(def ^:private graph-svg-id "graph-svg")

;; ---------------------------------------------------------------------------
;; Zoom controls
;; ---------------------------------------------------------------------------

(defn zoom-controls []
  (let [btn-class "px-2 py-1 rounded text-xs font-medium border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-700 dark:text-gray-300 hover:bg-gray-100 dark:hover:bg-gray-700 transition-colors cursor-pointer"]
    [:div {:class "flex gap-1"}
     [:button {:on-click   #(when-let [zb @zoom-behavior-ref]
                              (let [svg-el   (d3-selection/select (str "#" graph-svg-id))
                                    svg-node (.node svg-el)
                                    ;; Use viewBox dimensions — zoom transform operates in this space
                                    vb       (.getAttribute svg-node "viewBox")
                                    vb-parts (.split vb " ")
                                    w        (js/parseFloat (aget vb-parts 2))
                                    h        (js/parseFloat (aget vb-parts 3))
                                    ;; Collect node positions + radii from circle elements
                                    circles  (.selectAll svg-el "[data-testid='graph-nodes'] circle")
                                    pts      (atom [])]
                                (.each circles
                                       (fn [_d]
                                         (this-as el
                                                  (let [x (js/parseFloat (.getAttribute el "cx"))
                                                        y (js/parseFloat (.getAttribute el "cy"))
                                                        r (js/parseFloat (.getAttribute el "r"))]
                                                    (when (and (not (js/isNaN x)) (not (js/isNaN y))
                                                               (pos? r))
                                                      (swap! pts conj {:x x :y y :r r}))))))
                                (when (seq @pts)
                                  ;; Center of mass weighted by node radius
                                  (let [points    @pts
                                        total-w   (reduce + (map :r points))
                                        com-x     (/ (reduce + (map (fn [p] (* (:x p) (:r p))) points)) total-w)
                                        com-y     (/ (reduce + (map (fn [p] (* (:y p) (:r p))) points)) total-w)
                                        ;; Distance of each point from center of mass
                                        with-dist (map (fn [p] (assoc p :dist (js/Math.hypot (- (:x p) com-x)
                                                                                             (- (:y p) com-y))))
                                                       points)
                                        sorted    (sort-by :dist with-dist)
                                        ;; Keep 80% of nodes closest to center of mass (the main cluster)
                                        n-keep    (max 1 (js/Math.ceil (* 0.8 (count sorted))))
                                        cluster   (take n-keep sorted)
                                        ;; Fit bounding box to the main cluster
                                        min-x     (apply min (map :x cluster))
                                        max-x     (apply max (map :x cluster))
                                        min-y     (apply min (map :y cluster))
                                        max-y     (apply max (map :y cluster))
                                        bw        (- max-x min-x)
                                        bh        (- max-y min-y)
                                        pad       60
                                        s         (min (/ (- w (* 2 pad)) (max bw 1))
                                                       (/ (- h (* 2 pad)) (max bh 1))
                                                       2.0)
                                        t         (-> (.-zoomIdentity d3-zoom)
                                                      (.translate (/ w 2) (/ h 2))
                                                      (.scale s)
                                                      (.translate (- com-x) (- com-y)))]
                                    (.transform zb
                                                (-> svg-el (.transition) (.duration 300))
                                                t)))))
               :class      btn-class
               :aria-label "Fit to screen"
               :title      "Fit to screen"}
      "Fit"]
     [:button {:on-click   #(when-let [zb @zoom-behavior-ref]
                              (let [svg (d3-selection/select (str "#" graph-svg-id))]
                                (.scaleBy zb svg 1.3)))
               :class      btn-class
               :aria-label "Zoom in"
               :title      "Zoom in"}
      "+"]
     [:button {:on-click   #(when-let [zb @zoom-behavior-ref]
                              (let [svg (d3-selection/select (str "#" graph-svg-id))]
                                (.scaleBy zb svg (/ 1 1.3))))
               :class      btn-class
               :aria-label "Zoom out"
               :title      "Zoom out"}
      "-"]]))

;; ---------------------------------------------------------------------------
;; Tooltip component
;; ---------------------------------------------------------------------------

(defn- tooltip-screen-pos
  "Compute screen-space position for a tooltip given node pos and zoom params."
  [pos node zoom-p]
  (let [k  (:k zoom-p)
        tx (:x zoom-p)
        ty (:y zoom-p)]
    {:sx (+ (* (:x pos) k) tx (* (:r node) k) 8)
     :sy (+ (* (:y pos) k) ty (- 40))}))

(defn- node-tooltip-compact
  "Compact hover tooltip — layer, truncated content, click hint."
  [node pos zoom-p]
  (let [layer-kw (keyword "layer" (:layer node))
        label    (get colors/layer-names layer-kw (:layer node))
        {:keys [sx sy]} (tooltip-screen-pos pos node zoom-p)]
    [:foreignObject {:x sx :y sy :width 280 :height 120
                     :class "pointer-events-none"}
     [:div {:class "bg-gray-900 text-white text-sm rounded-lg px-3 py-2 shadow-lg"
            :style {:max-width "270px"}}
      [:div {:class "font-medium mb-1"
             :style {:color (get colors/layer-colors layer-kw)}}
       label]
      [:div {:class "text-gray-300 break-words"}
       (truncate-label (:content node) 120)]
      (if (:cluster? node)
        [:div {:class "text-gray-400 mt-1 italic"} "Click to expand"]
        [:div {:class "text-gray-400 mt-1 italic"} "Click for details"])]]))

(defn- navigable-memory-link
  "Clickable link to another memory — shows layer color dot + truncated content."
  [memory-id id->memory on-navigate]
  (let [target (get id->memory memory-id)]
    (if target
      (let [layer-kw (keyword "layer" (:layer target))]
        [:span {:class    "inline-flex items-start gap-1 cursor-pointer hover:text-white"
                :on-click (fn [e]
                            (.stopPropagation e)
                            (on-navigate memory-id))}
         [:span {:class "inline-block w-2 h-2 rounded-full flex-shrink-0 mt-1"
                 :style {:background-color (get colors/layer-colors layer-kw "#9ca3af")}}]
         [:span {:class "underline decoration-dotted"}
          (truncate-label (:content target) 40)]])
      [:span {:class    "cursor-pointer hover:text-white font-mono underline decoration-dotted"
              :on-click (fn [e]
                          (.stopPropagation e)
                          (on-navigate memory-id))}
       (truncate-label memory-id 12)])))

(defn- node-tooltip-expanded
  "Expanded detail card — pinned on click, shows full memory info.
   on-navigate is called with a memory-id to jump to that node."
  [node pos zoom-p on-close on-navigate id->memory]
  (let [layer-kw (keyword "layer" (:layer node))
        label    (get colors/layer-names layer-kw (:layer node))
        memory   @(rf/subscribe [:selected-memory])
        {:keys [sx sy]} (tooltip-screen-pos pos node zoom-p)]
    [:foreignObject {:x sx :y sy :width 320 :height 420}
     [:div {:class         "bg-gray-900 text-white text-sm rounded-lg px-3 py-3 shadow-lg overflow-y-auto"
            :style         {:max-width "310px" :max-height "400px"
                            :user-select "text" :-webkit-user-select "text"}
            :on-mouse-down (fn [e] (.stopPropagation e))
            :on-click      (fn [e] (.stopPropagation e))}

      ;; Header with close button
      [:div {:class "flex items-center justify-between mb-2"}
       [:div {:class "font-medium" :style {:color (get colors/layer-colors layer-kw)}}
        label]
       [:button {:on-click on-close
                 :class    "text-gray-400 hover:text-white text-xs cursor-pointer"}
        "X"]]
      ;; Content
      [:div {:class "text-gray-200 break-words mb-3"}
       (:content node)]
      ;; Extra fields from fetched memory
      (if memory
        [:div {:class "space-y-2 text-xs border-t border-gray-700 pt-2"}
         [:div {:class "flex justify-between"}
          [:span {:class "text-gray-400"} "Source"]
          [:span {:class "text-gray-200"} (or (:source memory) "-")]]
         ;; Hierarchy — parent navigation
         (when-let [pid (:parent-id memory)]
           [:div {:class "border-t border-gray-700 pt-2"}
            [:span {:class "text-gray-500 text-xs uppercase tracking-wide block mb-1"} "Hierarchy"]
            [:div {:class "flex items-start gap-1"}
             [:span {:class "text-gray-500 flex-shrink-0"} "\u2191"]
             [navigable-memory-link pid id->memory on-navigate]]])
         ;; Relationships — scrollable, separate from hierarchy
         (when-let [rels (:relationships memory)]
           (when (seq rels)
             (let [my-id (:id node)]
               [:div {:class "border-t border-gray-700 pt-2"}
                [:span {:class "text-gray-500 text-xs uppercase tracking-wide block mb-1"}
                 (str "Relationships (" (count rels) ")")]
                [:ul {:class "space-y-1 overflow-y-auto" :style {:max-height "120px"}}
                 (for [rel rels]
                   (let [outgoing? (= my-id (:source-id rel))
                         other-id  (if outgoing? (:target-id rel) (:source-id rel))
                         direction (if outgoing? "\u2192" "\u2190")]
                     ^{:key (:id rel)}
                     [:li {:class "text-gray-300 flex items-start gap-1"}
                      [:span {:class "text-gray-500 flex-shrink-0"} (:type rel)]
                      [:span {:class "text-gray-600 flex-shrink-0"} direction]
                      [navigable-memory-link other-id id->memory on-navigate]]))]])))]
        [:div {:class "text-gray-500 text-xs border-t border-gray-700 pt-2"} "Loading..."])]]))

;; ---------------------------------------------------------------------------
;; Force graph (Form-3 component)
;; ---------------------------------------------------------------------------

(defn- force-graph-inner []
  (let [node-positions    (r/atom {})
        simulation-ref    (atom nil)
        dragging-ref      (atom nil)
        hovered-node-id   (r/atom nil)
        selected-node-id  (r/atom nil)
        expanded-clusters (r/atom #{})
        zoom-transform    (r/atom nil)
        zoom-params       (r/atom {:k 1 :x 0 :y 0})
        prev-zoom-band    (atom nil)
        prev-data-sig     (atom nil)
        raf-pending?      (atom false)

        make-on-tick
        (fn [sim]
          (fn []
            (when-not @raf-pending?
              (reset! raf-pending? true)
              (js/requestAnimationFrame
               (fn []
                 (reset! raf-pending? false)
                 (let [nodes (.nodes sim)]
                   (reset! node-positions
                           (into {} (map (fn [n]
                                           [(.-id n) {:x (.-x n) :y (.-y n)}]))
                                 nodes))))))))

        setup-simulation!
        (fn [nodes links]
          (when @simulation-ref (stop-simulation! @simulation-ref))
          (let [positions @node-positions
                js-nodes (clj->js (mapv (fn [n]
                                          (let [pos (get positions (:id n))]
                                            (cond-> {:id (:id n) :r (:r n)}
                                              pos (assoc :x (:x pos) :y (:y pos)))))
                                        nodes))
                js-links (clj->js (mapv (fn [l] {:source (:source l) :target (:target l)}) links))
                sim      (create-simulation! js-nodes js-links (fn []))]
            (reset! simulation-ref sim)
            (.on sim "tick" (make-on-tick sim))
            sim))

        setup-zoom!
        (fn [node-count]
          (let [svg-node (.getElementById js/document graph-svg-id)
                max-z    (adaptive-max-zoom node-count)
                zoom-behavior
                (-> (d3-zoom/zoom)
                    (.scaleExtent #js [0.1 max-z])
                    (.filter (fn [event]
                               (let [evt-type (.-type event)
                                     target   (.-target event)]
                                 (if (or (= evt-type "mousedown")
                                         (= evt-type "touchstart"))
                                   ;; Block pan on nodes and inside tooltips
                                   (and (nil? (.closest target ".cursor-pointer"))
                                        (nil? (.closest target "foreignObject")))
                                   ;; Allow wheel zoom, etc. everywhere
                                   true))))
                    (.on "zoom"
                         (fn [event]
                           (let [transform (.-transform event)]
                             (reset! zoom-transform (.toString transform))
                             (reset! zoom-params {:k (.-k transform)
                                                  :x (.-x transform)
                                                  :y (.-y transform)})
                             (rf/dispatch-sync [:graph/set-zoom-level (.-k transform)])))))]
            (-> (d3-selection/select svg-node)
                (.call zoom-behavior))
            (reset! zoom-behavior-ref zoom-behavior)))

        zoom-band
        (fn [level n]
          (cond
            (< level 0.4)                               :layer-clusters
            (and (< level 0.7) (> n cluster-threshold)) :parent-clusters
            :else                                        :full-detail))

        screen->sim-coords
        (fn [evt]
          (let [svg-el   (.getElementById js/document graph-svg-id)
                svg-rect (.getBoundingClientRect svg-el)
                scale-x  (/ 800 (.-width svg-rect))
                scale-y  (/ 600 (.-height svg-rect))
                vx       (* (- (.-clientX evt) (.-left svg-rect)) scale-x)
                vy       (* (- (.-clientY evt) (.-top svg-rect)) scale-y)
                transform (d3-zoom/zoomTransform svg-el)]
            (if transform
              (let [inv (.invert transform #js [vx vy])]
                {:x (aget inv 0) :y (aget inv 1)})
              {:x vx :y vy})))

        start-drag!
        (fn [node-id evt]
          (.preventDefault evt)
          (.stopPropagation evt)
          (reset! dragging-ref node-id)
          (when-let [sim @simulation-ref]
            (.alphaTarget sim 0.3)
            (.restart sim)
            (let [nodes (.nodes sim)]
              (doseq [n nodes]
                (when (= (.-id n) node-id)
                  (set! (.-fx n) (.-x n))
                  (set! (.-fy n) (.-y n)))))))

        on-drag-move!
        (fn [evt]
          (when-let [node-id @dragging-ref]
            (.preventDefault evt)
            (when-let [sim @simulation-ref]
              (let [{:keys [x y]} (screen->sim-coords evt)
                    nodes (.nodes sim)]
                (doseq [n nodes]
                  (when (= (.-id n) node-id)
                    (set! (.-fx n) x)
                    (set! (.-fy n) y)))))))

        on-drag-end!
        (fn [_evt]
          (when-let [_node-id @dragging-ref]
            (reset! dragging-ref nil)
            (when-let [sim @simulation-ref]
              (.alphaTarget sim 0))))

        select-node!
        (fn [node-id]
          (reset! selected-node-id node-id)
          (rf/dispatch [:fetch-memory node-id])
          (rf/dispatch [:fetch-memory-relationships node-id])
          (.replaceState js/window.history nil "" (str "/graph?node=" node-id)))

        deselect-node!
        (fn []
          (reset! selected-node-id nil)
          (rf/dispatch [:close-memory-detail])
          (.replaceState js/window.history nil "" "/graph"))

        handle-node-click!
        (fn [node e]
          (.stopPropagation e)
          (if (:cluster? node)
            ;; Toggle cluster expansion, then zoom to fit
            (do (swap! expanded-clusters
                       (fn [s]
                         (if (contains? s (:id node))
                           (disj s (:id node))
                           (conj s (:id node)))))
                (when-let [zb @zoom-behavior-ref]
                  (let [svg (d3-selection/select (str "#" graph-svg-id))]
                    (.scaleTo zb svg 1)
                    (.translateTo zb svg 400 300))))
            ;; Pin tooltip and fetch full detail
            (if (= @selected-node-id (:id node))
              (deselect-node!)
              (select-node! (:id node)))))]

    (r/create-class
     {:display-name "force-graph"

      :component-did-mount
      (fn [this]
        (let [[_ memories graph] (r/argv this)
              rels     (:relationships graph)
              zoom-lvl (:zoom-level graph)
              {:keys [nodes links]} (compute-display-data memories rels zoom-lvl @expanded-clusters)
              sig [(set (map :id nodes)) (count links)]]
          (reset! select-node-fn! select-node!)
          (setup-zoom! (count memories))
          (reset! prev-zoom-band (zoom-band zoom-lvl (count memories)))
          (reset! prev-data-sig sig)
          (setup-simulation! nodes links)
          ;; Auto-select node from URL ?node= param
          (let [params  (js/URLSearchParams. (.-search js/window.location))
                node-id (.get params "node")]
            (when node-id
              ;; Delay to let the simulation settle before panning
              (js/setTimeout #(select-node! node-id) 1500)))))

      :component-did-update
      (fn [this _old-argv]
        (let [[_ memories graph] (r/argv this)
              rels     (:relationships graph)
              zoom-lvl (:zoom-level graph)
              {:keys [nodes links]} (compute-display-data memories rels zoom-lvl @expanded-clusters)
              new-band (zoom-band zoom-lvl (count memories))
              new-sig  [(set (map :id nodes)) (count links)]
              band-changed? (not= new-band @prev-zoom-band)
              data-changed? (not= new-sig @prev-data-sig)]
          (when (or band-changed? data-changed?)
            (reset! prev-zoom-band new-band)
            (reset! prev-data-sig new-sig)
            (let [positions @node-positions]
              (if @simulation-ref
                (let [js-nodes (clj->js (mapv (fn [n]
                                                (let [pos (get positions (:id n))]
                                                  (cond-> {:id (:id n) :r (:r n)}
                                                    pos (assoc :x (:x pos) :y (:y pos)))))
                                              nodes))
                      js-links (clj->js (mapv (fn [l] {:source (:source l) :target (:target l)}) links))]
                  (restart-simulation! @simulation-ref js-nodes js-links
                                       (if band-changed? 0.1 0.3))
                  (.on @simulation-ref "tick" (make-on-tick @simulation-ref)))
                (setup-simulation! nodes links))))))

      :component-will-unmount
      (fn [_]
        (reset! select-node-fn! nil)
        (when @simulation-ref
          (stop-simulation! @simulation-ref)
          (reset! simulation-ref nil))
        ;; Clean up selected-memory state when leaving graph
        (when @selected-node-id
          (rf/dispatch [:close-memory-detail])))

      :reagent-render
      (fn [memories graph]
        (let [rels      (:relationships graph)
              zoom-lvl  (:zoom-level graph)
              highlighted-ids (:highlighted-ids graph)
              highlighted-rel-ids (set (:highlighted-rel-ids graph))
              has-highlights? (seq highlighted-ids)
              {:keys [nodes links]} (compute-display-data memories rels zoom-lvl @expanded-clusters)
              positions @node-positions
              hovered   @hovered-node-id
              selected  @selected-node-id
              node-map  (into {} (map (fn [n] [(:id n) n]) nodes))]
          [:svg {:id             graph-svg-id
                 :viewBox        "0 0 800 600"
                 :class          "absolute inset-0 w-full h-full"
                 :role           "img"
                 :aria-label     "Memory relationship graph"
                 :style          {:user-select "none" :-webkit-user-select "none"}
                 :on-mouse-move  on-drag-move!
                 :on-mouse-up    on-drag-end!
                 :on-click       (fn [_] (when @selected-node-id (deselect-node!)))
                 :on-mouse-leave (fn [e]
                                   (on-drag-end! e)
                                   (reset! hovered-node-id nil))}
           ;; SVG defs: per-color arrowheads + highlight glow
           [:defs
            [:marker {:id           "arrowhead"
                      :viewBox      "0 0 10 6"
                      :refX         10
                      :refY         3
                      :markerWidth  8
                      :markerHeight 6
                      :orient       "auto"
                      :markerUnits  "strokeWidth"}
             [:path {:d    "M 0 0 L 10 3 L 0 6 Z"
                     :fill "#9ca3af"}]]
            (when has-highlights?
              [:filter {:id "glow" :x "-50%" :y "-50%" :width "200%" :height "200%"}
               [:feGaussianBlur {:stdDeviation "3" :result "blur"}]
               [:feMerge
                [:feMergeNode {:in "blur"}]
                [:feMergeNode {:in "SourceGraphic"}]]])]
           [:g (cond-> {:id "graph-viewport"}
                 @zoom-transform (assoc :transform @zoom-transform))
            ;; Edge lines — rendered first so nodes paint on top
            [:g {:data-testid "graph-edges"}
             (for [link links
                   :let [src-pos (get positions (:source link))
                         tgt-pos (get positions (:target link))]
                   :when (and src-pos tgt-pos)]
               (let [rel-type  (:rel-type link)
                     rel-kw    (when rel-type (keyword rel-type))
                     color     (if rel-kw (colors/relationship-color rel-kw) "#9ca3af")
                     tgt-node  (get node-map (:target link))
                     tgt-r     (or (:r tgt-node) 20)
                     {:keys [x2 y2]} (shorten-line (:x src-pos) (:y src-pos)
                                                   (:x tgt-pos) (:y tgt-pos)
                                                   tgt-r)
                     edge-highlighted? (contains? highlighted-rel-ids (:id link))
                     edge-dimmed?      (and has-highlights? (not edge-highlighted?))]
                 ^{:key (or (:id link) (str (:source link) "-" (:target link)))}
                 [:line {:x1           (:x src-pos) :y1 (:y src-pos)
                         :x2           x2 :y2 y2
                         :stroke       color
                         :stroke-width (if edge-highlighted? 3 1.5)
                         :opacity      (cond edge-highlighted? 0.9
                                             edge-dimmed?      0.1
                                             :else             0.6)
                         :marker-end   "url(#arrowhead)"
                         :filter       (when edge-highlighted? "url(#glow)")
                         :data-testid  "graph-edge"}]))]
            ;; Nodes group — rendered after edges so they appear on top
            [:g {:data-testid "graph-nodes"}
             (for [node nodes
                   :let [pos (get positions (:id node))
                         highlighted? (contains? highlighted-ids (:id node))
                         selected-node? (= selected (:id node))
                         dimmed? (and has-highlights? (not highlighted?))]
                   :when pos]
               ^{:key (:id node)}
               [:g {:on-mouse-down  (partial start-drag! (:id node))
                    :on-click       (partial handle-node-click! node)
                    :on-mouse-enter #(reset! hovered-node-id (:id node))
                    :on-mouse-leave #(reset! hovered-node-id nil)
                    :class          "cursor-pointer"}
                ;; Highlight ring for selected or highlighted nodes
                (when (or highlighted? selected-node?)
                  [:circle {:cx           (:x pos)
                            :cy           (:y pos)
                            :r            (+ (:r node) 4)
                            :fill         "none"
                            :stroke       (:color node)
                            :stroke-width 2.5
                            :opacity      0.7
                            :filter       "url(#glow)"}])
                ;; Single node circle — dimmed nodes use muted fill + low opacity
                [:circle {:cx      (:x pos)
                          :cy      (:y pos)
                          :r       (:r node)
                          :fill    (if dimmed? "#4b5563" (:color node))
                          :opacity (if dimmed? 0.25 1)}]
                (when (:cluster? node)
                  [:circle {:cx           (+ (:x pos) (* (:r node) 0.6))
                            :cy           (- (:y pos) (* (:r node) 0.6))
                            :r            10
                            :fill         "#374151"
                            :stroke       "#9ca3af"
                            :stroke-width 1}])
                (when (:cluster? node)
                  (let [count-text (first (re-seq #"\d+" (:content node)))]
                    [:text {:x           (+ (:x pos) (* (:r node) 0.6))
                            :y           (+ (- (:y pos) (* (:r node) 0.6)) 4)
                            :text-anchor "middle"
                            :font-size   "9"
                            :font-weight "bold"
                            :fill        "white"
                            :class       "pointer-events-none select-none"}
                     count-text]))
                ;; In-node label — word-wrapped to fit circle
                (when (and (> zoom-lvl 0.5) (not dimmed?))
                  (let [label (or (:display-title node) (:content node))
                        lines (wrap-label-for-circle label (:r node))]
                    [:text {:x                 (:x pos)
                            :y                 (:y pos)
                            :text-anchor       "middle"
                            :dominant-baseline "central"
                            :font-size         "8"
                            :fill              "white"
                            :class             "pointer-events-none select-none"}
                     (for [{:keys [text dy]} lines]
                       ^{:key dy}
                       [:tspan {:x (:x pos)
                                :y (+ (:y pos) dy)}
                        text])]))])]
            ;; Edge labels — only shown for edges connected to the selected node
            ;; to avoid cluttering the graph with dozens of overlapping labels
            (when (and selected (> zoom-lvl 0.7))
              [:g {:data-testid "graph-edge-labels"}
               (for [link links
                     :let [src-pos (get positions (:source link))
                           tgt-pos (get positions (:target link))]
                     :when (and src-pos tgt-pos (:rel-type link)
                                (or (= selected (:source link))
                                    (= selected (:target link))))]
                 (let [mid   (edge-midpoint src-pos tgt-pos)
                       dx    (- (:x tgt-pos) (:x src-pos))
                       dy    (- (:y tgt-pos) (:y src-pos))
                       angle (-> (Math/atan2 dy dx) (* (/ 180 Math/PI)))
                       ;; Flip if text would render upside-down
                       angle (if (or (> angle 90) (< angle -90))
                               (+ angle 180)
                               angle)]
                   ^{:key (str "label-" (or (:id link) (str (:source link) "-" (:target link))))}
                   [:text {:x            (:x mid)
                           :y            (- (:y mid) 4)
                           :text-anchor  "middle"
                           :font-size    "8"
                           :fill         "#9ca3af"
                           :opacity      0.8
                           :transform    (str "rotate(" angle "," (:x mid) "," (:y mid) ")")
                           :data-testid  "edge-label"
                           :class        "pointer-events-none select-none"}
                    (:rel-type link)]))])] ;; end graph-viewport group
           ;; Tooltip / detail card — rendered in SVG root coords, zoom-independent
           ;; Selected (pinned) takes precedence over hovered
           (if-let [s-node (and selected (get node-map selected))]
             (when-let [s-pos (get positions selected)]
               (let [id->memory (into {} (map (fn [m] [(:id m) m]) memories))]
                 [node-tooltip-expanded s-node s-pos @zoom-params
                  (fn [e]
                    (.stopPropagation e)
                    (deselect-node!))
                  select-node!
                  id->memory]))
             (when-let [h-node (and hovered (not selected) (get node-map hovered))]
               (when-let [h-pos (get positions hovered)]
                 [node-tooltip-compact h-node h-pos @zoom-params])))]))})))

(defn force-graph []
  (let [memories @(rf/subscribe [:visible-graph-memories])
        graph    @(rf/subscribe [:graph])]
    [force-graph-inner memories graph]))

;; ---------------------------------------------------------------------------
;; Graph panel + page
;; ---------------------------------------------------------------------------

(defn graph-panel []
  (let [graph @(rf/subscribe [:graph])]
    (if (:loading? graph)
      [loading/spinner]
      [:div {:class "flex-1 min-h-0 relative w-full bg-white dark:bg-gray-800 rounded-lg border border-gray-200 dark:border-gray-700 p-4"
             :data-testid "graph-container"}
       [force-graph]])))

;; ---------------------------------------------------------------------------
;; Recall input bar
;; ---------------------------------------------------------------------------

(defn recall-input-bar []
  (let [recall @(rf/subscribe [:graph/recall-state])
        query  (:query recall)
        loading? (:loading? recall)]
    [:form {:class    "flex gap-2"
            :on-submit (fn [e]
                         (.preventDefault e)
                         (rf/dispatch [:graph/recall!]))}
     [:input {:type        "text"
              :value       (or query "")
              :placeholder "Ask a question about your memories..."
              :on-change   #(rf/dispatch [:graph/set-recall-query (.. % -target -value)])
              :class       "flex-1 px-3 py-2 text-sm rounded-lg border border-gray-300 dark:border-gray-600 bg-white dark:bg-gray-800 text-gray-900 dark:text-gray-100 placeholder-gray-400 dark:placeholder-gray-500 focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:border-transparent"
              :data-testid "graph-recall-input"}]
     [:button {:type     "submit"
               :disabled loading?
               :class    (str "px-4 py-2 text-sm font-medium rounded-lg transition-colors cursor-pointer "
                              (if loading?
                                "bg-gray-400 text-gray-200 cursor-not-allowed"
                                "bg-indigo-600 text-white hover:bg-indigo-700"))}
      (if loading? "Searching..." "Ask")]]))

;; ---------------------------------------------------------------------------
;; Recall results panel
;; ---------------------------------------------------------------------------

(defn- recall-result-card [memory]
  (let [layer-kw (keyword "layer" (:layer memory))]
    [:div {:class    "flex items-start gap-2 p-2 rounded-md bg-gray-50 dark:bg-gray-700/50 cursor-pointer hover:bg-gray-100 dark:hover:bg-gray-600/50 transition-colors"
           :on-click (fn [_]
                       (when-let [f @select-node-fn!]
                         (f (:memory-id memory))))}
     [:div {:class  "w-2 h-2 rounded-full mt-1.5 shrink-0"
            :style  {:background-color (get colors/layer-colors layer-kw "#9ca3af")}}]
     [:div {:class "flex-1 min-w-0"}
      [:p {:class "text-xs text-gray-900 dark:text-gray-100 line-clamp-2"}
       (:content memory)]
      [:div {:class "flex gap-2 mt-1"}
       [:span {:class "text-[10px] font-medium px-1.5 py-0.5 rounded"
               :style {:background-color (str (get colors/layer-colors layer-kw "#9ca3af") "20")
                       :color (get colors/layer-colors layer-kw "#9ca3af")}}
        (get colors/layer-names layer-kw (:layer memory))]
       [:span {:class "text-[10px] text-gray-400 dark:text-gray-500"}
        (str "d=" (.toFixed (or (:distance memory) 0) 3))]]]]))

(defn recall-answer-bar []
  (let [recall @(rf/subscribe [:graph/recall-state])
        answer (get-in recall [:results :answer])]
    (when answer
      [:div {:class    "p-3 text-sm text-gray-800 dark:text-gray-200 bg-blue-50 dark:bg-blue-900/30 rounded-lg border border-blue-200 dark:border-blue-800"
             :data-testid "recall-answer"}
       answer])))

(defn recall-results-panel []
  (let [recall  @(rf/subscribe [:graph/recall-state])
        graph   @(rf/subscribe [:graph])
        results (:results recall)
        error   (:error recall)
        active-count (count (:highlighted-ids graph))]
    (when (or results error)
      [:div {:class    "absolute bottom-4 right-4 w-80 bg-white dark:bg-gray-800 rounded-lg border border-gray-200 dark:border-gray-700 shadow-lg z-10 flex flex-col"
             :style    {:max-height "50%"}
             :data-testid "recall-results-panel"}
       [:div {:class "shrink-0 flex items-center justify-between px-3 py-2 border-b border-gray-200 dark:border-gray-700"}
        [:span {:class "text-xs font-medium text-gray-700 dark:text-gray-300"}
         (if error
           "Recall failed"
           (str active-count " activated memories"))]
        [:button {:on-click #(rf/dispatch [:graph/clear-highlights])
                  :class    "text-gray-400 hover:text-gray-600 dark:hover:text-gray-300 cursor-pointer"
                  :aria-label "Close results"}
         "\u00d7"]]
       [:div {:class "overflow-y-auto min-h-0"}
        (if error
          [:div {:class "p-3 text-xs text-red-600 dark:text-red-400"}
           (str "Error: " (or (:status-text error) "Unknown error"))]
          (if (seq (:memories results))
            [:div {:class "p-2 space-y-1"}
             (for [memory (:memories results)]
               ^{:key (:memory-id memory)}
               [recall-result-card memory])]
            [:div {:class "p-3 text-xs text-gray-500 dark:text-gray-400 text-center"}
             "No matching memories found"]))]])))

(defn- consolidate-controls []
  (let [selected (r/atom "24h")]
    (fn []
      (let [reflecting? @(rf/subscribe [:reflect-loading?])
            since-ms    (case @selected
                          "1h"  (* 1 60 60 1000)
                          "24h" (* 24 60 60 1000)
                          "7d"  (* 7 24 60 60 1000)
                          "30d" (* 30 24 60 60 1000)
                          "all" nil)
            since-val   (when since-ms
                          (- (.getTime (js/Date.)) since-ms))]
        [:div {:class "flex items-center gap-2"}
         [:select {:class     "px-2 py-1.5 text-xs rounded-full bg-gray-100 dark:bg-gray-700
                               text-gray-700 dark:text-gray-300 border-0 cursor-pointer"
                   :value     @selected
                   :on-change #(reset! selected (.. % -target -value))}
          [:option {:value "1h"} "Last hour"]
          [:option {:value "24h"} "Last 24h"]
          [:option {:value "7d"} "Last 7 days"]
          [:option {:value "30d"} "Last 30 days"]
          [:option {:value "all"} "All time"]]
         [:button {:on-click #(rf/dispatch [:reflect! since-val])
                   :disabled reflecting?
                   :class    (str "px-3 py-1.5 text-xs font-medium rounded-full cursor-pointer "
                                  (if reflecting?
                                    "bg-gray-300 text-gray-500 dark:bg-gray-600"
                                    "bg-indigo-100 text-indigo-700 hover:bg-indigo-200
                                     dark:bg-indigo-900 dark:text-indigo-300 dark:hover:bg-indigo-800"))}
          (if reflecting? "Consolidating..." "Consolidate")]]))))

(defn page []
  (rf/dispatch [:fetch-graph-data])
  (fn []
    (let [graph @(rf/subscribe [:graph])
          has-highlights? (seq (:highlighted-ids graph))]
      [:div {:class "flex flex-col flex-1 min-h-0 gap-6"}
       [:div {:class "flex items-center justify-between"}
        [:h2 {:class "text-2xl font-bold text-gray-900 dark:text-gray-100"} "Memory Graph"]
        [:div {:class "flex items-center gap-4"}
         [consolidate-controls]
         (when has-highlights?
           [:button {:on-click #(rf/dispatch [:graph/clear-highlights])
                     :class    "px-3 py-1 text-xs font-medium text-gray-600 dark:text-gray-400 border border-gray-300 dark:border-gray-600 rounded-full hover:bg-gray-100 dark:hover:bg-gray-700 cursor-pointer"}
            "Clear highlights"])
         [zoom-controls]
         [layer-toggles]]]
       [recall-input-bar]
       [recall-answer-bar]
       [:div {:class "flex flex-col flex-1 min-h-0 relative"}
        [graph-panel]
        [recall-results-panel]]])))
