import { test, expect } from "@playwright/test";
import { register } from "./register";

test("a photo allowance message preserves the local photograph across a refresh", async ({
  page,
}) => {
  await register(page);
  let denied = 0;
  const message = "You've reached the photo upload limit. Try again in 1 hour.";
  await page.route("**/api/backend/garments/uploads", async (route) => {
    denied++;
    await route.fulfill({
      status: 429,
      headers: { "Retry-After": "3600" },
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "ACTION_LIMIT", detail: message }),
    });
  });
  await page.goto("/add");
  await expect(page.getByRole("button", { name: "Choose photographs", exact: true })).toBeEnabled();
  await page
    .getByLabel("Photograph files", { exact: true })
    .setInputFiles("e2e/fixtures/shirt.png");
  await expect(page.getByRole("status").filter({ hasText: message })).toBeVisible();
  await page.reload();
  await expect(page.getByText("shirt.png", { exact: true })).toBeVisible();
  await expect(page.getByRole("status").filter({ hasText: message })).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry", exact: true })).toBeVisible();
  expect(denied).toBe(1);
  await page.getByRole("button", { name: "Dismiss", exact: true }).click();
  await expect(page.getByRole("list", { name: "Upload queue" })).not.toBeVisible();
});

test("semantic allowance messages offer ordinary keyword search without another inference", async ({
  page,
}) => {
  await register(page);
  let denied = 0;
  const message = "You've reached the semantic search limit. Try again in 1 hour.";
  await page.route("**/api/backend/search?**", async (route) => {
    const query = new URL(route.request().url()).searchParams;
    if (query.get("mode") !== "SEMANTIC") return route.continue();
    denied++;
    await route.fulfill({
      status: 429,
      headers: { "Retry-After": "3600" },
      contentType: "application/problem+json",
      body: JSON.stringify({ code: "ACTION_LIMIT", detail: message }),
    });
  });
  await page.goto("/search");
  await page.getByRole("searchbox").fill("cream knit");
  await page.getByRole("combobox", { name: "Search by", exact: true }).selectOption("SEMANTIC");
  await page.getByRole("button", { name: "Search wardrobe", exact: true }).click();
  const limitMessage = page.getByRole("alert").filter({ hasText: message });
  await expect(limitMessage).toBeVisible();
  await page.getByRole("button", { name: "Search words in details instead", exact: true }).click();
  await expect(page).toHaveURL(/mode=KEYWORD/);
  await expect(limitMessage).not.toBeVisible();
  await expect(page.getByRole("heading", { name: "No pieces found yet." })).toBeVisible();
  await expect(page.getByRole("searchbox")).toHaveValue("cream knit");
  expect(denied).toBe(1);
});
