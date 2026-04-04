(ns memlayer.mcp.server
  "MCP server with stdio transport (JSON-RPC over stdin/stdout).
   Thin client that forwards tool calls to the memlayer HTTP server."
  (:gen-class)
  (:require [memlayer.cli :as cli]
            [memlayer.version :as version]
            [memlayer.mcp.protocol :as proto]
            [memlayer.mcp.tools :as tools]
            [memlayer.mcp.resources :as resources]
            [memlayer.mcp.client :as client]
            [memlayer.mcp.lifecycle :as lifecycle]
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

(def ^:private default-port 8090)

;; -- Method handlers --

(defmulti handle-method (fn [method _params _ctx] method))

(defmethod handle-method "initialize" [_ _params {:keys [active-namespace]}]
  (let [ns-name (when active-namespace @active-namespace)]
    {:protocolVersion "2025-03-26"
     :serverInfo      server-info
     :capabilities    capabilities
     :instructions    (if ns-name
                        (str (resources/instructions-text)
                             "\n\n## Active namespace\n\nYour current namespace is `"
                             ns-name "`. All memory operations are scoped to this namespace."
                             " To switch, call the `memlayer_set_namespace` tool.")
                        (resources/instructions-text))}))

(defmethod handle-method "notifications/initialized" [_ _ _]
  nil) ;; notification, no response

(defmethod handle-method "tools/list" [_ _params _ctx]
  {:tools tools/tool-definitions})

(defmethod handle-method "resources/list" [_ _params _ctx]
  {:resources resources/resource-definitions})

(defmethod handle-method "resources/read" [_ params _ctx]
  (resources/read-resource (:uri params)))

(defn- wrap-mcp-content
  "Wrap a result map as MCP tool content."
  [result]
  {:content [{:type "text"
              :text (j/write-value-as-string result json/mapper)}]})

(defmethod handle-method "tools/call" [_ params {:keys [base-url port active-namespace]}]
  (let [tool-name (:name params)
        arguments (:arguments params)
        ns-name   (if active-namespace @active-namespace (:namespace arguments))
        ;; Try the call, retry once if server went away
        call-with-retry
        (fn [f]
          (try
            (f base-url)
            (catch Exception e
              (log/warn "Tool call failed, attempting server restart:" (.getMessage e))
              (if-let [new-url (lifecycle/try-restart-server! port)]
                (f new-url)
                (throw (ex-info "Server unavailable" {:tool tool-name}))))))]
    (case tool-name
      "memlayer_set_namespace"
      (let [new-ns (:namespace arguments)]
        (if active-namespace
          (do (reset! active-namespace new-ns)
              (log/info "Namespace changed to:" new-ns)
              (wrap-mcp-content {:namespace new-ns
                                 :message   (str "Switched to namespace \"" new-ns "\". All operations now scoped to this namespace.")}))
          (throw (ex-info "set_namespace is only available in MCP stdio mode" {}))))

      "memlayer_retain"
      (wrap-mcp-content
       (call-with-retry
        #(client/retain! % {:content   (:content arguments)
                            :source    (:source arguments)
                            :namespace ns-name})))

      "memlayer_batch_retain"
      (wrap-mcp-content
       (call-with-retry
        #(client/batch-retain! % {:namespace ns-name
                                  :items     (:items arguments)})))

      "memlayer_recall"
      (wrap-mcp-content
       (call-with-retry
        #(client/recall! % {:query        (:query arguments)
                            :namespace    ns-name
                            :limit        (:limit arguments)
                            :as-of        (:as-of arguments)
                            :layer        (:layer arguments)
                            :expand-graph (:expand-graph arguments)})))

      "memlayer_forget"
      (wrap-mcp-content
       (call-with-retry
        #(client/forget! % {:memory-id (:memory-id arguments)})))

      "memlayer_reflect"
      (wrap-mcp-content
       (call-with-retry
        #(client/reflect! % {:dry-run   (:dry-run arguments)
                             :namespace ns-name
                             :phases    (:phases arguments)})))

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

(defn -main [& args]
  (let [info @version/build-info]
    (log/info (str "memlayer " (:version info) " (" (:git-sha info) ") built " (:built-at info))))
  (log/info "Starting memlayer MCP client (stdio)")
  (let [options          (cli/parse-and-validate! args "memlayer mcp - MCP stdio server")
        port             (or (:port options)
                             (some-> (System/getenv "MEMLAYER_PORT") parse-long)
                             default-port)
        namespace        (or (:namespace options)
                             (System/getProperty "memlayer.namespace")
                             "default")
        active-namespace (atom namespace)
        base-url         (lifecycle/ensure-server! port)]
    (log/info "Connected to memlayer server at" base-url "namespace:" namespace)
    (run-stdio! {:base-url         base-url
                 :port             port
                 :active-namespace active-namespace})))
