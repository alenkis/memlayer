---
name: clojure-review
description: Review recently written or changed Clojure code for FP principles, composition, readability, testability, and correctness. Use after writing or modifying .clj, .cljc, or .cljs files.
context: fork
agent: clojure-reviewer
---

Review the Clojure code that was recently changed in this project.

## Find what changed

```bash
git diff --name-only HEAD
```

If there are no uncommitted changes, check the latest commit:

```bash
git diff --name-only HEAD~1
```

Focus only on `.clj`, `.cljc`, and `.cljs` files.

## Review

Read each changed file in full. For each file:

1. Understand the file's role in the system (handler? domain logic? persistence?)
2. Review every function against the principles in your agent definition
3. Look at how the functions compose together — is the data flow clear?
4. Check separation of concerns — are parsing, logic, effects, and serialization separate?
5. Assess testability — could each function be tested in isolation?

## Report

Return a concise review with concrete suggestions. Include refactored code for each finding. If the code is already good, say so briefly and explain what makes it good.
