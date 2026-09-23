import { readFile } from "node:fs/promises";
import { randomUUID } from "node:crypto";
import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

test("search interface preserves hard filters and photo history, explains matches, and refreshes after wear", async ({
  page,
}, testInfo) => {
  test.setTimeout(150_000);
  const { garments } = await prepareSearchWardrobe(page);
  const shirts = ["Olive cotton shirt", "Sage linen shirt"];
  await page.getByRole("link", { name: "Search", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Find what feels right." })).toBeVisible();
  await page.getByRole("searchbox").fill("a light green button down shirt");
  await page.getByRole("combobox", { name: "Search by", exact: true }).selectOption("SEMANTIC");
  await page.getByRole("button", { name: "Search wardrobe", exact: true }).click();
  await expect(page).toHaveURL(/mode=SEMANTIC&q=/);
  await expect(page.getByRole("heading", { name: "Related pieces", exact: true })).toBeVisible();
  const cards = page.locator(".search-results .piece-card");
  await expect(cards).toHaveCount(5);
  expect(shirts).toContain(await cards.first().getByRole("heading").textContent());
  expect(shirts).toContain(await cards.nth(1).getByRole("heading").textContent());
  await page.getByText("Choose filters", { exact: true }).click();
  await page.getByRole("combobox", { name: "Category", exact: true }).selectOption("SHOES");
  await page.getByRole("button", { name: "Apply filters", exact: true }).click();
  await expect(cards).toHaveCount(1);
  await expect(cards.first().getByRole("heading")).toHaveText("Red high heels");
  await page.getByRole("button", { name: "Remove Category filter", exact: true }).click();
  await expect(cards).toHaveCount(5);
  await page.goBack();
  await expect(cards).toHaveCount(1);
  await expect(page).toHaveURL(/category=SHOES/);
  await page.getByRole("button", { name: "Remove Category filter", exact: true }).click();
  await page.getByRole("searchbox").fill("");
  await page.getByText("Search with a photograph", { exact: true }).click();
  await page
    .getByLabel("Reference photograph", { exact: true })
    .setInputFiles("e2e/fixtures/shirt.png");
  await expect(page.getByAltText("Your search reference photograph")).toBeVisible();
  await page.getByRole("button", { name: "Search wardrobe", exact: true }).click();
  await expect(page).toHaveURL(/photo=[0-9a-f-]+/);
  expect(page.url()).not.toMatch(/base64|shirt\.png/);
  await expect(cards).toHaveCount(5);
  await expect(cards.first()).toContainText("Related to your photograph");
  expect(shirts).toContain(await cards.first().getByRole("heading").textContent());
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: testInfo.outputPath("photo-search.png"), fullPage: true });
  const referenceName = await cards.first().getByRole("heading").textContent();
  await cards.first().click();
  await page.getByRole("link", { name: "Find similar pieces", exact: true }).click();
  await expect(page).toHaveURL(/similarToGarmentId=/);
  await expect(cards).toHaveCount(4);
  expect(shirts).toContain(await cards.first().getByRole("heading").textContent());
  expect(await cards.first().getByRole("heading").textContent()).not.toBe(referenceName);
  await expect(page.locator(".search-source")).toContainText(referenceName!);
  await page.goBack();
  await page.goBack();
  await expect(page).toHaveURL(/photo=/);
  await expect(page.getByAltText("Your search reference photograph")).toBeVisible();
  await expect(cards).toHaveCount(5);
  await page.reload();
  await expect(page.getByRole("heading", { name: "Select your photograph again." })).toBeVisible();
  await page.getByRole("button", { name: "Continue without a photograph", exact: true }).click();
  await expect(cards).toHaveCount(5);
  await page.getByRole("searchbox").fill("black formal dress not worn recently");
  await page.getByRole("combobox", { name: "Search by", exact: true }).selectOption("HYBRID");
  await page.getByRole("button", { name: "Search wardrobe", exact: true }).click();
  await expect(cards).toHaveCount(1);
  await expect(cards.first().getByRole("heading")).toHaveText("Black evening dress");
  await expect(
    page
      .getByRole("region", { name: "Filters recognised in your description" })
      .getByRole("listitem"),
  ).toHaveCount(4);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await cards.first().click();
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(page.getByText("Wear recorded for 1 piece.", { exact: true })).toBeVisible();
  await page.goBack();
  await expect(page.getByRole("heading", { name: "No pieces found yet." })).toBeVisible();
  await expect(cards).toHaveCount(0);
  expect((await page.request.get(`/api/backend/garments/${garments[4].id}`)).status()).toBe(200);
});

async function search(page: Page, params: Record<string, string>) {
  const response = await page.request.get(`/api/backend/search?${new URLSearchParams(params)}`);
  expect(response.status()).toBe(200);
  return response.json();
}

