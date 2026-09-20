import { randomUUID } from "node:crypto";
import { expect, type Page } from "@playwright/test";

export async function register(page: Page) {
  await page.goto("/signin");
  await page.getByRole("button", { name: "Open your wardrobe" }).click();
  await page.getByRole("link", { name: "Register", exact: true }).click();
  const identity = randomUUID();
  await page
    .getByRole("textbox", { name: "Email", exact: false })
    .fill(`closetos-${identity}@example.test`);
  await page.locator("#password").fill(`Local-${identity}!`);
  await page.locator("#password-confirm").fill(`Local-${identity}!`);
  await page.getByRole("textbox", { name: "First name" }).fill("Wardrobe");
  await page.getByRole("textbox", { name: "Last name" }).fill("Tester");
  await page.getByRole("button", { name: "Register", exact: true }).click();
  await expect(page).toHaveURL(/\/wardrobe/);
  await expect(
    page.getByRole("heading", { name: "A little space for possibility." }),
  ).toBeVisible();
}
