# Full Test Suite

Run the complete pre-merge verification: unit tests, integration tests, and e2e tests. Starts server and dashboard on free non-default ports, then runs integration and e2e in parallel.

**This is expensive** — uses real LLM providers. Only run when work is ready to merge.

## Phase 1: Fast Checks

Run unit tests, lint, and formatting:
```bash
bb check
```

If this fails, **stop immediately** and report the failure. Do not proceed to expensive tests.

## Phase 2: Environment

Check `.env` exists:
```bash
test -f .env && echo "exists" || echo "missing"
```

If missing, run the `/onboard` skill (Phase 2 only — environment setup). Do not proceed without `.env`.

## Phase 3: Find Free Ports

Integration tests use port 18080 (hardcoded). E2E tests need a server + dashboard on custom ports.

Find **three** free ports — one for the API server, one for the dashboard, one confirmed for integration (18080). Do NOT use defaults (8080, 3000).

Check port availability:
```bash
lsof -i :18080 -t 2>/dev/null && echo "18080 BUSY" || echo "18080 FREE"
```

For the server and dashboard, try ports in the 19000-19100 range:
```bash
lsof -i :19001 -t 2>/dev/null && echo "19001 BUSY" || echo "19001 FREE"
lsof -i :19002 -t 2>/dev/null && echo "19002 BUSY" || echo "19002 FREE"
```

Pick the first two free ports. If 18080 is busy, **stop** — integration tests need it.

## Phase 4: Generate Config & Install Deps

Set the chosen ports as env vars for all subsequent commands. Example (assuming 19001 and 19002):

```bash
MEMLAYER_PORT=19001 DASHBOARD_PORT=19002 bb config-json
MEMLAYER_PORT=19001 DASHBOARD_PORT=19002 bb shadow-cljs-edn
```

Install npm deps if `node_modules` is missing, then build Tailwind CSS (required for e2e — the CLJS dashboard won't render without it):
```bash
test -d node_modules || npm install
npm run build:css
```

## Phase 5: Start Server & Dashboard

Start both in background. **Both commands need both env vars** (server uses DASHBOARD_PORT for CORS, dashboard uses MEMLAYER_PORT for API requests).

Start the API server:
```bash
MEMLAYER_PORT=19001 DASHBOARD_PORT=19002 bb server
```
Run this in the background.

Start the dashboard:
```bash
MEMLAYER_PORT=19001 DASHBOARD_PORT=19002 bb dashboard-dev
```
Run this in the background.

## Phase 6: Wait for Health

Wait for both to be reachable (retry up to 60 seconds):

```bash
for i in $(seq 1 30); do curl -sf http://localhost:19001/health && break || sleep 2; done
```

```bash
for i in $(seq 1 30); do curl -sf http://localhost:19002/ -o /dev/null && break || sleep 2; done
```

If either times out after 60 seconds, check the background process output for errors and **stop**.

## Phase 7: Run Tests in Parallel

Launch integration and e2e tests **simultaneously** using parallel tool calls:

**Integration tests** (starts its own server on 18080, needs .env for API keys):
```bash
set -a && [ -f .env ] && . .env || true && set +a; clojure -M:integration
```

**E2E tests** (uses the server + dashboard started in Phase 5):
```bash
npx playwright test
```

Run both in parallel. Wait for both to complete. Report results from each.

## Phase 8: Cleanup

After tests complete (pass or fail), kill the background server and dashboard processes. Use the process IDs or:
```bash
lsof -i :19001 -t | xargs kill 2>/dev/null
lsof -i :19002 -t | xargs kill 2>/dev/null
```

Restore original config files if they were tracked by git:
```bash
git checkout config.json shadow-cljs.edn 2>/dev/null || true
```

## Phase 9: Report

Summarize results:
- **bb check**: pass/fail
- **Integration tests**: pass/fail (with failure details if any)
- **E2E tests**: pass/fail (with failure details if any)

If all pass: "Ready to merge."
If any fail: show the relevant failures and stop.
