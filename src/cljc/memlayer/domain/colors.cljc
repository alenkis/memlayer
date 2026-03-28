(ns memlayer.domain.colors
  "Shared layer colors, names, and sizes for backend serialization and frontend display.")

(def layer-colors
  {:layer/domain  "#8b5cf6"
   :layer/concept "#3b82f6"
   :layer/fact    "#22c55e"
   :layer/episode "#f97316"
   :layer/summary "#ec4899"})

(def layer-names
  {:layer/domain  "Domain"
   :layer/concept "Concept"
   :layer/fact    "Fact"
   :layer/episode "Episode"
   :layer/summary "Summary"})

(def layer-sizes
  {:layer/domain  40
   :layer/concept 30
   :layer/fact    20
   :layer/episode 15
   :layer/summary 25})

(def relationship-colors
  "Colors for common relationship types."
  {:supports    "#22c55e"
   :elaborates  "#3b82f6"
   :contradicts "#ef4444"
   :caused-by   "#f97316"
   :refines     "#06b6d4"
   :related-to  "#8b5cf6"
   :example-of  "#ec4899"})

(def ^:private fallback-palette
  ["#6366f1" "#14b8a6" "#f59e0b" "#10b981" "#a855f7" "#f43f5e" "#0ea5e9" "#84cc16"])

(defn relationship-color
  "Get color for a relationship type keyword. Falls back to a deterministic
   palette selection for unknown free-form types."
  [type-kw]
  (or (get relationship-colors type-kw)
      (nth fallback-palette
           (mod (Math/abs (hash (name (or type-kw :unknown))))
                (count fallback-palette)))))
