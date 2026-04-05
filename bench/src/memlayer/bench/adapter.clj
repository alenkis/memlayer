(ns memlayer.bench.adapter
  "Protocol for benchmark adapters. Each memory system implements this to
   participate in LongMemEval benchmarking.")

(defprotocol BenchAdapter
  (adapter-name [this]
    "Human-readable name for this system, e.g. \"memlayer\" or \"hindsight\".")
  (setup! [this]
    "Ensure the system is healthy and ready. Polls health endpoint with retries.")
  (teardown! [this]
    "Clean up resources.")
  (create-session! [this session-id]
    "Create an isolated memory space (namespace/bank) for one benchmark question.")
  (delete-session! [this session-id]
    "Delete the memory space after the question is answered.")
  (retain! [this session-id text opts]
    "Ingest a single conversation text. opts may include {:timestamp str}.
     Returns {:latency-ms long :usage {:prompt-tokens n :completion-tokens n :total-tokens n}}")
  (reflect! [this session-id]
    "Run knowledge organization on the session. Optional — adapters that don't
     support reflect should return nil. Returns {:latency-ms long} or nil.")
  (recall! [this session-id query]
    "Query the system and get a natural-language answer.
     Returns {:answer str :latency-ms long :usage {...}}"))

(defn timed
  "Execute f, return [result elapsed-ms]."
  [f]
  (let [start (System/nanoTime)
        result (f)
        elapsed (/ (- (System/nanoTime) start) 1e6)]
    [result (long elapsed)]))
