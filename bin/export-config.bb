#!/usr/bin/env bb
;; Reads resources/config.edn (Aero-tagged) and exports as JSON or shadow-cljs --config-merge EDN.
;;
;; Usage:
;;   bb bin/export-config.bb json          → non-secret config as JSON (for e2e tests)
;;   bb bin/export-config.bb shadow-edn   → shadow-cljs.edn content

(require '[cheshire.core :as json]
         '[clojure.edn :as edn])

(def readers
  "Aero-compatible tagged literal readers for Babashka."
  {'env     (fn [k] (System/getenv (str k)))
   'or      (fn [vs] (first (remove nil? vs)))
   'long    (fn [v] (when v (parse-long (str v))))
   'double  (fn [v] (when v (parse-double (str v))))
   'keyword (fn [v] (keyword (str v)))
   'boolean (fn [v] (cond (boolean? v) v
                          (string? v)  (= "true" v)
                          :else        (boolean v)))
   'join    (fn [vs] (apply str vs))
   'str     (fn [vs] (apply str vs))})

(defn read-config []
  (edn/read-string {:readers readers} (slurp "resources/config.edn")))

;; Keys safe to export (no secrets like API keys)
(def public-keys [:server :datahike :proximum :auth :tuning])

;; Base shadow-cljs config (static parts)
(def shadow-base
  {:deps         true
   :dep-aliases  [:dashboard]
   :nrepl        {:port 7002}
   :builds
   {:dashboard
    {:target     :browser
     :output-dir "resources/public/js/compiled"
     :asset-path "/js/compiled"
     :modules    {:main {:init-fn 'memlayer.dashboard.core/init}}
     :devtools   {:after-load 'memlayer.dashboard.core/after-load
                  :preloads   ['devtools.preload]}}
    :test
    {:target    :node-test
     :output-to "out/test/node-test.js"
     :ns-regexp "memlayer\\.dashboard\\..*-test$"
     :autorun   true}}})

(case (first *command-line-args*)
  "json"
  (let [cfg (select-keys (read-config) public-keys)]
    (println (json/generate-string (update cfg :auth dissoc :api-key-hash))))

  "shadow-edn"
  (let [c         (read-config)
        port      (get-in c [:server :port])
        dash-port (get-in c [:server :dashboard-port])]
    (prn (-> shadow-base
             (assoc :dev-http {dash-port {:root              "resources/public"
                                          :push-state/index  "index.html"}})
             (assoc-in [:builds :dashboard :closure-defines]
                       {'memlayer.dashboard.config/dev-api-port (str port)}))))

  (binding [*out* *err*]
    (println "Usage: bb bin/export-config.bb <json|shadow-edn>")
    (System/exit 1)))
