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
src/clj/memlayer/    # Clojure backend (operations, persistence, API, MCP)
src/cljc/memlayer/   # Shared Clojure/ClojureScript code (.cljc)
src/cljs/memlayer/   # ClojureScript dashboard (Re-frame)
resources/           # Static assets, logging config
test/                # Unit tests + integration tests (kaocha)
e2e/                 # Playwright e2e tests (real services)
dev/                 # REPL development helpers
bin/                 # Launcher script
website/             # Documentation site (Astro)
marketing/           # Marketing site (Astro)
memlayer-plugin/     # Claude Code marketplace plugin
infra/               # AWS infrastructure (Terraform: EC2, ECR, SSM)
docs/                # Domain documentation
allium/              # Behavioural specs (allium language)
```

## Tech Stack

- **Language**: Clojure 1.12 (JVM), ClojureScript (dashboard)
- **Runtime**: Java 22+ (required for Proximum vector operations)
- **Database**: Datahike (embedded datalog), Proximum (embedded vector DB), Stratum (analytics)
- **HTTP**: http-kit + reitit routing + malli schemas
- **System lifecycle**: Integrant
- **LLM providers**: OpenAI (embeddings), Groq (extraction/decisions)
- **Build**: tools.build (uberjar), shadow-cljs (dashboard)
- **Infrastructure**: AWS (EC2, ECR, SSM) via Terraform
- **Domain**: `memlayer.dev` — API at `api.memlayer.dev`, dashboard at `app.memlayer.dev`

## Linear Tickets (Conductor Workspaces)

When working in a Conductor workspace (`.context/` directory present), a Linear ticket **must** exist before any implementation begins.

**Workflow:**
1. **Before writing any code**, check if `.context/linear-ticket` exists
2. If it doesn't exist, run `/linear` to associate a ticket with this workspace
3. Do NOT proceed with implementation until a ticket is tracked

**When associating a ticket**, the `/linear` skill should also:
- **Assign** the ticket to the current user
- **Set status** to "In Progress"
- **Ensure it belongs to a project** — list existing projects, ask user to pick one, or propose creating a new project if none fits. Every ticket must belong to a project.
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

- `bb server` — start HTTP API server (port 8080)
- `bb mcp` — start MCP stdio server
- `bb repl-server` — start dev REPL
- `bb test` — run unit tests
- `bb check` — build + fmt-check + lint + test (fast, run often)
- `bb test-full` — check + integration + e2e tests (expensive, uses real providers)
- `bb fmt` — format all Clojure files
- `bb fmt-check` — check formatting without changes
- `bb dashboard-dev` — start CLJS dashboard (port 3000, connects to API on 8080)
- `bb dashboard-css` — watch/compile Tailwind CSS
- `bb tasks` — see all available tasks

**Custom ports:** All config lives in `config.edn` (Aero-tagged EDN). To run on non-default ports, pass env vars to **both** server and dashboard:

```bash
MEMLAYER_PORT=8081 DASHBOARD_PORT=5176 bb server
MEMLAYER_PORT=8081 DASHBOARD_PORT=5176 bb dashboard-dev
```

Both env vars are needed on both commands: the server uses `DASHBOARD_PORT` for CORS, and the dashboard uses `MEMLAYER_PORT` to know where to send API requests. For parallel sessions, use git worktrees — each worktree gets its own `shadow-cljs.edn` and shadow-cljs server.

## Pre-Merge Verification

**Before creating a PR or merging**, you MUST run `/test-full`. This skill orchestrates the full test suite automatically:

1. Runs `bb check` (build + fmt + lint + unit tests) — stops immediately on failure
2. Verifies `.env` secrets are present (integration/e2e tests need real API keys)
3. Finds free non-default ports, starts server + dashboard
4. Runs integration and e2e tests **in parallel**
5. Cleans up server/dashboard processes and restores config files

This is expensive (real LLM calls, real services) so only run `/test-full` when you believe the work is complete and ready to merge. Use `bb check` (unit-only) during development iterations.

## Releasing

Releases are controlled by git tags. Pushing a `v*` tag on `main` triggers the release pipeline (build + lint + unit tests → deploy API + dashboard + docs + marketing).

E2e and integration tests live in a separate workflow (`e2e.yml`) and can be dispatched manually from the GitHub Actions UI at any time.

**How to release:**

1. Go to https://github.com/alenkis/memlayer/releases
2. Click "Draft a new release"
3. Choose a tag: type a new tag like `v0.4.0` (must target `main`)
4. Click "Generate release notes" for an auto-generated changelog
5. Publish the release

This creates the tag and triggers the deploy pipeline automatically.

**From the CLI** (alternative): `bb release v0.4.0` — validates format, prevents duplicates, enforces `main`, creates an annotated tag and pushes it.

**Emergency deploy** (no version tag): Use the GitHub Actions UI → Release workflow → Run workflow. Deploys from `main` HEAD without creating a tag.

**Rollback to a previous version:**

Every release creates a tagged Docker image (e.g., `v0.3.1`). Two ways to rollback:

1. **Via GitHub Actions** (recommended): Actions UI → Release workflow → Run workflow → enter the tag in the `version` field (e.g., `v0.3.1`). This checks out that tag and redeploys all services from it.

2. **Via SSH/SSM** (API only, faster):
```bash
# Via SSH
ssh ec2-user@<IP> 'sudo /usr/local/bin/memlayer-deploy v0.3.1'

# Via AWS SSM
aws ssm send-command --instance-ids <ID> \
  --document-name AWS-RunShellScript \
  --parameters commands=["/usr/local/bin/memlayer-deploy v0.3.1"]
```

To find available versions: `git tag --sort=-creatordate | head -10` or check [GitHub Releases](https://github.com/alenkis/memlayer/releases).

**Check deployed version:** `curl https://api.memlayer.dev/health` returns `{version, git-sha, built-at}`.

**Versioning scheme:** Semantic versioning — `vMAJOR.MINOR.PATCH`. Bump MAJOR for breaking API changes, MINOR for new features, PATCH for fixes.

## Naming Conventions

- **`!` suffix**: Functions that perform side effects (database writes, index mutations, external API calls) must use the `!` suffix. This is standard Clojure convention. Examples: `forget!`, `evict!`, `retract-memory!`, `delete-relationships-for-memory!`. Read-only functions (queries, parsing, serialization) must NOT use `!`.

## Formatting

**IMPORTANT**: After editing any `.clj` or `.cljs` file, ALWAYS run `clojure -M:fmt fix <file>` to format it before committing. This ensures consistent formatting across the codebase. The formatter config is in `.cljfmt.edn` and is shared with clojure-lsp.

## Allium Specs

Allium specs (`allium/*.allium` files) are the formal behavioural specifications for this system. They describe what the system does at the domain level — entities, rules, surfaces — independent of implementation.

**Spec files:**

- `allium/memlayer-core.allium` — Memory, Retain, Recall, Reflect, Forget, Ingest, analytics
- `allium/memlayer-auth.allium` — Authentication, tokens, multi-tenancy
- `allium/memlayer-dashboard.allium` — Dashboard surfaces, API key management, usage
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

**CLJS dashboard** (`src/cljs/memlayer/dashboard/`) — Re-frame + Tailwind

**Rules:**

- Reusable components go in `src/cljs/memlayer/dashboard/components/`
- Page views go in `src/cljs/memlayer/dashboard/views/`
- Use re-frame events/subs for state, not local atoms
