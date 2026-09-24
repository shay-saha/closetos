import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { randomUUID } from "node:crypto";
import { register } from "./register";

const origin = { Origin: "http://localhost:3000" };
async function prepare(page: Page) {
  await register(page);
  const purchased = new Date(Date.now() - 400 * 86_400_000).toISOString().slice(0, 10);
  const examples = [
    {
      name: "Everyday olive shirt",
      category: "TOP",
      purchasePrice: 60,
      purchaseCurrency: "GBP",
      wears: 4,
    },
    {
      name: "Unworn linen shirt",
      category: "TOP",
      purchasePrice: 80,
      purchaseCurrency: "GBP",
      wears: 0,
    },
    {
      name: "Dollar jacket",
      category: "OUTERWEAR",
      purchasePrice: 100,
      purchaseCurrency: "USD",
      wears: 1,
    },
    {
      name: "Gifted cotton shirt",
      category: "TOP",
      purchasePrice: 0,
      purchaseCurrency: "GBP",
      wears: 2,
    },
    { name: "Unknown price shirt", category: "TOP", wears: 0 },
  ];
  const pieces: string[] = [];
  for (const { wears, ...data } of examples) {
    const created = await page.request.post("/api/backend/garments", {
      headers: origin,
      data: { ...data, purchaseDate: purchased, seasonTags: ["winter"] },
    });
    expect(created.status()).toBe(201);
    const piece = await created.json();
    pieces.push(piece.id);
    for (let count = 0; count < wears; count++) {
      const recorded = await page.request.post("/api/backend/wear-events", {
        headers: { ...origin, "Idempotency-Key": randomUUID() },
        data: {
          garmentIds: [piece.id],
          wornOn: new Date(Date.now() - (count + 1) * 86_400_000).toISOString().slice(0, 10),
        },
      });
      expect(recorded.status()).toBe(201);
    }
  }
  return pieces;
}
async function usable(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

test("history insights explain forgotten pieces, compare currencies and refresh after recording wear", async ({
  page,
}, testInfo) => {
  const pieces = await prepare(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.getByRole("link", { name: "Search", exact: true }).click();
  await page.getByRole("link", { name: "Wardrobe insights", exact: true }).click();
  await expect(page.getByRole("heading", { name: "A fresh look at what you own." })).toBeVisible();
  await expect(
    page.getByText("Showing 2 of 2 eligible pieces, ranked from 5 reviewed pieces."),
  ).toBeVisible();
  await page.locator("summary").filter({ hasText: "Why this appeared" }).first().press("Enter");
  await expect(
    page.getByText("No season selected; season compatibility was not inferred.").first(),
  ).toBeVisible();
  await page
    .getByRole("combobox", { name: "Your current season", exact: true })
    .selectOption("WINTER");
  await expect(page).toHaveURL(/season=WINTER/);
  await page.reload();
  await expect(
    page.getByRole("combobox", { name: "Your current season", exact: true }),
  ).toHaveValue("WINTER");
  await usable(page);
  await page.screenshot({ path: testInfo.outputPath("forgotten-pieces.png"), fullPage: true });

  await page.getByRole("button", { name: "Wear history", exact: true }).click();
  await expect(
    page
      .locator(".insight-stats > div")
      .filter({ has: page.getByText("Reviewed pieces", { exact: true }) })
      .locator("dd"),
  ).toHaveText("5");
  await expect(
    page
      .locator(".insight-stats > div")
      .filter({ has: page.getByText("Garment wear occurrences", { exact: true }) })
      .locator("dd"),
  ).toHaveText("7");
  await usable(page);
  await page.getByRole("button", { name: "Cost per wear", exact: true }).click();
  await expect(page.getByRole("combobox", { name: "Recorded currency", exact: true })).toHaveValue(
    "GBP",
  );
  await expect(page.getByText("£140.00", { exact: true })).toBeVisible();
  const lowest = page.getByRole("region", { name: "Lowest cost per wear", exact: true });
  await expect(lowest.getByRole("link", { name: /Gifted cotton shirt/ })).toContainText(
    "£0.00 per recorded wear",
  );
  await expect(lowest.getByRole("link", { name: /Everyday olive shirt/ })).toContainText(
    "£15.00 per recorded wear",
  );
  const never = page.getByRole("region", { name: "Never worn purchases", exact: true });
  await expect(never.getByRole("link", { name: /Unworn linen shirt/ })).toContainText(
    "£80.00 purchase price · never worn",
  );
  await expect(page.getByText(/1 piece has no purchase price/)).toBeVisible();
  await page.getByRole("combobox", { name: "Recorded currency", exact: true }).selectOption("USD");
  await expect(page.getByText("US$100.00", { exact: true })).toBeVisible();
  await expect(lowest.getByRole("link", { name: /Dollar jacket/ })).toBeVisible();
  await expect(lowest.getByRole("link", { name: /Everyday olive shirt/ })).toHaveCount(0);
  await usable(page);
  await page.screenshot({ path: testInfo.outputPath("cost-per-wear.png"), fullPage: true });
  await page.getByRole("combobox", { name: "Recorded currency", exact: true }).selectOption("GBP");
  await never.getByRole("link", { name: /Unworn linen shirt/ }).click();
  await expect(page).toHaveURL(new RegExp(`/garments/${pieces[1]}$`));
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(
    page.getByRole("status").filter({ hasText: "Wear recorded for 1 piece." }),
  ).toBeVisible();
  await page.goBack();
  await expect(never.getByRole("link", { name: /Unworn linen shirt/ })).toHaveCount(0);
  await expect(lowest.getByRole("link", { name: /Unworn linen shirt/ })).toContainText(
    "£80.00 per recorded wear",
  );
  await page.getByRole("button", { name: "Forgotten pieces", exact: true }).click();
  await expect(
    page.getByText("Showing 1 of 1 eligible pieces, ranked from 5 reviewed pieces."),
  ).toBeVisible();
  await expect(
    page.getByRole("combobox", { name: "Your current season", exact: true }),
  ).toHaveValue("WINTER");
  expect(errors).toEqual([]);
});

test("empty insights distinguish missing wardrobe history from zero-cost purchases", async ({
  page,
}) => {
  await register(page);
  await page.goto("/discover/insights");
  await expect(page.getByRole("heading", { name: "No wardrobe history yet." })).toBeVisible();
  await usable(page);
  await page.getByRole("button", { name: "Cost per wear", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "A little more context is needed." }),
  ).toBeVisible();
  await expect(page.getByRole("combobox", { name: "Recorded currency", exact: true })).toHaveCount(
    0,
  );
  await expect(page.getByText("£0.00", { exact: true })).toHaveCount(0);
  await usable(page);
  await page.getByRole("button", { name: "Wear history", exact: true }).click();
  await expect(
    page.getByText("No wear has been recorded yet. Open a piece or an outfit to log a wear."),
  ).toBeVisible();
  await expect(
    page.getByRole("region", { name: "Most worn", exact: true }).getByRole("link"),
  ).toHaveCount(0);
  await usable(page);
});
