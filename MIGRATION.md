# Migration Plan: memlayer-cloud → memlayer (open source)

## Goal

Create an open-source version of memlayer (from `memlayer-cloud` / memc) starting from a clean slate. The open-source distribution should:

1. **Build with GraalVM** — already possible via local setup in memc
2. **Distribute via Homebrew** — users install and get the server, databases, dashboard, etc. running locally
3. **Local-first with configurable storage** — users choose where to save their data
4. **Usable as a Clojure (JVM) library** — core logic is composable and reusable, eventually importable back into memc. This ensures concerns stay separated and the core doesn't accumulate cloud-specific coupling.

## Approach: incremental strip-down

We progress in steps rather than rewriting from scratch.

### Step 1: Copy the repo (current)
- Copy the entire `memlayer-cloud` codebase into this repo as-is.
- Verify it compiles and the core functionality works.

### Step 2: Strip authentication
- Remove Clerk/auth middleware, API key gating, and related deps.
- Replace with a local-only or optional simple auth model.

### Step 3: Remove cloud infrastructure
- Remove Vercel, fly.io, and cloud deployment configs.
- Remove marketing site and cloud-specific docs.
- Strip CI workflows that target cloud environments.

### Step 4: Localise storage
- Replace any cloud-hosted DB assumptions with local-first defaults.
- Add configuration for storage location (e.g. `~/.memlayer/` or user-specified path).

### Step 5: Extract core library
- Factor out the core logic (ingestion, querying, memory layer) into a standalone Clojure library.
- The open-source app and memc both depend on this library.
- Publish to Clojars or similar.

### Step 6: GraalVM native image + Homebrew
- Ensure GraalVM native-image build works for the standalone app.
- Create Homebrew formula for distribution.

## Architecture target

```
┌─────────────────────────────────┐
│  memlayer (open source CLI/app) │  ← Homebrew, GraalVM native
│    - local server               │
│    - local DB                   │
│    - dashboard                  │
│    - configurable storage       │
├─────────────────────────────────┤
│  memlayer-core (library)        │  ← Clojars, JVM dependency
│    - ingestion                  │
│    - querying                   │
│    - memory layer logic         │
└─────────────────────────────────┘
         ▲
         │ depends on
┌────────┴────────────────────────┐
│  memlayer-cloud (memc)          │  ← existing cloud product
│    - auth, billing, multi-tenant│
│    - cloud infra                │
└─────────────────────────────────┘
```
