# Onboard

Set up a new developer's environment for working on memlayer. Run each phase in order, checking current state before acting. Skip steps that are already done.

## Phase 1: Prerequisites

### Java 22+

```bash
java -version
```

If missing or too old: guide the user to install via Homebrew (`brew install openjdk`) or https://adoptium.net/. Java 22+ is required for Proximum vector operations.

### Clojure CLI

```bash
clojure --version
```

If missing: `brew install clojure/tools/clojure` on macOS, or see https://clojure.org/guides/install_clojure.

### Node.js (for CLJS dashboard)

```bash
node --version
```

If missing: `brew install node` or https://nodejs.org/.

## Phase 2: Environment Setup

### .env file

Check if `.env` exists in the project root:
```bash
test -f .env && echo "exists" || echo "missing"
```

If missing:
```bash
cp .env.example .env
# Edit .env and set OPENAI_API_KEY and GROQ_API_KEY
```

## Phase 3: Build & Start

Start the HTTP API server:
```bash
bb server
```

Verify the server is healthy:
```bash
curl -s http://localhost:8080/health
```

### Dashboard (optional)

Install npm deps and start the CLJS dashboard:
```bash
bb dashboard-deps
bb dashboard-dev
# In another terminal:
bb dashboard-css
```

Dashboard runs at http://localhost:3000 (proxies API to :8080).

## Phase 4: Verify

Run unit tests:
```bash
bb test
```

## Phase 5: Orientation

Once everything is running, explain to the developer:

**Project structure:**
- `src/memlayer/` — All source: operations, persistence, API, MCP, dashboard
- `src/memlayer/dashboard/` — ClojureScript dashboard (Re-frame)
- `test/` — Unit tests + integration tests (kaocha)
- `e2e/` — Playwright e2e tests (real services)
- `memlayer-plugin/` — Claude Code marketplace plugin

**Key commands** (always use bb):
- `bb server` — start HTTP API server
- `bb mcp` — start MCP stdio server
- `bb repl-server` — start dev REPL
- `bb test` — run unit tests
- `bb check` — build + lint + test
- `bb dashboard-dev` — start CLJS dashboard dev server
- `bb tasks` — see all targets

**Workflow:**
- Use git worktrees for feature branches: `git worktree add ../memlayer-<feature> -b <branch>`
- Run `/check-server-version` before E2E tests or API interaction
- Read `CLAUDE.md` for full coding conventions
