import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

async function prepare(page: Page) {
  await register(page);
  const examples = [
    {
      name: "Olive cotton shirt",
      category: "TOP",
      primaryColourHex: "#45664c",
      material: "Cotton",
    },
    { name: "Sage linen shirt", category: "TOP", primaryColourHex: "#8b9273", material: "Linen" },
    { name: "Red high heels", category: "SHOES", primaryColourHex: "#953f2b" },
    { name: "Blue denim jeans", category: "BOTTOM", primaryColourHex: "#355f83" },
  ];
  const pieces: { id: string }[] = [];
  for (const data of examples) {
    const response = await page.request.post("/api/backend/garments", {
      headers: { Origin: "http://localhost:3000" },
      data,
    });
    expect(response.status()).toBe(201);
    pieces.push(await response.json());
  }
  await Promise.all(
    pieces.map(async (piece) => {
      await expect
        .poll(
          async () => {
            const state = await (
              await page.request.get(`/api/backend/garments/${piece.id}/embedding`)
            ).json();
            expect(state.state, state.failureCode).not.toBe("FAILED");
            return state.state;
          },
          { timeout: 60_000, intervals: [500, 1000] },
        )
        .toBe("READY");
    }),
  );
  return pieces;
}

test("topology filters and camera controls preserve history, expose relationships, and open a real garment from the canvas", async ({
  page,
}, testInfo) => {
  test.setTimeout(120_000);
  const pieces = await prepare(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.getByRole("link", { name: "Search", exact: true }).click();
  await page.getByRole("link", { name: "Wardrobe topology", exact: true }).click();
  await expect(page.getByRole("heading", { name: "See the connections." })).toBeVisible();
  const map = page.getByRole("group", { name: "Interactive wardrobe graph", exact: true });
  const canvas = map.locator("canvas");
  await expect(canvas).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("wardrobe-topology-overview.png"),
    fullPage: true,
  });
  await expect(
    page.getByText("4 pieces · 4 prepared for relationships", { exact: true }),
  ).toBeVisible();
  await page.getByRole("combobox", { name: "Colour by", exact: true }).selectOption("wear");
  await page.getByRole("checkbox", { name: "Show relationships", exact: true }).uncheck();
  await expect(page).toHaveURL(/colour=wear&edges=hidden/);
  await page.getByRole("combobox", { name: "Category", exact: true }).selectOption("TOP");
  await expect(
    page.getByText("2 pieces · 2 prepared for relationships", { exact: true }),
  ).toBeVisible();
  const list = page.getByRole("region", { name: "Pieces in this map", exact: true });
  await expect(list.getByRole("heading", { level: 3 })).toHaveCount(2);
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Category", exact: true })).toHaveValue("TOP");
  await expect(page.getByRole("combobox", { name: "Colour by", exact: true })).toHaveValue("wear");
  await expect(
    page.getByRole("checkbox", { name: "Show relationships", exact: true }),
  ).not.toBeChecked();
  await expect(canvas).toBeVisible();
  await page
    .getByRole("combobox", { name: "Highlight a piece", exact: true })
    .selectOption(pieces[0].id);
  await expect(
    page.getByRole("complementary", { name: "Highlighted piece", exact: true }),
  ).toContainText("Olive cotton shirt");
  const beforeZoom = await canvas.screenshot();
  await page.getByRole("button", { name: "Zoom in", exact: true }).click();
  expect((await canvas.screenshot()).equals(beforeZoom)).toBe(false);
  for (const label of [
    "Zoom out",
    "Rotate left",
    "Rotate right",
    "Pan left",
    "Pan right",
    "Reset view",
  ])
    await page.getByRole("button", { name: label, exact: true }).click();
  await page.getByRole("button", { name: "Focus selected piece", exact: true }).click();
  await canvas.scrollIntoViewIfNeeded();
  const box = (await canvas.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, box.y + box.height / 2);
  await expect(page.getByRole("tooltip")).toContainText("Olive cotton shirt");
  await expect(page.getByRole("tooltip")).toContainText("0 wears");
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("wardrobe-topology.png"), fullPage: true });
  await canvas.click({ position: { x: box.width / 2, y: box.height / 2 } });
  await expect(page).toHaveURL(new RegExp(`/garments/${pieces[0].id}$`));
  await expect(
    page.getByRole("heading", { name: "Olive cotton shirt", exact: true }),
  ).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("combobox", { name: "Category", exact: true })).toHaveValue("TOP");
  await page.getByRole("button", { name: "List view", exact: true }).click();
  await expect(canvas).toHaveCount(0);
  const row = list
    .locator("li")
    .filter({ has: page.getByRole("heading", { name: "Olive cotton shirt", exact: true }) })
    .first();
  await row.getByText("Related pieces (1)", { exact: true }).click();
  await row.getByRole("link", { name: "Sage linen shirt", exact: true }).click();
  await expect(page).toHaveURL(new RegExp(`/garments/${pieces[1].id}$`));
  expect(errors).toEqual([]);
});

