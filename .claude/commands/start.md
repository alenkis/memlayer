# Start memlayer

Start the memlayer HTTP API server and optionally the CLJS dashboard.

## Steps

1. Check for `.env` file:
   ```bash
   test -f .env && echo "exists" || echo "missing"
   ```
   If missing, run `cp .env.example .env` and fill in your API keys.

2. Start the API server:
   ```bash
   bb server
   ```

3. Wait for the API to be healthy:
   ```bash
   curl -s --retry 10 --retry-delay 2 --retry-all-errors http://localhost:8080/health
   ```

4. Report the following to the user:
   - API: http://localhost:8080
   - Dashboard (CLJS): run `bb dashboard-dev` + `bb dashboard-css` for http://localhost:3000
   - MCP: run `bb mcp` for stdio MCP server

5. If the health check fails after retries, check the server output for errors.
