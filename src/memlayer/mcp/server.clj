(ns memlayer.mcp.server
  "MCP server with stdio transport (JSON-RPC over stdin/stdout)."
  (:gen-class)
  (:require [memlayer.version :as version]
            [memlayer.mcp.protocol :as proto]
            [memlayer.mcp.tools :as tools]
            [memlayer.mcp.resources :as resources]
            [memlayer.operations.flow.retention-flow :as retention-flow]
            [memlayer.operations.recall :as recall]
            [memlayer.operations.forget :as forget]
            [memlayer.operations.reflect :as reflect]
            [memlayer.config :as config]
            [memlayer.json :as json]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [jsonista.core :as j])
  (:import [java.io BufferedReader InputStreamReader]))

(def ^:private server-info
  {:name    "memlayer"
   :version (version/version)})

(def ^:private capabilities
  {:tools     {}
   :resources {}})

;; -- Method handlers --

(defmulti handle-method (fn [method _params _ctx] method))

(defmethod handle-method "initialize" [_ _params _ctx]
  {:protocolVersion "2025-03-26"
   :serverInfo      server-info
   :capabilities    capabilities
   :instructions    (resources/instructions-text)})

(defmethod handle-method "notifications/initialized" [_ _ _]
  nil) ;; notification, no response

(defmethod handle-method "tools/list" [_ _params _ctx]
  {:tools tools/tool-definitions})

(defmethod handle-method "resources/list" [_ _params _ctx]
  {:resources resources/resource-definitions})

(defmethod handle-method "resources/read" [_ params _ctx]
  (resources/read-resource (:uri params)))

(defmethod handle-method "tools/call" [_ params {:keys [flow deps]}]
  (let [tool-name (:name params)
        arguments (:arguments params)]
    (case tool-name
      "memlayer_retain"
      (let [result (retention-flow/submit! flow
                                           {:items     [{:content (:content arguments)
                                                         :source  (:source arguments)}]
                                            :namespace (:namespace arguments)
                                            :source    (:source arguments)})]
        {:content [{:type "text"
                    :text (j/write-value-as-string
                           {:memory-ids (mapv str (:memory-ids result))
                            :decisions  (mapv (fn [d]
                                                (cond-> {:type    (:type d)
                                                         :content (:content d)}
                                                  (:memory-id d) (assoc :memory-id (str (:memory-id d)))))
                                              (:decisions result))}
                           json/mapper)}]})

      "memlayer_batch_retain"
      (let [result         (retention-flow/submit! flow
                                                   {:items     (:items arguments)
                                                    :namespace (:namespace arguments)})
            reflect-result (when (and result (seq (:decisions result)))
                             (try
                               (reflect/reflect! deps {:dry-run false :namespace (:namespace arguments)})
                               (catch Exception e
                                 (log/warn "Post-batch reflect failed:" (.getMessage e))
                                 nil)))]
        {:content [{:type "text"
                    :text (j/write-value-as-string
                           (cond-> {:memory-ids (mapv str (:memory-ids result))
                                    :decisions  (mapv (fn [d]
                                                        (cond-> {:type    (:type d)
                                                                 :content (:content d)}
                                                          (:memory-id d) (assoc :memory-id (str (:memory-id d)))))
                                                      (:decisions result))
                                    :usage      (:usage result)}
                             reflect-result (assoc :reflect reflect-result))
                           json/mapper)}]})

      "memlayer_recall"
      (let [result (recall/recall! deps {:query        (:query arguments)
                                         :namespace    (:namespace arguments)
                                         :limit        (:limit arguments)
                                         :as-of        (:as-of arguments)
                                         :layer        (:layer arguments)
                                         :expand-graph (:expand-graph arguments)})]
        {:content [{:type "text"
                    :text (j/write-value-as-string result json/mapper)}]})

      "memlayer_forget"
      (let [result (forget/forget! deps {:memory-id (parse-uuid (:memory-id arguments))})]
        {:content [{:type "text"
                    :text (j/write-value-as-string result json/mapper)}]})

      "memlayer_reflect"
      (let [result (reflect/reflect! deps {:dry-run   (:dry-run arguments)
                                           :query     (:query arguments)
                                           :threshold (:threshold arguments)
                                           :namespace (:namespace arguments)
                                           :phases    (:phases arguments)})]
        {:content [{:type "text"
                    :text (j/write-value-as-string result json/mapper)}]})

      ;; Unknown tool
      (throw (ex-info "Unknown tool" {:tool-name tool-name})))))

(defmethod handle-method :default [method _params _ctx]
  (throw (ex-info "Method not found" {:method method})))

;; -- Message dispatch --

(defn dispatch
  "Dispatch a parsed JSON-RPC message. Returns a response map or nil for notifications."
  [message ctx]
  (let [{:keys [id method params]} message]
    (try
      (let [result (handle-method method params ctx)]
        (when id ;; only respond to requests, not notifications
          (proto/success-response id result)))
      (catch Exception e
        (let [data (ex-data e)]
          (log/error e "Error handling MCP method" method)
          (when id
            (proto/error-response id
                                  (if (= "Method not found" (.getMessage e))
                                    proto/method-not-found
                                    proto/internal-error)
                                  (.getMessage e)
                                  data)))))))

;; -- Stdio transport --

(defn- read-line-blocking
  "Read a line from stdin. Returns nil on EOF."
  [^BufferedReader reader]
  (.readLine reader))

(defn run-stdio!
  "Run the MCP server reading JSON-RPC from stdin, writing to stdout.
   Blocks until EOF on stdin."
  [ctx]
  (log/info "Starting MCP stdio server")
  (let [reader (BufferedReader. (InputStreamReader. System/in))]
    (loop []
      (when-let [line (read-line-blocking reader)]
        (when-not (str/blank? line)
          (let [message  (proto/parse-message line)
                response (if (:error message)
                           (proto/error-response nil proto/parse-error
                                                 (get-in message [:error :message]))
                           (dispatch message ctx))]
            (when response
              (let [json-str (proto/encode response)]
                (locking System/out
                  (println json-str)
                  (flush))))))
        (recur)))))

(defn -main [& _args]
  (let [info @version/build-info]
    (log/info (str "memlayer " (:version info) " (" (:git-sha info) ") built " (:built-at info))))
  (log/info "Starting memlayer MCP server (stdio)")
  (let [start-mcp-system! (requiring-resolve 'memlayer.system/start-mcp-system!)
        stop-system!      (requiring-resolve 'memlayer.system/stop-system!)
        system            (start-mcp-system!)
        cfg               (config/load-config)
        db                (:persistence/datahike system)
        deps              {:db                 db
                           :vector-index       (:persistence/proximum system)
                           :embedding-provider (:provider/openai system)
                           :chat-provider      (:provider/groq system)
                           :prompts            (:prompts cfg)
                           :tuning             (:tuning cfg)}
        flow              (retention-flow/start-standalone! deps cfg)]
    (.addShutdownHook (Runtime/getRuntime)
                      (Thread. ^Runnable (fn []
                                           (retention-flow/stop-standalone! flow)
                                           (stop-system! system))))
    (run-stdio! {:flow flow :deps deps})))
