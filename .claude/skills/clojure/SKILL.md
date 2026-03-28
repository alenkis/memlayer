---
name: clojure
description: Clojure coding guidelines for memlayer. Use when writing, editing, or reviewing any .clj, .cljc, or .cljs file. Not for git operations, config changes, or documentation.
user-invocable: false
---

Follow these guidelines when writing Clojure code in this project.

## Style

- Idiomatic Clojure — prefer threading macros, destructuring, and small pure functions
- Use `!` suffix for side-effecting functions
- After editing any `.clj` or `.cljs` file, run `clojure -M:fmt fix <file>` to format it

## Composition

- **Extract named functions** for each distinct step. Prefer several small, well-named `defn-` or `defn` over one large function body. Each function should do one thing.
- **Separate concerns**: parsing/validation, transformation, and side effects should be different functions — not interleaved in one `let` block.
- **Keep lines short and readable**. Break complex expressions into named intermediate steps rather than nesting deeply or chaining long inline expressions.
- **Compose with threading macros** (`->`, `->>`, `some->`) to make data flow explicit and linear.
- **Name the steps**: if a `let` binding does something non-obvious, extract it to a named function. If you'd need a comment to explain a line, it should probably be a function instead.

### Bad — one monolithic function mixing concerns

```clojure
(defn handler [deps]
  (fn [request]
    (let [body (:body-params request)
          result (some-op! deps {:content (:content body) :source (:source body) :namespace (:namespace body)})]
      {:status 201 :body {:memory-ids (mapv str (:memory-ids result)) :decisions (mapv (fn [d] (cond-> {:type (:type d) :content (:content d)} (:memory-id d) (assoc :memory-id (str (:memory-id d))))) (:decisions result))}})))
```

### Good — extracted, composed, readable

```clojure
(defn- parse-retain-params [body]
  {:content   (:content body)
   :source    (:source body)
   :namespace (:namespace body)})

(defn- serialize-decision [d]
  (cond-> {:type    (:type d)
           :content (:content d)}
    (:memory-id d) (assoc :memory-id (str (:memory-id d)))))

(defn- retain-response [result]
  {:status 201
   :body   {:memory-ids (mapv str (:memory-ids result))
            :decisions  (mapv serialize-decision (:decisions result))}})

(defn handler [deps]
  (fn [request]
    (-> (:body-params request)
        parse-retain-params
        (->> (some-op! deps))
        retain-response)))
```

## Structure

- All source lives in `src/memlayer/` (.clj, .cljc, .cljs colocated)
- Dashboard (ClojureScript) is in `src/memlayer/dashboard/`
- Tests go in `test/` mirroring the `src/` structure

## Verification

- Run `bb check` after changes to verify build + formatting + lint + unit tests
- Use `/eval-clj` to test functions interactively in the REPL
