import { test, expect } from "@playwright/test";
import { resetDatabase, retainViaPlayground, bypassFirebaseAuth, expectGraphWithNodes } from "./helpers";
import path from "path";

const SMALL_FIXTURE = path.join(__dirname, "fixtures", "pet-facts.txt");

test.describe("Playground: Retain, Ingest, and Recall", () => {
  test.beforeEach(async ({ page }) => {
    await resetDatabase();
    await bypassFirebaseAuth(page);
  });

  test("retain: decisions, graph highlights, and browser listing", async ({
    page,
  }) => {
    await test.step("retain content via playground", async () => {
      await page.goto("/playground");
      await expect(
        page.getByRole("heading", { name: /playground/i })
      ).toBeVisible();

      await page.getByRole("tab", { name: "Retain" }).click();

      const contentInput = page
        .getByRole("textbox", { name: /content/i })
        .or(page.locator("textarea").first());
      await contentInput.fill(
        "PlaywrightTestUser prefers Clojure for backend development and uses Emacs as their editor"
      );

      const sourceField = page.locator('input[placeholder*="playground" i]').or(
        page.locator('label:has-text("Source") + input')
      );
      if (await sourceField.isVisible()) {
        await sourceField.clear();
        await sourceField.fill("e2e-playwright");
      }

      await page.getByRole("button", { name: "Retain" }).click();

      // Wait for any decision type to appear in the result
      await expect(
        page.getByText(/^(CREATE|UPDATE|FORGET|NOOP)$/).first()
      ).toBeVisible({ timeout: 60_000 });
    });

    await test.step("View in Graph shows highlighted nodes and arrowhead markers", async () => {
      const viewInGraph = page.getByRole("button", { name: /view in graph/i });
      await expect(viewInGraph).toBeVisible({ timeout: 5_000 });
      await viewInGraph.click();

      await expectGraphWithNodes(page);

      const graphContainer = page.locator('[data-testid="graph-container"]');
      const highlightRings = graphContainer.locator('svg circle[filter="url(#glow)"]');
      await expect(highlightRings.first()).toBeVisible({ timeout: 5_000 });

      // Arrowhead marker should be defined (graph is ready to render directed edges)
      const arrowMarker = graphContainer.locator("svg defs marker#arrowhead");
      await expect(arrowMarker).toBeAttached();
    });

    await test.step("memories appear in Browser", async () => {
      await page.getByRole("link", { name: /browser/i }).click();
      await expect(
        page.getByRole("heading", { name: /browser/i })
      ).toBeVisible();
      // LLM extracts atomic facts — match keywords likely to survive extraction
      await expect(
        page.getByText(/clojure|emacs|backend/i).first()
      ).toBeVisible({ timeout: 15_000 });
    });
  });

  test("ingest: file upload, progress, and graph highlights", async ({
    page,
  }) => {
    await test.step("upload and ingest file", async () => {
      await page.goto("/playground");
      await expect(
        page.getByRole("heading", { name: /playground/i })
      ).toBeVisible();

      await page.getByRole("tab", { name: "Retain" }).click();
      await page.getByText("Upload a file").click();
      await page.locator('input[type="file"]').setInputFiles(SMALL_FIXTURE);

      await expect(page.getByText("pet-facts.txt", { exact: true })).toBeVisible({
        timeout: 5_000,
      });
      await expect(page.getByText(/will be ingested/i)).toBeVisible({
        timeout: 5_000,
      });

      const ingestButton = page.getByRole("button", { name: /ingest/i });
      await expect(ingestButton).toBeEnabled({ timeout: 5_000 });
      await ingestButton.click();
    });

    await test.step("progress bar and completion", async () => {
      await expect(page.locator(".bg-indigo-600").first()).toBeVisible({
        timeout: 30_000,
      });

      await expect(page.getByText(/ingestion complete/i).first()).toBeVisible({
        timeout: 60_000,
      });

      await expect(page.getByText(/created/i).first()).toBeVisible({
        timeout: 10_000,
      });
    });

    await test.step("View in Graph shows highlighted nodes and arrowhead markers", async () => {
      const viewInGraph = page.getByRole("button", { name: /view in graph/i });
      await expect(viewInGraph).toBeVisible({ timeout: 5_000 });
      await viewInGraph.click();

      await expectGraphWithNodes(page);

      const graphContainer = page.locator('[data-testid="graph-container"]');
      const highlightRings = graphContainer.locator('svg circle[filter="url(#glow)"]');
      await expect(highlightRings.first()).toBeVisible({ timeout: 5_000 });

      // Arrowhead marker should be defined (graph is ready to render directed edges)
      const arrowMarker = graphContainer.locator("svg defs marker#arrowhead");
      await expect(arrowMarker).toBeAttached();
    });
  });

  test("recall: ranked results after retain", async ({ page }) => {
    await test.step("pre-populate via retain", async () => {
      await retainViaPlayground(
        page,
        "RecallPlaywrightUser enjoys hiking in the mountains and photography"
      );
    });

    await test.step("recall returns matching results", async () => {
      await page.goto("/playground");
      await page.getByRole("tab", { name: /recall/i }).click();

      const queryInput = page.locator('input[type="text"]').first();
      await queryInput.click();
      await page.keyboard.insertText("outdoor activities");

      await page.getByRole("button", { name: /recall/i }).click();

      await expect(page.getByText(/hiking/i).first()).toBeVisible({
        timeout: 60_000,
      });
    });
  });
});
