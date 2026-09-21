import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

test("profile and photograph privacy preferences persist through navigation and refresh", async ({
  page,
}) => {
  await register(page);
  await page.getByRole("link", { name: "Settings", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Display name", exact: true })
    .fill("Considered wardrobe");
  await page.getByRole("textbox", { name: "Locale", exact: true }).fill("en-GB");
  await page.getByRole("textbox", { name: "Currency", exact: true }).fill("GBP");
  await page.getByRole("textbox", { name: "Timezone", exact: true }).fill("Europe/London");
  await page
    .getByRole("checkbox", { name: "Delete original photographs after background removal" })
    .check();
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("button", { name: "Save preferences" }).click();
  await expect(page.getByText("Your preferences are saved.", { exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole("textbox", { name: "Display name", exact: true })).toHaveValue(
    "Considered wardrobe",
  );
  await expect(
    page.getByRole("checkbox", { name: "Delete original photographs after background removal" }),
  ).toBeChecked();
  const profile = await (await page.request.get("/api/backend/me")).json();
  expect(profile).toMatchObject({
    currency: "GBP",
    timezone: "Europe/London",
    deleteOriginalAfterIsolation: true,
  });
  expect(profile.version).toBeGreaterThan(0);
});

test("batch capture reviews every completed photo and excludes saved pieces", async ({ page }) => {
  test.setTimeout(240_000);
  await register(page);
  await page.goto("/add");
  await expect(page.getByRole("button", { name: "Choose photographs", exact: true })).toBeEnabled();
  await page
    .getByLabel("Photograph files", { exact: true })
    .setInputFiles(["e2e/fixtures/shirt.png", "e2e/fixtures/shirt.png"]);
  await expect(page.getByRole("link", { name: "Review your piece" })).toHaveCount(2, {
    timeout: 180_000,
  });
  await page.getByRole("link", { name: "Review completed photographs" }).click();
  await expect(page.getByText("0 reviewed · 2 ready for review", { exact: true })).toBeVisible();
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("button", { name: "Review this piece later" }).click();
  await page
    .getByRole("textbox", { name: "Piece name *", exact: true })
    .fill("First reviewed shirt");
  await page.getByRole("combobox", { name: "Category *", exact: true }).selectOption("TOP");
  await page.getByRole("textbox", { name: "Material", exact: true }).fill("Cotton");
  await page.getByRole("textbox", { name: "Seasons", exact: true }).fill("Spring, Summer");
  await page.getByRole("button", { name: "Save reviewed piece", exact: true }).click();
  await expect(page.getByText("1 reviewed · 1 ready for review", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Review skipped pieces" }).click();
  await page
    .getByRole("textbox", { name: "Piece name *", exact: true })
    .fill("Second reviewed shirt");
  await page.getByRole("combobox", { name: "Category *", exact: true }).selectOption("TOP");
  await page.getByRole("button", { name: "Save reviewed piece", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "You’re all caught up.", exact: true }),
  ).toBeVisible();
  expect(
    await (
      await page.request.get("/api/backend/garments?processingStatus=READY_FOR_REVIEW")
    ).json(),
  ).toMatchObject({ items: [], nextCursor: null });
  await page.reload();
  await expect(
    page.getByRole("heading", { name: "You’re all caught up.", exact: true }),
  ).toBeVisible();
  await page.goto("/catalogue?category=TOP&season=Summer");
  await expect(
    page.getByRole("heading", { name: "First reviewed shirt", exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Second reviewed shirt", exact: true }),
  ).not.toBeVisible();
  await page.goto("/add");
  await expect(page.getByText("Ready in your wardrobe", { exact: true })).toHaveCount(2);
});
