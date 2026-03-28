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
   [:auth [:map [:e2e-mode :boolean] [:api-key-hash [:maybe :string]]]]
   [:firebase {:optional true} [:map [:project-id :string]]]
   [:dynamodb {:optional true} [:map [:endpoint [:maybe :string]] [:region :string] [:table [:maybe :string]]]]
   [:rate-limit [:map [:enabled :boolean] [:max-requests :int] [:window-ms :int]]]
   [:cost {:optional true} [:map
                            [:embedding-per-1k-tokens :double]
                            [:chat-prompt-per-1k-tokens :double]
                            [:chat-completion-per-1k-tokens :double]]]
   [:tuning [:map
             [:retain-context-threshold :double]
             [:retain-context-limit :int]
             [:recall-default-limit :int]
             [:reflect-batch-size :int]
             [:reflect-default-threshold :double]
             [:memory-limit :int]]]])

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
  "Load configuration via Aero.
   Zero-arity reads from config.edn file (cloud mode).
   One-arity reads from a classpath resource (local mode)."
  ([]
   (-> (aero/read-config (io/file "config.edn"))
       resolve-prompts
       validate-config!))
  ([classpath-resource]
   (-> (aero/read-config (io/resource classpath-resource))
       expand-paths
       resolve-prompts
       validate-config!)))
