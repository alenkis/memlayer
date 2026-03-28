/**
 * E2E test helpers — setup/teardown and UI interaction helpers.
 * All test actions must go through the dashboard UI, never the API directly.
 */

import { Page, expect, BrowserContext } from "@playwright/test";
import fs from "fs";
import path from "path";
import { BASE_URL } from "./config";

/**
 * No-op — Firebase auth has been removed. Kept for backward compatibility
 * with existing test files.
 */
export async function bypassFirebaseAuth(_page: Page): Promise<void> {}

/**
 * Assert the graph page loads with visible nodes.
 * Used after "View in Graph" navigation or direct graph access.
 *
 * SPA client-side navigation can be slow — wait for the URL to settle
 * on /graph before asserting DOM content.
 */
export async function expectGraphWithNodes(page: Page): Promise<void> {
  await page.waitForURL("**/graph**", { timeout: 10_000 });
  await expect(
    page.getByRole("heading", { name: /memory graph/i })
  ).toBeVisible({ timeout: 15_000 });

  const graphContainer = page.locator('[data-testid="graph-container"]');
  await expect(graphContainer.locator("svg circle").first()).toBeVisible({
    timeout: 15_000,
  });
}

/**
 * Switch the active namespace via the top-bar dropdown.
 * The dropdown is always visible; selecting a value triggers data refetch.
 */
export async function switchNamespace(
  page: Page,
  namespace: string
): Promise<void> {
  // The namespace dropdown is in the top bar, next to the "Namespace" label
  const select = page.getByLabel("Namespace");
  await select.selectOption(namespace);
  // Wait for data refetch to settle
  await page.waitForTimeout(1000);
}

/**
 * Create a namespace via the Namespaces management page.
 */
export async function createNamespace(
  page: Page,
  name: string
): Promise<void> {
  await page.goto("/namespaces");
  await expect(
    page.getByRole("heading", { name: /namespaces/i })
  ).toBeVisible();
  await page.getByRole("button", { name: /create/i }).click();
  await page.locator('input[placeholder*="namespace" i]').fill(name);
  await page
    .locator(".fixed, [role=dialog]")
    .getByRole("button", { name: /create/i })
    .click();
  // Wait for namespace to appear in the table list (not the dropdown)
  await expect(
    page.getByRole("table").getByText(name)
  ).toBeVisible({ timeout: 5_000 });
}

export async function resetDatabase(): Promise<void> {
  const resp = await fetch(`${BASE_URL}/api/v1/admin/reset`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: "{}",
  });
  if (!resp.ok) throw new Error(`Reset failed: ${resp.status}`);
}

export async function waitForConsistency(ms: number = 500): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * Retain a memory via the Playground Retain tab.
 * Navigates to /playground, fills the form, clicks Retain,
 * and waits for a CREATE/UPDATE decision to appear.
 */
export async function retainViaPlayground(
  page: Page,
  content: string,
  opts: { source?: string; namespace?: string } = {}
): Promise<void> {
  const { source = "e2e-test", namespace } = opts;

  await page.goto("/playground");
  await expect(
    page.getByRole("heading", { name: /playground/i })
  ).toBeVisible();

  // Ensure Retain tab is active (tabs now use role="tab" per WCAG)
  await page.getByRole("tab", { name: "Retain" }).click();

  // Fill content
  await page.locator("textarea").first().fill(content);

  // Fill source
  const sourceInput = page.locator('input[placeholder*="playground" i]').or(
    page.locator('label:has-text("Source") + input')
  );
  if (await sourceInput.isVisible()) {
    await sourceInput.clear();
    await sourceInput.fill(source);
  }

  // Click Retain submit button
  await page.getByRole("button", { name: "Retain" }).click();
  // Wait for any decision type to appear in the result
  await expect(
    page.getByText(/^(CREATE|UPDATE|FORGET|NOOP)$/).first()
  ).toBeVisible({ timeout: 60_000 });
}

/**
 * Ingest a document via the Playground Retain tab's file upload.
 * Expands the "Upload a file" accordion, uploads a file from disk,
 * and waits for the WebSocket ingest to complete.
 */
