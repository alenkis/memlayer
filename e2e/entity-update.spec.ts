import { test, expect } from "@playwright/test";
import {
  resetDatabase,
  retainViaPlayground,
  bypassFirebaseAuth,
  llmJudge,
  switchNamespace,
} from "./helpers";

test.describe("Entity Update: Conflicting Info Updates Instead of Duplicates", () => {
  // LLM decisions are non-deterministic; allow retries for this behavioral test
  test.describe.configure({ retries: 2 });

  test.beforeEach(async ({ page }) => {
    await resetDatabase();
    await bypassFirebaseAuth(page);
  });

  test("retaining a new value for the same fact updates the existing memory", async ({
    page,
  }) => {
    // Step 1: Retain initial fact
    await retainViaPlayground(
      page,
      "TestConflictUser's favorite color is blue",
      { source: "e2e-conflict-test" }
    );

    // Step 2: Retain conflicting fact (same single-valued attribute, new value)
    await retainViaPlayground(
      page,
      "TestConflictUser's favorite color is yellow",
      { source: "e2e-conflict-test" }
    );

    // Step 3: Navigate to Browser and collect memories
    await page.goto("/browser");
    await expect(
      page.getByRole("heading", { name: /browser/i })
    ).toBeVisible();
    // Trigger data refetch after full page navigation
    await switchNamespace(page, "default");

    // Wait briefly for memories to load — may be 0, 1, or 2 depending on LLM decision
    await page.waitForTimeout(3000);

    // Collect all memory contents
    const rows = page.locator("table tbody tr");
    const rowCount = await rows.count();
    const memories: string[] = [];
    for (let i = 0; i < rowCount; i++) {
      const text = await rows.nth(i).locator("td").first().textContent();
      if (text?.trim()) memories.push(text.trim());
    }

    // If LLM chose FORGET (deleted the memory), fail early with a clear message
    // so Playwright retries rather than calling the LLM judge with empty data
    expect(
      memories.length,
      "Expected at least one memory after two retains (LLM may have chosen FORGET — retrying)"
    ).toBeGreaterThanOrEqual(1);

    // Step 4: Use LLM judge to verify the update happened correctly
    const judgment = await llmJudge({
      rubric: [
        "The user first said their favorite color is blue, then said it is yellow.",
        "The memory system should have UPDATED the existing memory, not created a duplicate.",
        "",
        "Evaluate:",
        "1. There should be exactly ONE memory about the user's favorite color (not two)",
        "2. That memory should reflect 'yellow' as the current favorite color",
        "3. The old value 'blue' should NOT appear as a separate, active memory",
        "",
        "Score 5 if there's exactly one color-preference memory saying yellow.",
        "Score 3 if there are two memories but the latest one says yellow.",
        "Score 1 if the old value 'blue' is the only or primary memory.",
      ].join("\n"),
      memories,
    });

    expect(
      judgment.score,
      `LLM judge: ${judgment.score}/5 — ${judgment.reasoning}`
    ).toBeGreaterThanOrEqual(4);
  });
});
