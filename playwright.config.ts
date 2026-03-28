import { defineConfig } from "@playwright/test";
import { DASHBOARD_URL } from "./e2e/config";

/**
 * E2E tests run against the dashboard served by `bb server`.
 * API + dashboard are served on a single port.
 * Ports are configured in config.edn (exported to config.json by `bb config-json`).
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
