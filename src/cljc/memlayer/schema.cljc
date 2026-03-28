(ns memlayer.schema
  "Shared schema constants and malli types for namespace validation.")

(def default-namespace "default")

(def namespace-pattern #"[a-z0-9-]{1,64}")

(def NamespaceSchema
  "Malli schema for a valid namespace string: lowercase alphanumeric + hyphens, 1-64 chars."
  [:and :string [:re (str namespace-pattern)]])
