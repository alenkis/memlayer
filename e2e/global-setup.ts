/**
 * Playwright global setup — fail fast if backend or frontend are unavailable.
 * Runs once before any test file executes.
 *
 * Prerequisites (run in separate terminals):
 *   make server        — backend
 *   make dashboard-dev — dashboard
 *
 * Ports are configured in config.edn (exported to config.json by `make config.json`).
 */

import { BASE_URL as API_URL, DASHBOARD_URL } from "./config";
import { resetDatabase } from "./helpers";
const TIMEOUT_MS = 5_000;

async function checkEndpoint(
  url: string,
  hint: string,
  headers?: Record<string, string>
): Promise<string> {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), TIMEOUT_MS);
  try {
    const resp = await fetch(url, { signal: controller.signal, headers });
    if (!resp.ok) {
      throw new Error(`${url} returned ${resp.status} — ${hint}`);
    }
    return await resp.text();
  } catch (err: any) {
    if (err.name === "AbortError" || err.code === "ECONNREFUSED") {
      throw new Error(`${url} not reachable — ${hint}`);
    }
    throw err;
  } finally {
    clearTimeout(timer);
  }
}

export default async function globalSetup(): Promise<void> {
  await checkEndpoint(`${API_URL}/health`, "run: make server");

  const body = await checkEndpoint(`${DASHBOARD_URL}/`, "run: make dashboard-dev", {
    Accept: "text/html",
  });
  if (!body.includes("<html") && !body.includes("<!DOCTYPE")) {
    throw new Error(
      `${DASHBOARD_URL}/ did not return HTML — run: make dashboard-dev`
    );
  }

  // Clean slate before all tests
  await resetDatabase();
}