/**
 * Load GROQ_API_KEY from environment or .env file.
 */
function getGroqApiKey(): string {
  if (process.env.GROQ_API_KEY) return process.env.GROQ_API_KEY;

  const envPath = path.join(__dirname, "..", ".env");
  if (fs.existsSync(envPath)) {
    const match = fs
      .readFileSync(envPath, "utf-8")
      .match(/^GROQ_API_KEY=(.+)$/m);
    if (match) return match[1].trim();
  }
  throw new Error("GROQ_API_KEY not found in environment or .env file");
}

/**
 * Use an LLM to judge the quality of extracted memories.
 * Calls Groq directly — this is a test evaluation utility, not a user action.
 *
 * Returns a score (1-5) and reasoning. The rubric should describe
 * what "good" looks like for the specific test scenario.
 *
 * Anti-flakiness: temperature 0, structured JSON output, generous threshold.
 */
export async function llmJudge(params: {
  rubric: string;
  memories: string[];
}): Promise<{ score: number; reasoning: string }> {
  const apiKey = getGroqApiKey();

  const resp = await fetch(
    "https://api.groq.com/openai/v1/chat/completions",
    {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: "llama-3.3-70b-versatile",
        temperature: 0,
        response_format: { type: "json_object" },
        messages: [
          {
            role: "system",
            content: [
              "You are a strict quality judge for a memory extraction system.",
              "A document was ingested and broken into individual memories.",
              "Evaluate the extracted memories against the provided rubric.",
              "",
              'Return JSON: {"score": <1-5>, "reasoning": "<2-3 sentences>"}',
              "",
              "Score guide:",
              "  1 = Poor: memories are mostly wrong, missing, or incoherent",
              "  2 = Below average: significant gaps or inaccuracies",
              "  3 = Acceptable: captures main ideas with minor gaps",
              "  4 = Good: comprehensive and accurate with minor omissions",
              "  5 = Excellent: thorough, accurate, nuanced",
            ].join("\n"),
          },
          {
            role: "user",
            content: `## Rubric\n${params.rubric}\n\n## Extracted memories (one per line)\n${params.memories.join("\n")}`,
          },
        ],
      }),
    }
  );

  if (!resp.ok) {
    const body = await resp.text();
    throw new Error(`LLM judge failed (${resp.status}): ${body}`);
  }

  const json = await resp.json();
  return JSON.parse(json.choices[0].message.content);
}

export async function ingestViaPlayground(
  page: Page,
  filePath: string,
  opts: { source?: string; namespace?: string } = {}
): Promise<void> {
  const { source = "e2e-test", namespace } = opts;

  await page.goto("/playground");
  await expect(
    page.getByRole("heading", { name: /playground/i })
  ).toBeVisible();

  // Ensure Retain tab is active (tabs use role="tab" per WCAG)
  await page.getByRole("tab", { name: "Retain" }).click();

  // Expand file upload accordion
  await page.getByText("Upload a file").click();

  // Upload file from filesystem
  await page.locator('input[type="file"]').setInputFiles(filePath);

  // Verify file info message is shown (textarea is hidden when file is selected)
  await expect(page.getByText(/will be ingested/i)).toBeVisible({
    timeout: 5_000,
  });

  // Fill source
  const sourceInput = page.locator('input[placeholder*="playground" i]').or(
    page.locator('label:has-text("Source") + input')
  );
  if (await sourceInput.isVisible()) {
    await sourceInput.clear();
    await sourceInput.fill(source);
  }

  // Click Ingest button
  const ingestButton = page.getByRole("button", { name: /ingest/i });
  await expect(ingestButton).toBeEnabled({ timeout: 5_000 });
  await ingestButton.click();

  // Wait for WebSocket streaming to complete
  await expect(page.getByText(/ingestion complete/i).first()).toBeVisible({
    timeout: 60_000,
  });

  // "View in Graph" button should appear after successful ingest
  await expect(
    page.getByRole("button", { name: /view in graph/i })
  ).toBeVisible({ timeout: 5_000 });
}
