(ns memlayer.dashboard.components.chart
  (:require [reagent.core :as r]
            ["chart.js/auto" :as Chart]))

(defn- format-tokens [n]
  (cond
    (>= n 1000000) (str (.toFixed (/ n 1000000) 1) "M")
    (>= n 1000)    (str (.toFixed (/ n 1000) 1) "K")
    :else          (str n)))

(defn- build-chart-config
  "Build Chart.js config from data, x-key, and bar specs."
  [data x-key bars]
  ;; For date labels like "2026-03-15", drop the year prefix → "03-15"
  (let [labels   (mapv #(let [label (str (get % x-key ""))]
                          (if (> (count label) 5) (subs label 5) label))
                       data)
        datasets (mapv (fn [{:keys [key color label]}]
                         #js {:label           label
                              :data            (clj->js (mapv #(get % key 0) data))
                              :backgroundColor color
                              :borderRadius    2
                              :barPercentage   0.8})
                       bars)]
    #js {:type "bar"
         :data #js {:labels   (clj->js labels)
                    :datasets (clj->js datasets)}
         :options #js {:responsive true
                       :maintainAspectRatio false
                       :plugins #js {:legend #js {:position "bottom"
                                                  :labels #js {:usePointStyle true
                                                               :padding 20}}}
                       :scales #js {:x #js {:stacked true
                                            :grid #js {:display false}
                                            :ticks #js {:maxRotation 45}}
                                    :y #js {:stacked true
                                            :ticks #js {:callback (fn [value _idx _ticks]
                                                                    (format-tokens value))}}}}}))

(defn- update-chart! [chart-instance data x-key bars]
  (when chart-instance
    (let [labels   (mapv #(let [label (str (get % x-key ""))]
                            (if (> (count label) 5) (subs label 5) label))
                         data)
          datasets (mapv (fn [{:keys [key color label]}]
                           #js {:label           label
                                :data            (clj->js (mapv #(get % key 0) data))
                                :backgroundColor color
                                :borderRadius    2
                                :barPercentage   0.8})
                         bars)]
      (set! (.-labels (.-data chart-instance)) (clj->js labels))
      (set! (.-datasets (.-data chart-instance)) (clj->js datasets))
      (.update chart-instance))))

(defn bar-chart
  "Chart.js bar chart.
   - data:   vector of maps, each a data point
   - x-key:  keyword for x-axis labels
   - bars:   vector of {:key keyword :color string :label string}
   - height: container height (default 300)"
  [{:keys [_data _x-key _bars _height]}]
  (let [chart-instance (atom nil)
        canvas-ref     (atom nil)]
    (r/create-class
     {:display-name "chart-js-bar"

      :component-did-mount
      (fn [this]
        (when @canvas-ref
          (let [[_ {:keys [data x-key bars]}] (r/argv this)
                ctx    (.getContext @canvas-ref "2d")
                config (build-chart-config data x-key bars)]
            (reset! chart-instance (Chart. ctx config)))))

      :component-did-update
      (fn [this _old-argv]
        (let [[_ {:keys [data x-key bars]}] (r/argv this)]
          (update-chart! @chart-instance data x-key bars)))

      :component-will-unmount
      (fn [_]
        (when @chart-instance
          (.destroy @chart-instance)
          (reset! chart-instance nil)))

      :reagent-render
      (fn [{:keys [height]
            :or   {height 300}}]
        [:div {:style {:width "100%" :height (str height "px")}}
         [:canvas {:ref        #(reset! canvas-ref %)
                   :role       "img"
                   :aria-label "Daily token usage chart"}]])})))
