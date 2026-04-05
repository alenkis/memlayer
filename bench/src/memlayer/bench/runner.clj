(ns memlayer.bench.runner
  "Main benchmark runner. Orchestrates dataset loading, system adapters,
   evaluation, and reporting for LongMemEval."
  (:require [memlayer.bench.adapter :as adapter]
            [memlayer.bench.adapters.memlayer :as ml-adapter]
            [memlayer.bench.adapters.hindsight :as hs-adapter]
            [memlayer.bench.dataset :as dataset]
            [memlayer.bench.http :as http]
            [memlayer.bench.judge :as judge]
            [memlayer.bench.metrics :as metrics]
            [memlayer.bench.report :as report]
            [clojure.tools.logging :as log]
            [clojure.edn :as edn]
            [clojure.string :as str]))

;; ---------------------------------------------------------------------------
;; Adapter registry
;; ---------------------------------------------------------------------------

(defn- make-adapter [system-name opts]
  (case system-name
    "memlayer"  (ml-adapter/make-adapter opts)
    "hindsight" (hs-adapter/make-adapter opts)
    (throw (ex-info (str "Unknown system: " system-name) {:system system-name}))))

;; ---------------------------------------------------------------------------
;; Progress
;; ---------------------------------------------------------------------------

(defn- format-duration [ms]
  (let [s (quot ms 1000)
        m (quot s 60)
        h (quot m 60)]
    (cond
      (>= h 1) (format "%dh%02dm" h (mod m 60))
      (>= m 1) (format "%dm%02ds" m (mod s 60))
      :else    (format "%ds" s))))

;; ---------------------------------------------------------------------------
;; Ingest phase — all sessions into one namespace
;; ---------------------------------------------------------------------------

(defn- ingest-all!
  "Ingest all sessions from all questions into a single namespace sequentially.
   Returns {:retain-results [...]}."
  [adapter session-id items]
  (let [all-sessions (vec (mapcat :sessions items))
        total        (count all-sessions)
        start-ms     (System/currentTimeMillis)]
    (log/info (format "Ingesting %d sessions into namespace %s..." total session-id))
    (let [retain-results
          (vec
           (map-indexed
            (fn [idx session]
              (when (and (pos? idx) (zero? (mod idx 10)))
                (let [elapsed (- (System/currentTimeMillis) start-ms)
                      avg     (/ elapsed idx)
                      eta     (format-duration (long (* avg (- total idx))))]
                  (log/info (format "  Ingested %d/%d sessions — elapsed: %s, ETA: %s"
                                    idx total
                                    (format-duration elapsed) eta))))
              (adapter/retain! adapter session-id (:text session)
                               {:timestamp (:date session)}))
            all-sessions))
          ingest-ms (- (System/currentTimeMillis) start-ms)]
      (log/info (format "Ingest complete: %d sessions in %s" total (format-duration ingest-ms)))
      ;; Reflect once over the full namespace
      (log/info "Reflecting...")
      (let [reflect-result (adapter/reflect! adapter session-id)]
        (when reflect-result
          (log/info (format "Reflect complete in %.1fs"
                            (/ (:latency-ms reflect-result) 1000.0)))))
      {:retain-results retain-results})))

;; ---------------------------------------------------------------------------
;; Recall phase — query each question against the shared namespace
;; ---------------------------------------------------------------------------

(defn- recall-all!
  "Run recall for each question against the shared namespace.
   Returns a vec of recall results (parallel to items)."
  [adapter session-id system items]
  (let [total    (count items)
        start-ms (System/currentTimeMillis)]
    (log/info "Recalling" total "questions...")
    (mapv (fn [idx item]
            (let [result (try
                           (adapter/recall! adapter session-id (:question item))
                           (catch Exception e
                             (log/error e "Recall failed for" (:question-id item))
                             {:answer "" :latency-ms 0 :usage nil :error {:exception (.getMessage e)}}))
                  elapsed (- (System/currentTimeMillis) start-ms)]
              (log/info (format "[%s %d/%d] %s (%s) — recall: %.1fs — elapsed: %s, ETA: %s"
                                system (inc idx) total
                                (:question-id item) (:question-type item)
                                (/ (:latency-ms result) 1000.0)
                                (format-duration elapsed)
                                (format-duration (long (* (/ elapsed (inc idx))
                                                          (- total (inc idx)))))))
              result))
          (range) items)))

;; ---------------------------------------------------------------------------
;; Full system benchmark
;; ---------------------------------------------------------------------------

