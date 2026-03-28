# Check Server Version

Verify the running server is healthy before E2E tests or API interaction.

## Steps

1. Query the running server's health endpoint:
   ```bash
   curl -s --connect-timeout 3 --max-time 5 http://localhost:8080/health
   ```

2. Check the response:
   - **Healthy**: Report "Server is running" and proceed.
   - **Unreachable**: Report "No server responding on localhost:8080. Run `bb server` to start it."

3. If the server is unreachable, **stop and inform the user**. Do not proceed with E2E tests or API calls.
