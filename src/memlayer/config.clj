(ns memlayer.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [malli.core :as m]
            [malli.error :as me]
            [memlayer.util.resources :as resources]))

(def Config
  "Malli schema for the application config map."
  [:map
   [:server [:map [:port :int] [:dashboard-port :int]]]
   [:datahike [:map [:backend :keyword] [:path :string]]]
   [:proximum [:map [:dim :int] [:capacity :int] [:backend :keyword] [:path :string]]]
   [:openai [:map [:api-key [:maybe :string]] [:base-url :string] [:embedding-model :string]]]
   [:groq [:map [:api-key [:maybe :string]] [:base-url :string] [:model :string]]]
   [:prompts [:map
              [:extraction :string]
              [:batch-extraction :string]
              [:decision :string]
              [:resolution :string]
              [:reflect :string]
              [:reflect-organize :string]
              [:reflect-organize-domains :string]
              [:reflect-summarize :string]
              [:reflect-connect :string]
              [:reflect-curate :string]
              [:recall :string]]]
   [:cost {:optional true} [:map
                            [:embedding-per-1k-tokens :double]
                            [:chat-prompt-per-1k-tokens :double]
                            [:chat-completion-per-1k-tokens :double]]]
   [:tuning [:map
             [:retain-context-threshold :double]
             [:retain-context-limit :int]
             [:recall-default-limit :int]
             [:reflect-batch-size :int]
             [:reflect-default-threshold :double]]]])

(defn validate-config!
  "Validate config against the Config schema. Throws ex-info with humanized errors on failure."
  [config]
  (when-not (m/validate Config config)
    (let [errors (me/humanize (m/explain Config config))]
      (throw (ex-info (str "Invalid configuration: " (pr-str errors))
                      {:errors errors}))))
  config)

(defn- resolve-prompts
  "Resolve prompt paths to their :system-prompt content."
  [config]
  (update config :prompts
          (fn [prompts]
            (update-vals prompts #(:system-prompt (resources/read-edn! %))))))

(defn- deep-merge
  "Recursively merge b into a. b values win for non-map leaves."
  [a b]
  (merge-with (fn [v1 v2]
                (if (and (map? v1) (map? v2))
                  (deep-merge v1 v2)
                  v2))
              a b))

(defn- expand-home
  "Replace leading ~ with user.home in string values."
  [s]
  (if (and (string? s) (.startsWith ^String s "~"))
    (str (System/getProperty "user.home") (.substring ^String s 1))
    s))

(defn- expand-paths
  "Expand ~ in known path fields."
  [config]
  (-> config
      (update-in [:datahike :path] expand-home)
      (update-in [:proximum :path] expand-home)))

(defn load-config
  "Load configuration via Aero from classpath resource.
   Precedence: config.edn defaults → env vars (#env) → CLI overrides.
   Zero-arity reads config.edn. Two-arity deep-merges overrides after Aero."
  ([] (load-config "config.edn" nil))
  ([classpath-resource] (load-config classpath-resource nil))
  ([classpath-resource overrides]
   (-> (aero/read-config (io/resource classpath-resource))
       (cond-> (seq overrides) (deep-merge overrides))
       expand-paths
       resolve-prompts
       validate-config!)))