(defn- run-system!
  "Benchmark all questions against a single system using one shared namespace.
   Phases: create namespace → ingest all sessions → reflect → recall each question.
   Returns:
   {:system str :items [...] :recall-results [...] :retain-results [...] :answers [...]}"
  [adapter items]
  (let [system     (adapter/adapter-name adapter)
        run-id     (str (System/currentTimeMillis))
        session-id (str "bench-" run-id)]
    (log/info "Starting benchmark:" system "—" (count items) "questions,"
              (reduce + (map #(count (:sessions %)) items)) "sessions (run" run-id ")")
    (adapter/setup! adapter)
    (adapter/create-session! adapter session-id)
    (let [{:keys [retain-results]} (ingest-all! adapter session-id items)
          recall-results (recall-all! adapter session-id system items)]
      (adapter/teardown! adapter)
      {:system         system
       :items          items
       :recall-results recall-results
       :retain-results retain-results
       :answers        (mapv #(or (:answer %) "") recall-results)})))

;; ---------------------------------------------------------------------------
;; CLI entry point
;; ---------------------------------------------------------------------------

(defn- load-api-key []
  (or (System/getenv "OPENAI_API_KEY")
      (throw (ex-info "OPENAI_API_KEY not set (needed for LLM-as-a-Judge)" {}))))

(defn run
  "Run the benchmark. Called via clojure -X:bench.
   Opts:
     :dataset  \"oracle\" | \"s\" | \"m\"  (default: oracle)
     :limit    int or nil             (default: nil = all 500)
     :systems  [\"memlayer\" \"hindsight\"]
     :memlayer-url str                (default: http://localhost:8090)
     :hindsight-url str               (default: http://localhost:8888)"
  [{:keys [dataset limit systems memlayer-url hindsight-url]
    :or   {dataset "oracle"
           systems ["memlayer" "hindsight"]}}]
  (let [api-key    (load-api-key)
        items      (dataset/load-dataset dataset)
        items      (if limit (vec (take limit items)) items)
        _          (log/info "Loaded" (count items) "questions from" dataset)
        adapter-opts {"memlayer"  {:base-url (or memlayer-url "http://localhost:8090")}
                      "hindsight" {:base-url (or hindsight-url "http://localhost:8888")}}

        ;; Run each system
        system-data
        (into {}
              (map (fn [sys]
                     (let [adapter (make-adapter sys (get adapter-opts sys {}))
                           data    (run-system! adapter items)]
                       [sys data])))
              systems)

        ;; Judge all answers
        all-metrics
        (into {}
              (map (fn [[sys data]]
                     (log/info "Judging" sys "answers...")
                     (let [verdicts (judge/judge-all! api-key items (:answers data))
                           m        (metrics/compute-metrics
                                     items verdicts
                                     (:retain-results data)
                                     (:recall-results data))]
                       ;; Write per-system files
                       (report/write-raw-results! sys items verdicts (:recall-results data))
                       (report/write-metrics! sys m)
                       [(keyword sys) m])))
              system-data)]

    ;; Write summary
    (report/write-summary! all-metrics dataset)
    (report/print-summary all-metrics)
    (log/info "Results written to bench/results/" @report/run-dir-name "/")
    all-metrics))

(defn run-cli
  "Entry point for bb bench. Parses CLI args and delegates to run."
  []
  (let [args  (into {} (map (fn [[k v]] [(keyword (str/replace k #"^:" "")) v])
                            (partition 2 *command-line-args*)))
        opts  (cond-> {:dataset (or (:dataset args) "oracle")}
                (:limit args)
                (assoc :limit (parse-long (:limit args)))

                (:systems args)
                (assoc :systems (edn/read-string (:systems args)))

                (:memlayer-url args)
                (assoc :memlayer-url (:memlayer-url args))

                (:hindsight-url args)
                (assoc :hindsight-url (:hindsight-url args)))]
    (run opts)))

;; ---------------------------------------------------------------------------
;; Cleanup
;; ---------------------------------------------------------------------------

(defn clean!
  "Delete bench namespaces from a running memlayer instance.
   If run-id is provided, only deletes namespaces from that run.
   Otherwise deletes all namespaces with the 'bench-' prefix."
  [{:keys [url run-id] :or {url "http://localhost:8090"}}]
  (let [resp    (http/get! url "/api/v1/account/namespaces")
        all-ns  (get-in resp [:body :namespaces])
        prefix  (if run-id (str "bench-" run-id) "bench-")
        targets (filter #(str/starts-with? (or (:name %) (:id %)) prefix) all-ns)]
    (if (empty? targets)
      (println (if run-id
                 (str "  No bench namespaces found for run " run-id)
                 "  No bench namespaces"))
      (do
        (println (str "  Deleting " (count targets) " bench namespaces..."))
        (doseq [ns-entry targets]
          (let [ns-name (or (:name ns-entry) (:id ns-entry))
                resp    (http/delete! url (str "/api/v1/account/namespaces/" ns-name))]
            (if (#{200 204} (:status resp))
              (println "    Deleted" ns-name)
              (println "    FAILED" ns-name (:status resp)))))))))

(defn clean-cli
  "Entry point for bb bench-clean."
  []
  (let [args (into {} (map (fn [[k v]] [(keyword (str/replace k #"^:" "")) v])
                           (partition 2 *command-line-args*)))
        opts (cond-> {}
               (:url args) (assoc :url (:url args))
               (first (remove #(str/starts-with? % ":") (map str *command-line-args*)))
               (assoc :run-id (first (remove #(str/starts-with? % ":") (map str *command-line-args*)))))]
    (clean! opts)))
