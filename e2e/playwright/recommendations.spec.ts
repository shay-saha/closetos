import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { register } from "./register";

const origin = { Origin: "http://localhost:3000" };
async function reviewedPhoto(page: Page) {
  const photograph = await readFile("e2e/fixtures/shirt.png");
  const created = await page.request.post("/api/backend/garments/uploads", {
    headers: { ...origin, "Idempotency-Key": randomUUID() },
    data: {
      filename: "shirt.png",
      mimeType: "image/png",
      size: photograph.length,
      checksumSha256: createHash("sha256").update(photograph).digest("base64"),
      imageRole: "FRONT",
    },
  });
  expect(created.status()).toBe(201);
  const reserved = await created.json();
  const uploaded = await page.evaluate(
    async ({ upload, bytes }) => {
      const response = await fetch(upload.url, {
        method: upload.method,
        headers: upload.headers,
        body: new Uint8Array(bytes),
      });
      return response.status;
    },
    { upload: reserved.upload, bytes: [...photograph] },
  );
  expect(uploaded).toBe(200);
  await expect
    .poll(
      async () => {
        const response = await page.request.get(`/api/backend/processing/${reserved.imageId}`);
        expect(response.status()).toBe(200);
        const progress = await response.json();
        expect(progress.state, progress.failureDetail).not.toBe("FAILED");
        return progress.state;
      },
      { timeout: 120_000, intervals: [1000] },
    )
    .toBe("READY_FOR_REVIEW");
  const detail = await (
    await page.request.get(`/api/backend/garments/${reserved.garmentId}`)
  ).json();
  const approved = await page.request.patch(`/api/backend/garments/${reserved.garmentId}`, {
    headers: origin,
    data: {
      version: detail.version,
      name: "Olive cotton shirt",
      category: "TOP",
      material: "Cotton",
      primaryColourHex: "#45664c",
      primaryColourName: "Olive",
      formality: "Casual",
      seasonTags: ["winter"],
      styleTags: ["classic", "cold-weather"],
    },
  });
  expect(approved.status()).toBe(200);
  await expect
    .poll(
      async () => {
        const response = await page.request.get(
          `/api/backend/garments/${reserved.garmentId}/embedding`,
        );
        expect(response.status()).toBe(200);
        const state = await response.json();
        expect(state.state, state.failureCode).not.toBe("FAILED");
        return state.state;
      },
      { timeout: 60_000, intervals: [500, 1000] },
    )
    .toBe("READY");
  return reserved.garmentId as string;
}
async function accessible(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

test("real reviewed photos produce advisory duplicate pairs with keyboard explanations and private garment links", async ({
  page,
}, testInfo) => {
  test.setTimeout(240_000);
  await register(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  const pieces = await Promise.all([reviewedPhoto(page), reviewedPhoto(page)]);
  await page.goto("/discover/insights");
  await page.getByRole("link", { name: "Potential duplicates", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Potential duplicates." })).toBeVisible();
  await expect(
    page.getByText("Showing 1 of 1 potential matches in this comparison shortlist."),
  ).toBeVisible();
  const pair = page.getByRole("region", {
    name: "Compare Olive cotton shirt and Olive cotton shirt",
    exact: true,
  });
  await expect(pair.getByRole("link")).toHaveCount(2);
  await pair.locator("summary").press("Enter");
  await expect(
    pair.getByText("A potential match to review, rather than a confirmed duplicate."),
  ).toBeVisible();
  await expect(pair.getByText("Recorded colours are close.")).toBeVisible();
  await expect(pair.locator(".isolated-photo")).toHaveCount(2);
  await accessible(page);
  await page.screenshot({ path: testInfo.outputPath("potential-duplicates.png"), fullPage: true });
  await page.getByRole("combobox", { name: "Category", exact: true }).selectOption("TOP");
  await expect(page).toHaveURL(/category=TOP/);
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Category", exact: true })).toHaveValue("TOP");
  await pair.locator(`a[href="/garments/${pieces[0]}"]`).click();
  await expect(page).toHaveURL(new RegExp(`/garments/${pieces[0]}$`));
  await expect(
    page.getByRole("heading", { name: "Olive cotton shirt", exact: true }),
  ).toBeVisible();
  expect(errors).toEqual([]);
});

test("pairings use saved outfits and wear history while context and availability remain hard filters", async ({
  page,
}, testInfo) => {
  await register(page);
  const examples = [
    {
      name: "Winter cotton shirt",
      category: "TOP",
      primaryColourHex: "#45664c",
      formality: "Casual",
      seasonTags: ["winter"],
      styleTags: ["classic", "cold-weather"],
    },
    {
      name: "Winter linen trousers",
      category: "BOTTOM",
      primaryColourHex: "#555555",
      formality: "Casual",
      seasonTags: ["winter"],
      styleTags: ["classic", "cold-weather"],
    },
    {
      name: "Laundry trousers",
      category: "BOTTOM",
      formality: "Casual",
      seasonTags: ["winter"],
      styleTags: ["cold-weather"],
    },
    { name: "Unknown season trousers", category: "BOTTOM" },
    {
      name: "Another winter top",
      category: "TOP",
      formality: "Casual",
      seasonTags: ["winter"],
      styleTags: ["cold-weather"],
    },
  ];
  const pieces: string[] = [];
  for (const data of examples) {
    const created = await page.request.post("/api/backend/garments", { headers: origin, data });
    expect(created.status()).toBe(201);
    pieces.push((await created.json()).id);
  }
  const outfit = await page.request.post("/api/backend/outfits", {
    headers: origin,
    data: {
      name: "Winter everyday pairing",
      items: [pieces[0], pieces[1]].map((garmentId, index) => ({
        garmentId,
        x: 25 + index * 25,
        y: 40,
        scale: 1,
        rotation: 0,
        zIndex: index,
      })),
    },
  });
  expect(outfit.status()).toBe(201);
  const wear = await page.request.post("/api/backend/wear-events", {
    headers: { ...origin, "Idempotency-Key": randomUUID() },
    data: { garmentIds: [pieces[0], pieces[1]], wornOn: new Date().toISOString().slice(0, 10) },
  });
  expect(wear.status()).toBe(201);
  const laundry = await page.request.post(`/api/backend/garments/${pieces[2]}/status`, {
    headers: origin,
    data: { status: "LAUNDRY", version: 0 },
  });
  expect(laundry.status()).toBe(200);
  await page.goto(`/garments/${pieces[0]}`);
  await page.getByRole("link", { name: "Works with this", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Works with this." })).toBeVisible();
  await page.getByRole("combobox", { name: "Season", exact: true }).selectOption("WINTER");
  await page
    .getByRole("combobox", { name: "Weather assumption", exact: true })
    .selectOption("COLD");
  await page.getByRole("textbox", { name: "Formality", exact: true }).fill("Casual");
  await page.getByRole("button", { name: "Apply filters", exact: true }).click();
  await expect(page).toHaveURL(/season=WINTER&weather=COLD&formality=Casual/);
  await expect(
    page.getByText(
      "Showing 1 of 1 eligible pieces, ranked by how they complement the selected piece.",
    ),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /Winter linen trousers/ })).toBeVisible();
  await expect(
    page.getByRole("link", { name: /Laundry trousers|Another winter top|Unknown season trousers/ }),
  ).toHaveCount(0);
  await page.locator(".pairing-pieces summary").press("Enter");
  await expect(page.getByText("Recorded together in 1 wear entry.")).toBeVisible();
  await expect(page.getByText("Paired in 1 saved outfit.")).toBeVisible();
  await expect(
    page.getByText("Both pieces have explicit tags for the requested weather assumption."),
  ).toBeVisible();
  await accessible(page);
  await page.screenshot({ path: testInfo.outputPath("works-with-this.png"), fullPage: true });
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Season", exact: true })).toHaveValue("WINTER");
  await expect(page.getByRole("combobox", { name: "Weather assumption", exact: true })).toHaveValue(
    "COLD",
  );
  await page.getByRole("combobox", { name: "Weather assumption", exact: true }).selectOption("HOT");
  await page.getByRole("button", { name: "Apply filters", exact: true }).click();
  await expect(
    page.getByText(
      "The selected piece does not meet the requested filters. Adjust the filters or update its recorded details.",
    ),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: /Winter linen trousers/ })).toHaveCount(0);
  await accessible(page);
});
