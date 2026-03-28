(ns memlayer.dashboard.components.layer-badge
  (:require [memlayer.domain.colors :as colors]))

(def layer-css
  {:layer/domain  "bg-purple-100 text-purple-800"
   :layer/concept "bg-blue-100 text-blue-800"
   :layer/fact    "bg-green-100 text-green-800"
   :layer/episode "bg-orange-100 text-orange-800"})

(defn layer-badge
  "Render a colored badge for a memory layer. Accepts keyword or string."
  [layer]
  (let [layer-kw (if (keyword? layer)
                   layer
                   (keyword "layer" (name layer)))
        css (get layer-css layer-kw "bg-gray-100 text-gray-800")
        label (get colors/layer-names layer-kw (name layer))]
    [:span {:class (str "inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium " css)}
     label]))