test("reviewed garments and their named relationships remain accessible when WebGL is unavailable", async ({
  page,
}) => {
  test.setTimeout(120_000);
  const pieces = await prepare(page);
  await page.addInitScript(() => {
    const original = HTMLCanvasElement.prototype.getContext;
    HTMLCanvasElement.prototype.getContext = function (...args: Parameters<typeof original>) {
      if (String(args[0]).includes("webgl")) return null;
      return Reflect.apply(original, this, args);
    } as typeof original;
  });
  await page.goto("/discover/topology");
  await expect(
    page.getByText("The 3D view is unavailable here. Browse the pieces below.", { exact: true }),
  ).toBeVisible();
  const list = page.getByRole("region", { name: "Pieces in this map", exact: true });
  await expect(list.getByRole("heading", { level: 3 })).toHaveCount(4);
  const link = list.getByRole("link", { name: /Olive cotton shirt Tops/ });
  await link.focus();
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(new RegExp(`/garments/${pieces[0].id}$`));
  await page.goBack();
  await page.getByRole("button", { name: "List view", exact: true }).click();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
});

test("a thousand-piece topology uses a real worker while filters and list browsing stay responsive", async ({
  page,
}, testInfo) => {
  await register(page);
  await page.addInitScript(() => {
    const metrics = {
      started: 0,
      settled: 0,
      frames: 0,
      longTasks: [] as { start: number; duration: number }[],
    };
    Object.assign(window, { topologyMetrics: metrics });
    new PerformanceObserver((entries) => {
      for (const entry of entries.getEntries())
        metrics.longTasks.push({ start: entry.startTime, duration: entry.duration });
    }).observe({ type: "longtask", buffered: true });
    const OriginalWorker = Worker;
    window.Worker = new Proxy(OriginalWorker, {
      construct(Target, args) {
        const worker = Reflect.construct(Target, args) as Worker;
        const send = worker.postMessage;
        Object.defineProperty(worker, "postMessage", {
          value: (message: unknown, ...rest: unknown[]) => {
            metrics.started = performance.now();
            metrics.settled = 0;
            metrics.frames = 0;
            return Reflect.apply(send, worker, [message, ...rest]);
          },
        });
        worker.addEventListener("message", () => {
          metrics.settled = performance.now();
        });
        return worker;
      },
    });
    function frame() {
      if (metrics.started && !metrics.settled) metrics.frames++;
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  });
  const nodes = Array.from({ length: 1000 }, (_, n) => ({
    id: `00000000-0000-4000-8000-${n.toString(16).padStart(12, "0")}`,
    name: `Piece ${n.toString().padStart(4, "0")}`,
    category: n % 2 === 0 ? "TOP" : "DRESS",
    assets: null,
    colour: null,
    wearCount: n % 25,
    lastWornAt: null,
    costPerWear: null,
    purchaseCurrency: null,
    indexed: true,
  }));
  await page.route("**/api/backend/insights/topology?*", async (route) => {
    const category = new URL(route.request().url()).searchParams.get("category");
    const selected = nodes.filter((node) => !category || node.category === category);
    const edges = selected.flatMap((node, n) =>
      [1, 2, 3].map((offset) => ({
        source: node.id,
        target: selected[(n + offset) % selected.length].id,
        weight: 0.85,
      })),
    );
    await route.fulfill({
      json: {
        nodes: selected,
        edges,
        eligibleCount: selected.length,
        indexedCount: selected.length,
        truncated: false,
        model: { provider: "local-clip", modelId: "clip", dimensions: 512 },
        embeddingAvailability: "AVAILABLE",
      },
    });
  });
  await page.goto("/discover/topology");
  const canvas = page
    .getByRole("group", { name: "Interactive wardrobe graph", exact: true })
    .locator("canvas");
  await expect(canvas).toBeVisible({ timeout: 30_000 });
  const performanceData = await page.evaluate(
    () =>
      (
        window as unknown as {
          topologyMetrics: {
            started: number;
            settled: number;
            frames: number;
            longTasks: { start: number; duration: number }[];
          };
        }
      ).topologyMetrics,
  );
  expect(performanceData.started).toBeGreaterThan(0);
  expect(performanceData.settled - performanceData.started).toBeLessThan(15_000);
  expect(performanceData.frames).toBeGreaterThan(3);
  await testInfo.attach("worker-layout-performance", {
    contentType: "application/json",
    body: JSON.stringify({
      layoutDurationMs: performanceData.settled - performanceData.started,
      mainThreadFramesDuringLayout: performanceData.frames,
      longTasksDuringLayout: performanceData.longTasks.filter(
        (entry) => entry.start >= performanceData.started && entry.start < performanceData.settled,
      ),
    }),
  });
  expect(
    performanceData.longTasks
      .filter(
        (entry) => entry.start >= performanceData.started && entry.start < performanceData.settled,
      )
      .every((entry) => entry.duration < 200),
  ).toBe(true);
  const list = page.getByRole("region", { name: "Pieces in this map", exact: true });
  await expect(list.getByRole("heading", { level: 3 })).toHaveCount(60);
  await page.getByRole("combobox", { name: "Category", exact: true }).selectOption("TOP");
  await expect(
    page.getByText("500 pieces · 500 prepared for relationships", { exact: true }),
  ).toBeVisible();
  await page.getByRole("button", { name: "List view", exact: true }).click();
  await expect(canvas).toHaveCount(0);
  await page.getByRole("button", { name: "Show more pieces", exact: true }).click();
  await expect(list.getByRole("heading", { level: 3 })).toHaveCount(120);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({
    path: testInfo.outputPath("topology-thousand-piece-list.png"),
    fullPage: true,
  });
});
