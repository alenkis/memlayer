import { defineConfig } from "@playwright/test";
import { DASHBOARD_URL } from "./e2e/config";

/**
 * E2E tests run against the CLJS dashboard dev server
 * which proxies API calls to the Clojure backend.
 * Ports are configured in config.edn (exported to config.json by `make config.json`).
 *
 * Prerequisites (run in separate terminals):
 *   make server        — backend
 *   make dashboard-dev — dashboard
 */
export default defineConfig({
  globalSetup: "./e2e/global-setup.ts",
  globalTeardown: "./e2e/global-teardown.ts",
  testDir: "./e2e",
  timeout: 120_000,
  expect: { timeout: 30_000 },
  fullyParallel: false,
  workers: 1,
  retries: 0,
  reporter: "list",
  use: {
    baseURL: DASHBOARD_URL,
    trace: "on",
    screenshot: "on",
    video: "on",
  },
  projects: [
    {
      name: "chromium",
      use: { browserName: "chromium" },
    },
  ],
});
