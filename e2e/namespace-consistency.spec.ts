/**
 * Namespace consistency — memories retained under a namespace must only
 * appear in that namespace's dashboard, browser, and graph views.
 */
import { test, expect } from "@playwright/test";
import {
  bypassFirebaseAuth,
  resetDatabase,
  retainViaPlayground,
  createNamespace,
  switchNamespace,
  waitForConsistency,
} from "./helpers";

test.describe("Namespace consistency", () => {
  test.beforeEach(async ({ page }) => {
    await bypassFirebaseAuth(page);
    await resetDatabase();
  });

  test("memories are scoped to their namespace across all pages", async ({
    page,
  }) => {
    // -- 1. Retain first memory in default namespace, verify --
    await test.step("retain first memory in default namespace", async () => {
      await retainViaPlayground(page, "My name is Alice");
    });

    await test.step("graph shows 1 node after first retain", async () => {
      await page.goto("/graph");
      await expect(
        page.getByRole("heading", { name: /memory graph/i })
      ).toBeVisible({ timeout: 15_000 });
      await waitForConsistency(2000);
      const circles = page.locator("#graph-svg circle");
      await expect(circles).toHaveCount(1, { timeout: 15_000 });
    });

    await test.step("browser shows 1 memory after first retain", async () => {
      await page.goto("/browser");
      const rows = page.locator("tbody tr");
      await expect(rows).toHaveCount(1, { timeout: 15_000 });
      await expect(rows.first()).toContainText("Alice");
    });

    // -- 2. Retain second memory in default namespace, verify --
    await test.step("retain second memory in default namespace", async () => {
      await retainViaPlayground(page, "The capital of France is Paris");
    });

    await test.step("graph shows 2 nodes after second retain", async () => {
      await page.goto("/graph");
      await expect(
        page.getByRole("heading", { name: /memory graph/i })
      ).toBeVisible({ timeout: 15_000 });
      await waitForConsistency(2000);
      const circles = page.locator("#graph-svg circle");
      await expect(circles).toHaveCount(2, { timeout: 15_000 });
    });

    await test.step("browser shows 2 memories after second retain", async () => {
      await page.goto("/browser");
      const rows = page.locator("tbody tr");
      await expect(rows).toHaveCount(2, { timeout: 15_000 });
    });

    // -- 3. Create foo namespace, retain a rich memory --
    await test.step("create namespace foo and switch to it", async () => {
      await createNamespace(page, "foo");
      await switchNamespace(page, "foo");
    });

    await test.step("dashboard shows 0 memories for empty foo namespace", async () => {
      await page.goto("/");
      const totalCard = page.locator("text=Total Memories").locator("..");
      const totalValue = totalCard.locator("p.text-3xl");
      await expect(totalValue).toHaveText("0", { timeout: 15_000 });
    });

    await test.step("retain memory in foo namespace", async () => {
      await retainViaPlayground(page, "I enjoy playing chess on weekends");
    });

    await test.step(
      "graph shows nodes for foo namespace",
      async () => {
        await page.goto("/graph");
        await expect(
          page.getByRole("heading", { name: /memory graph/i })
        ).toBeVisible({ timeout: 15_000 });
        await waitForConsistency(2000);
        const circles = page.locator("#graph-svg circle");
        const count = await circles.count();
        expect(count).toBeGreaterThanOrEqual(1);
      }
    );

    await test.step(
      "browser shows foo memories",
      async () => {
        await page.goto("/browser");
        // Wait for at least one row to appear
        await expect(page.locator("tbody tr").first()).toBeVisible({
          timeout: 15_000,
        });
        // Verify at least one memory has a valid layer
        await expect(
          page
            .locator("td")
            .filter({ hasText: /^(fact|episode|concept|domain)$/i })
            .first()
        ).toBeVisible({ timeout: 15_000 });
      }
    );

    // -- 4. Switch back to default — should still have exactly 2 --
    await test.step("switch to default namespace", async () => {
      await switchNamespace(page, "default");
    });

    await test.step(
      "graph still shows 2 nodes for default namespace",
      async () => {
        await page.goto("/graph");
        await expect(
          page.getByRole("heading", { name: /memory graph/i })
        ).toBeVisible({ timeout: 15_000 });
        await waitForConsistency(2000);
        const circles = page.locator("#graph-svg circle");
        await expect(circles).toHaveCount(2, { timeout: 15_000 });
      }
    );

    await test.step(
      "browser still shows 2 memories for default namespace",
      async () => {
        await page.goto("/browser");
        await switchNamespace(page, "default");
        const rows = page.locator("tbody tr");
        await expect(rows).toHaveCount(2, { timeout: 15_000 });
      }
    );
  });
});
