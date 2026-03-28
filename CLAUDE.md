# memlayer

## Git Commits & PRs

- Do NOT include "Co-Authored-By" lines in commits
- Do NOT include "Generated with Claude Code" in PR descriptions
- When a Linear ticket is tracked (`.context/linear-ticket` exists), include `Fixes <TICKET-ID>` (or `Refs <TICKET-ID>`) in the PR body
- Branch names must include the Linear ticket ID when one is tracked (e.g., `alenkis/mem-123-add-feature`)

Patience, young grasshopper.

## Design Philosophy

- **Backward compatibility is not a concern until v1.0.** We are pre-1.0 — freely change APIs, response shapes, and behavior without opt-in flags or compatibility shims. Ship the right design, not the safe-to-change-later design.

## Project Structure

```
src/memlayer/          # All Clojure/ClojureScript source (.clj, .cljc, .cljs colocated)
  operations/          #   Core business logic (retain, recall, reflect, forget)
  persistence/         #   Database + vector DB
  provider/            #   LLM provider integrations
  domain/              #   Shared domain schemas (.cljc)
  api/                 #   HTTP API handlers
  mcp/                 #   MCP protocol adapter
  middleware/          #   HTTP middleware
  dashboard/           #   ClojureScript dashboard (Re-frame)
src/css/               # Tailwind CSS input
resources/             # Static assets, config, logging
test/                  # Unit tests + integration tests (kaocha)
e2e/                   # Playwright e2e tests (real services)
dev/                   # REPL development helpers
bin/                   # Launcher + build scripts
memlayer-plugin/       # Claude Code marketplace plugin
allium/                # Behavioural specs (allium language)
```

## Tech Stack

- **Language**: Clojure 1.12 (JVM), ClojureScript (dashboard)
- **Runtime**: Java 22+ (required for Proximum vector operations)
- **Database**: Datahike (embedded datalog), Proximum (embedded vector DB)
- **HTTP**: http-kit + reitit routing + malli schemas
- **System lifecycle**: Integrant
- **LLM providers**: OpenAI (embeddings), Groq (extraction/decisions)
- **Build**: tools.build (uberjar), shadow-cljs (dashboard)

## Linear Tickets (Conductor Workspaces)

When working in a Conductor workspace (`.context/` directory present), a Linear ticket **must** exist before any implementation begins.

**Workflow:**
1. **Before writing any code**, check if `.context/linear-ticket` exists
2. If it doesn't exist, run `/linear` to associate a ticket with this workspace
3. Do NOT proceed with implementation until a ticket is tracked

