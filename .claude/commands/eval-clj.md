# Evaluate Clojure in REPL

Run Clojure code against the project classpath. The REPL auto-starts on a random free port if none is running. Use this to verify code you wrote, test functions, explore namespaces, or run any Clojure expression in isolation.

## How to evaluate code

```bash
bin/repl-eval.clj '(+ 1 2)'
```

The first call starts a REPL in the background (may take ~15-30s for JVM startup). Subsequent calls reuse the same REPL process.

For multi-expression code, wrap in a `do` block:

```bash
bin/repl-eval.clj '(do (require '[clojure.string :as str]) (str/upper-case "hello"))'
```

For longer code, use a heredoc:

```bash
bin/repl-eval.clj <<'CLJ'
(require '[memlayer.operations.retain :as retain])
(doc retain/retain!)
CLJ
```

## Important notes

- The REPL has the full project classpath — you can `require` any namespace in `src/clj/`
- Each call opens a fresh socket connection but shares the same REPL process — side effects (defs, requires) persist across calls
- No system is started by default — this is for running code in isolation
- Port and PID are stored in `.repl-port` and `.repl-pid` (gitignored)
- If the REPL dies or something goes wrong, check `.repl.log`

## Common patterns

```bash
# Require a namespace and call a function
bin/repl-eval.clj '(do (require '[memlayer.config :as config]) (config/load-config))'

# Test a pure function you just wrote
bin/repl-eval.clj '(do (require '[memlayer.operations.retain :as r]) (doc r/retain!))'

# Explore a namespace
bin/repl-eval.clj '(do (require '[memlayer.persistence.datahike]) (dir memlayer.persistence.datahike))'

# Check if code compiles
bin/repl-eval.clj '(require '[memlayer.api.routes])'

# Run a quick data transformation
bin/repl-eval.clj '(mapv #(update % :x inc) [{:x 1} {:x 2}])'
```

## User request

$ARGUMENTS
