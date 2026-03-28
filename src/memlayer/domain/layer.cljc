(ns memlayer.domain.layer
  "Semantic layer hierarchy for memories.
   Domain (L0) → Concept (L1) → Fact (L2) → Episode (L3)")

(def layers
  "Ordered from most abstract to most concrete."
  [:layer/domain :layer/concept :layer/fact :layer/episode])

(def layer-set
  "All valid layers, including non-hierarchical ones."
  (conj (set layers) :layer/summary))

(def layer->level
  {:layer/domain  0
   :layer/concept 1
   :layer/fact    2
   :layer/episode 3})

(defn valid-layer? [l] (contains? layer-set l))

(defn parent-layer
  "Returns the parent (more abstract) layer, or nil for domain."
  [l]
  (case l
    :layer/concept :layer/domain
    :layer/fact    :layer/concept
    :layer/episode :layer/fact
    nil))