**When associating a ticket**, the `/linear` skill should also:
- **Create a new ticket automatically** without asking for confirmation. Infer the title, description, project, and priority from the conversation context. Only ask the user when it's genuinely unclear which project the ticket belongs to.
- **Assign** the ticket to the current user
- **Set status** to "In Progress"
- **Ensure it belongs to a project** — list existing projects and pick the best fit, or create a new project if none fits. Every ticket must belong to a project.
- **Ensure description exists** — every ticket must have a description with: Problem (what's wrong), Impact (why it matters), Desired Outcome (what done looks like). For simple tickets, add an Approach section with high-level direction.
- Set **priority** if not already set (ask user or default to Normal)
- Set **relationships** (`relatedTo`, `blocks`, `blockedBy`) to other tickets if relevant

**Branch naming:** `alenkis/<ticket-id>-short-description` (e.g., `alenkis/MEM-123-add-pagination`). The ticket ID must be lowercase in the branch name.

**PR references:** Every PR body must include a Linear ticket reference on its own line: `Fixes MEM-123` (or `Refs MEM-123` for partial work). This enables Linear's auto-close integration.

**After merging a PR:** Do NOT manually update the Linear ticket status. The GitHub-Linear integration automatically moves tickets to "Done" when PRs with `Fixes MEM-XXX` are merged.

**`.context/linear-ticket` format:**
```
TICKET_ID=MEM-123
TICKET_TITLE=Add pagination to API endpoints
TICKET_URL=https://linear.app/memlayer/issue/MEM-123
```

## Development Workflow

**Git Worktrees**: Always use git worktrees for feature development instead of working directly on main. Create a worktree with `git worktree add ../memlayer-<feature> -b <feature-branch>`.

**Key commands:**

- `bb server` — start memlayer (API + dashboard on port 8090, + nREPL). Auto-builds dashboard on first run.
- `bb dev` — start API + dashboard hot-reload + CSS watcher (for memlayer contributors)
- `bb mcp` — start MCP stdio server
- `bb repl-server` — start dev REPL
- `bb test` — run unit tests
- `bb check` — build + fmt-check + lint + test (fast, run often)
- `bb test-full` — check + integration + e2e tests (expensive, uses real providers)
- `bb fmt` — format all Clojure files
- `bb fmt-check` — check formatting without changes
- `bb dashboard-build` — production build of dashboard (run manually to rebuild)
- `bb tasks` — see all available tasks

**Custom ports:** Config lives in `resources/config.edn`. Override with env vars:

```bash
MEMLAYER_PORT=9090 bb server
```

For parallel sessions, use git worktrees — each worktree gets its own ports and shadow-cljs server.

## Pre-Merge Verification

**Before creating a PR or merging**, you MUST run `/test-full`. This skill orchestrates the full test suite automatically:

1. Runs `bb check` (build + fmt + lint + unit tests) — stops immediately on failure
2. Verifies `.env` secrets are present (integration/e2e tests need real API keys)
3. Finds free non-default ports, starts server + dashboard
4. Runs integration and e2e tests **in parallel**
5. Cleans up server/dashboard processes and restores config files

This is expensive (real LLM calls, real services) so only run `/test-full` when you believe the work is complete and ready to merge. Use `bb check` (unit-only) during development iterations.

## Naming Conventions

- **`!` suffix**: Functions that perform side effects (database writes, index mutations, external API calls) must use the `!` suffix. This is standard Clojure convention. Examples: `forget!`, `evict!`, `retract-memory!`, `delete-relationships-for-memory!`. Read-only functions (queries, parsing, serialization) must NOT use `!`.

## Formatting

**IMPORTANT**: After editing any `.clj` or `.cljs` file, ALWAYS run `clojure -M:fmt fix <file>` to format it before committing. This ensures consistent formatting across the codebase. The formatter config is in `.cljfmt.edn` and is shared with clojure-lsp.

## Allium Specs

Allium specs (`allium/*.allium` files) are the formal behavioural specifications for this system. They describe what the system does at the domain level — entities, rules, surfaces — independent of implementation.

**Spec files:**

- `allium/memlayer-core.allium` — Memory, Retain, Recall, Reflect, Forget, Ingest, Namespace CRUD
- `allium/memlayer-dashboard.allium` — Dashboard namespace management
- `allium/memlayer-ingest.allium` — Async ingest jobs, WebSocket streaming, chunking
- `allium/memlayer-mcp.allium` — MCP protocol adapter surface

**When to check specs:**

- Before implementing a new feature: read the relevant spec to understand existing behaviour
- Before modifying existing behaviour: check if the change affects specified rules or surfaces
- When adding a new API endpoint: check if a surface already describes the contract

**When to update specs:**

- After implementing a new feature: add entities, rules, and surfaces to the relevant spec
- After changing existing behaviour: update affected rules to match new logic
- After adding/removing API endpoints: update surfaces
- After adding new domain concepts: add entity or value type definitions

**When to create a new spec:**

- When a new subsystem is large enough to warrant its own file (3+ entities or 5+ rules)
- When the new feature doesn't fit naturally into any existing spec

**Rules:**

- Specs describe observable behaviour, not implementation details
- Use `deferred` for anything that's planned but not yet implemented
- Use `open question` for unresolved design decisions
- Use the `allium` skill (`/allium`) for syntax reference when writing specs

## E2E Tests (Playwright)

E2E tests live in `e2e/` and run against the dashboard UI with Playwright.

**Core principle: e2e tests interact through the UI, never the API directly.** Every test action must go through the dashboard the same way a real user would — clicking buttons, filling forms, navigating pages. Direct API calls in e2e specs belong in unit/integration tests, not here.

**Allowed API calls in e2e tests:**

- `resetDatabase()` — test setup/teardown (admin endpoint, not a user action)
- `waitForConsistency()` — timing helper

**UI helpers in `e2e/helpers.ts`:**

- `retainViaPlayground(page, content, source?)` — retains a memory through the Playground UI (for pre-populating test data)

**Not allowed in e2e tests:**

- Calling any API endpoint directly (e.g., `fetch("/api/v1/retain")`) — use UI helpers instead
- Asserting against raw API responses — assert against what the user sees in the UI
- API integration testing belongs in `test/` (Clojure kaocha tests), not in `e2e/`

**Test patterns:**

- Mock-based tests use `setupMockRoutes()` from `fixtures.ts` to intercept API calls
- Real-backend tests require a running server on `localhost:8080` with LLM providers configured

**Key commands:**

- `npx playwright test` — run all e2e tests
- `npx playwright test --list` — list all tests
- `npx playwright test e2e/bulk-ingest.spec.ts` — run a specific test file

## Frontend UI Components

**CLJS dashboard** (`src/memlayer/dashboard/`) — Re-frame + Tailwind

**Rules:**

- Reusable components go in `src/memlayer/dashboard/components/`
- Page views go in `src/memlayer/dashboard/views/`
- Use re-frame events/subs for state, not local atoms