async function prepareSearchWardrobe(page: Page) {
  await register(page);
  const headers = { Origin: "http://localhost:3000" };
  const examples = [
    { name: "Olive cotton shirt", category: "TOP", primaryColourName: "Olive", material: "Cotton" },
    { name: "Sage linen shirt", category: "TOP", primaryColourName: "Green", material: "Linen" },
    { name: "Red high heels", category: "SHOES", primaryColourName: "Red", material: "Leather" },
    { name: "Blue denim jeans", category: "BOTTOM", primaryColourName: "Blue", material: "Denim" },
    {
      name: "Black evening dress",
      category: "DRESS",
      primaryColourName: "Black",
      formality: "Formal",
    },
  ];
  const garments: { id: string }[] = [];
  for (const data of examples) {
    const response = await page.request.post("/api/backend/garments", { headers, data });
    expect(response.status()).toBe(201);
    garments.push(await response.json());
  }
  await Promise.all(
    garments.map(async (garment) => {
      await expect
        .poll(
          async () => {
            const response = await page.request.get(
              `/api/backend/garments/${garment.id}/embedding`,
            );
            const status = await response.json();
            expect(status.state, status.failureCode).not.toBe("FAILED");
            return status.state;
          },
          { timeout: 60_000, intervals: [500, 1000] },
        )
        .toBe("READY");
    }),
  );
  return { garments, headers };
}

test("learned text and photo retrieval find related pieces while filters and wear dates stay hard", async ({
  page,
  browser,
}) => {
  test.setTimeout(150_000);
  const { garments, headers } = await prepareSearchWardrobe(page);
  const shirts = garments.slice(0, 2).map((garment) => garment.id);
  const text = await search(page, { q: "a light green button down shirt", mode: "SEMANTIC" });
  expect(shirts).toContain(text.items[0].garment.id);
  expect(shirts).toContain(text.items[1].garment.id);
  expect(text.indexedCount).toBe(5);
  expect(JSON.stringify(text)).not.toMatch(/"(?:score|vector|modelKey|percentage)"/);
  const restricted = await search(page, {
    q: "a light green button down shirt",
    mode: "SEMANTIC",
    category: "SHOES",
  });
  expect(restricted.items.map((hit: { garment: { id: string } }) => hit.garment.id)).toEqual([
    garments[2].id,
  ]);
  const similar = await page.request.get(`/api/backend/garments/${garments[0].id}/similar`);
  expect(similar.status()).toBe(200);
  const neighbours = await similar.json();
  expect(neighbours.items[0].garment.id).toBe(garments[1].id);
  expect(
    neighbours.items.every((hit: { garment: { id: string } }) => hit.garment.id !== garments[0].id),
  ).toBe(true);

  const photograph = await readFile("e2e/fixtures/shirt.png");
  const image = await page.request.post("/api/backend/search/image", {
    headers,
    data: { imageBase64: photograph.toString("base64"), mode: "SEMANTIC" },
  });
  expect(image.status()).toBe(200);
  const visual = await image.json();
  expect(shirts).toContain(visual.items[0].garment.id);
  expect(visual.items[0].explanations).toContain("Related to your photograph");

  const query = { q: "black formal dress not worn recently", mode: "HYBRID" };
  const beforeWear = await search(page, query);
  expect(beforeWear.items.map((hit: { garment: { id: string } }) => hit.garment.id)).toEqual([
    garments[4].id,
  ]);
  expect(beforeWear.appliedConstraints).toHaveLength(4);
  const worn = await page.request.post("/api/backend/wear-events", {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: { wornOn: new Date().toISOString().slice(0, 10), garmentIds: [garments[4].id] },
  });
  expect(worn.status()).toBe(201);
  expect((await search(page, query)).items).toEqual([]);

  const ids = new Set<string>();
  let cursor: string | null = null;
  do {
    const result = await search(page, {
      q: "a light green button down shirt",
      mode: "SEMANTIC",
      limit: "2",
      ...(cursor ? { cursor } : {}),
    });
    for (const hit of result.items) {
      expect(ids.has(hit.garment.id)).toBe(false);
      ids.add(hit.garment.id);
    }
    cursor = result.nextCursor;
  } while (cursor);
  expect(ids.size).toBe(5);

  const stranger = await browser.newContext({ baseURL: "http://localhost:3000" });
  try {
    const other = await stranger.newPage();
    await register(other);
    expect((await search(other, { q: "green shirt", mode: "SEMANTIC" })).items).toEqual([]);
    expect(
      (await other.request.get(`/api/backend/garments/${garments[0].id}/similar`)).status(),
    ).toBe(404);
  } finally {
    await stranger.close();
  }
});
