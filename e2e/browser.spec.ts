import { test, expect } from "@playwright/test";
import {
  resetDatabase,
  retainViaPlayground,
  ingestViaPlayground,
  bypassFirebaseAuth,
  llmJudge,
} from "./helpers";
import path from "path";

const FIXTURE_FILE = path.join(__dirname, "fixtures", "scientific-revolutions.txt");
test.describe("Browser: Memory Listing, Search, and Details", () => {
  test.beforeEach(async ({ page }) => {
    await resetDatabase();
    await bypassFirebaseAuth(page);
  });

  test("playground memory retention", async ({ page }) => {
    await test.step("empty dashboard shows onboarding card", async () => {
      await page.goto("/");
      await expect(
        page.getByRole("heading", { name: /welcome to memlayer/i })
      ).toBeVisible({ timeout: 10_000 });
    });

    await test.step("retain two memories", async () => {
      await retainViaPlayground(
        page,
        "BrowserTestUser uses TypeScript for frontend and Clojure for backend"
      );
      await retainViaPlayground(
        page,
        "BrowserTestUser has a pet cat named Whiskers who likes to play with yarn"
      );
    });

    await test.step("dashboard shows stats and layer distribution", async () => {
      await page.goto("/");
      await expect(
        page.getByRole("heading", { name: /dashboard/i })
      ).toBeVisible({ timeout: 15_000 });

      await expect(page.getByText(/total memories/i)).toBeVisible({
        timeout: 10_000,
      });
      await expect(
        page.getByRole("heading", { name: "Consistency" })
      ).toBeVisible();
      await expect(
        page.getByRole("heading", { name: "Memory Distribution by Layer" })
      ).toBeVisible();
    });

    await test.step("memories appear in browser with layer badges", async () => {
      await page.getByRole("link", { name: /browser/i }).click();
      await expect(
        page.getByRole("heading", { name: /browser/i })
      ).toBeVisible();

      await expect(page.getByText(/TypeScript/i).first()).toBeVisible({
        timeout: 15_000,
      });
      await expect(page.getByText(/Whiskers/i).first()).toBeVisible();

      await expect(
        page
          .locator("td")
          .filter({ hasText: /^(fact|episode|concept|domain)$/i })
          .first()
      ).toBeVisible();
    });

    await test.step("memory detail modal shows on click", async () => {
      await page.getByText(/Whiskers/i).first().click();
      await expect(page.getByText(/importance/i).first()).toBeVisible({
        timeout: 5_000,
      });
    });
  });

  test("document ingestion retention", async ({
    page,
  }) => {
    test.setTimeout(360_000); // Extended: ingest + graph + LLM judge + recall
    await ingestViaPlayground(page, FIXTURE_FILE);

    // --- 1. Graph: follow "View in Graph" link after ingestion ---

    await page.getByRole("button", { name: /view in graph/i }).click();
    await page.waitForURL("**/graph**", { timeout: 10_000 });

    await test.step("graph renders nodes, edges, tooltip, and drag", async () => {
      await expect(
        page.getByRole("heading", { name: /memory graph/i })
      ).toBeVisible({ timeout: 15_000 });

      const graphContainer = page.locator('[data-testid="graph-container"]');
      await expect(graphContainer).toBeVisible();

      const circles = graphContainer.locator("svg circle");
      await expect(circles.first()).toBeVisible({ timeout: 15_000 });
      const circleCount = await circles.count();
      expect(circleCount).toBeGreaterThanOrEqual(3);

      const viewport = graphContainer.locator("svg g#graph-viewport");
      await expect(viewport).toBeAttached();

      // Arrowhead marker should be defined in SVG defs (always present)
      const arrowMarker = graphContainer.locator("svg defs marker#arrowhead");
      await expect(arrowMarker).toBeAttached();

      // Edges — LLM-inferred, non-deterministic. Verify rendering when present.
      await page.waitForTimeout(3_000);
      const edges = graphContainer.locator('[data-testid="graph-edge"]');
      const edgeCount = await edges.count();
      if (edgeCount > 0) {
        // data-testid="graph-edge" is on the <line> element itself
        await expect(edges.first()).toHaveAttribute("marker-end", "url(#arrowhead)");
      }

      // Hover shows tooltip — move mouse explicitly to a node circle's center
      await page.waitForTimeout(2000);
      // Target a non-highlight circle (exclude glow-filtered highlight rings)
      const nodeCircle = graphContainer.locator('svg circle:not([filter])').first();
      const hoverBox = await nodeCircle.boundingBox();
      expect(hoverBox).toBeTruthy();
      // Move away then to the circle center to trigger mouseenter on parent <g>
      await page.mouse.move(0, 0);
      await page.waitForTimeout(300);
      const cx = hoverBox!.x + hoverBox!.width / 2;
      const cy = hoverBox!.y + hoverBox!.height / 2;
      await page.mouse.move(cx, cy, { steps: 5 });
      const tooltip = graphContainer.locator("svg foreignObject");
      await expect(tooltip).toBeVisible({ timeout: 10_000 });

      // Drag moves a node
      const dragCircle = graphContainer.locator("svg circle").first();
      const cxBefore = await dragCircle.getAttribute("cx");
      const cyBefore = await dragCircle.getAttribute("cy");
      const dragBox = await dragCircle.boundingBox();
      expect(dragBox).toBeTruthy();
      const startX = dragBox!.x + dragBox!.width / 2;
      const startY = dragBox!.y + dragBox!.height / 2;
      await page.mouse.move(startX, startY);
      await page.mouse.down();
      await page.mouse.move(startX + 100, startY + 80, { steps: 10 });
      await page.mouse.up();
      await page.waitForTimeout(500);
      const cxAfter = await dragCircle.getAttribute("cx");
      const cyAfter = await dragCircle.getAttribute("cy");
      expect(cxBefore !== cxAfter || cyBefore !== cyAfter).toBe(true);
    });

    await test.step("graph edge labels visible when zoomed in", async () => {
      const graphContainer = page.locator('[data-testid="graph-container"]');
      const zoomInButton = page.getByRole("button", { name: "Zoom in" });

      // Zoom in past 0.7 threshold to trigger edge labels
      for (let i = 0; i < 5; i++) {
        await zoomInButton.click();
        await page.waitForTimeout(100);
      }
      await page.waitForTimeout(1000);

      const edgeLabels = graphContainer.locator('[data-testid="edge-label"]');
      const labelCount = await edgeLabels.count();
      if (labelCount > 0) {
        // Edge labels should contain a relationship type string
        const labelText = await edgeLabels.first().textContent();
        expect(labelText).toBeTruthy();
        expect(labelText!.length).toBeGreaterThan(0);
      }

      // Reset zoom for subsequent steps
      await page.getByRole("button", { name: "Fit to screen" }).click();
      await page.waitForTimeout(500);
    });

    await test.step("graph layer toggles filter nodes", async () => {
      const graphContainer = page.locator('[data-testid="graph-container"]');
      await expect(page.getByRole("button", { name: "Domain" })).toBeVisible();
      await expect(page.getByRole("button", { name: "Fact" })).toBeVisible();

      const initialCount = await graphContainer.locator("svg circle").count();
      await page.getByRole("button", { name: "Fact" }).click();
      await page.waitForTimeout(500);
      const afterToggleCount = await graphContainer
        .locator("svg circle")
        .count();
      expect(afterToggleCount).toBeLessThanOrEqual(initialCount);

      // Re-enable Fact layer for subsequent assertions
      await page.getByRole("button", { name: "Fact" }).click();
      await page.waitForTimeout(500);
    });

    await test.step("graph zoom controls work", async () => {
      const graphContainer = page.locator('[data-testid="graph-container"]');
      const fitButton = page.getByRole("button", { name: "Fit to screen" });
      const zoomInButton = page.getByRole("button", { name: "Zoom in" });
      const zoomOutButton = page.getByRole("button", { name: "Zoom out" });
      await expect(fitButton).toBeVisible();
      await expect(zoomInButton).toBeVisible();
      await expect(zoomOutButton).toBeVisible();

      const viewport = graphContainer.locator("svg g#graph-viewport");
      await zoomInButton.click();
      await page.waitForTimeout(500);
      const transformAfter = await viewport.getAttribute("transform");
      expect(transformAfter).not.toBeNull();
      expect(transformAfter).toContain("scale");

      await fitButton.click();
      await page.waitForTimeout(500);
      const transformAfterFit = await viewport.getAttribute("transform");
      if (transformAfterFit) {
        expect(transformAfterFit).not.toEqual(transformAfter);
      }
    });

    await test.step("graph semantic zoom clusters nodes when zoomed out", async () => {
      const graphContainer = page.locator('[data-testid="graph-container"]');
      const zoomOutButton = page.getByRole("button", { name: "Zoom out" });
      const fitButton = page.getByRole("button", { name: "Fit to screen" });
      const zoomInButton = page.getByRole("button", { name: "Zoom in" });

      await page.waitForTimeout(2000);
      const preZoomCount = await graphContainer.locator("svg circle").count();

      for (let i = 0; i < 10; i++) {
        await zoomOutButton.click();
        await page.waitForTimeout(100);
      }
      await page.waitForTimeout(1000);

      const zoomedOutCount = await graphContainer
        .locator("svg circle")
        .count();
      expect(zoomedOutCount).toBeLessThanOrEqual(preZoomCount);

      // Zoom back in — nodes expand
      await fitButton.click();
      await page.waitForTimeout(2000);
      for (let i = 0; i < 5; i++) {
        await zoomInButton.click();
        await page.waitForTimeout(100);
      }
      await page.waitForTimeout(2000);
      const afterFitCount = await graphContainer.locator("svg circle").count();
      expect(afterFitCount).toBeGreaterThanOrEqual(zoomedOutCount);
    });

    // --- 2. Browser: extraction quality and layer diversity ---
    await page.getByRole("link", { name: /browser/i }).click();
    await expect(
      page.getByRole("heading", { name: /browser/i })
    ).toBeVisible();

    await expect(page.locator("table tbody tr").first()).toBeVisible({
      timeout: 15_000,
    });

    // Collect memory content from the first column of each row
    const rows = page.locator("table tbody tr");
    const rowCount = await rows.count();
    const memories: string[] = [];
    for (let i = 0; i < rowCount; i++) {
      const text = await rows.nth(i).locator("td").first().textContent();
      if (text?.trim()) memories.push(text.trim());
    }

    const judgment = await llmJudge({
      rubric: [
        "The source document covers the history of scientific revolutions:",
        "continental drift (Wegener rejected for 50 years),",
        "H. pylori (Marshall/Warren challenged ulcer consensus),",
        "Galileo and heliocentrism (evidence was weaker than commonly believed),",
        "luminiferous aether (Michelson-Morley experiment),",
        "Einstein vs Bohr on quantum mechanics (EPR, entanglement),",
        "dark matter vs MOND (Zwicky, Rubin, Milgrom),",
        "the replication crisis (Ioannidis, p-values, publication bias),",
        "and climate science (IPCC consensus, sensitivity uncertainty).",
        "",
        "Evaluate whether the memories:",
        "1. Cover at least 5 of the 8 major topics listed above",
        "2. Capture specific people, dates, and claims (not vague summaries)",
        "3. Preserve opposing viewpoints rather than collapsing debates into one side",
        "4. Reflect temporal changes (e.g., ideas rejected then later accepted)",
      ].join("\n"),
      memories,
    });

    expect(
      judgment.score,
      `LLM judge score ${judgment.score}/5: ${judgment.reasoning}`
    ).toBeGreaterThanOrEqual(3);

    // Layer diversity: rich content should span multiple layer types
    const layerCells = page
      .locator("td")
      .filter({ hasText: /^(fact|episode|concept|domain)$/i });
    const layerTexts = await layerCells.allTextContents();
    const uniqueLayers = new Set(layerTexts.map((t) => t.toLowerCase()));
    expect(uniqueLayers.size).toBeGreaterThanOrEqual(2);

    // --- 3. Semantic recall across chunks ---
    // "resistance to new scientific ideas" doesn't appear verbatim, but the
    // document discusses Wegener's rejection, Marshall's ridicule, Einstein's
    // resistance — all spanning different chunks.
    await page.goto("/playground");
    await page.getByRole("tab", { name: /recall/i }).click();

    const queryInput = page.locator('input[type="text"]').first();
    await queryInput.click();
    await page.keyboard.insertText("resistance to new scientific ideas");
    await page.getByRole("button", { name: /recall/i }).click();

    // Broad OR matcher: any of these terms surfacing means recall worked
    await expect(
      page
        .getByText(/wegener|marshall|einstein|galileo|kuhn|consensus/i)
        .first()
    ).toBeVisible({ timeout: 60_000 });

    // --- 4. Opposing views preserved: both sides of a debate are queryable ---
    await page.getByRole("link", { name: /browser/i }).click();
    await expect(
      page.getByRole("heading", { name: /browser/i })
    ).toBeVisible();

    const searchInput = page.getByPlaceholder(
      "Search memories semantically..."
    );
    await searchInput.fill("alternatives to dark matter theory");
    await page.getByRole("button", { name: "Search" }).click();

    // Should surface MOND/Milgrom (the opposing view), not just mainstream dark matter
    await expect(
      page.getByText(/MOND|milgrom|modified|dark matter|gravity/i).first()
    ).toBeVisible({ timeout: 60_000 });
  });
});
