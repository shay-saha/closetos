import { appOrigin } from "./environment";
import { randomUUID } from "node:crypto";
import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";

import { register } from "./register";

async function addPiece(page: Page, name: string) {
  await page.goto("/add");
  await page.getByRole("textbox", { name: "Piece name *", exact: true }).fill(name);
  await page.getByRole("textbox", { name: "Colour", exact: true }).fill("Cream");
  await page.getByRole("textbox", { name: "Material", exact: true }).fill("Cotton");
  await page.getByRole("button", { name: "Add to your wardrobe", exact: true }).click();
  await expect(page.getByRole("heading", { name, exact: true })).toBeVisible();
}

test("signup, persistence, catalogue, physical browsing, editing, and deletion", async ({
  page,
}) => {
  await register(page);
  await addPiece(page, "Cream knit");
  const firstPieceUrl = page.url();
  await page.reload();
  await expect(page.getByRole("heading", { name: "Cream knit", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Edit piece" }).click();
  await page
    .getByRole("textbox", { name: "Piece name *", exact: true })
    .fill("Favourite cream knit");
  await page.getByRole("button", { name: "Save changes" }).click();
  await expect(
    page.getByRole("heading", { name: "Favourite cream knit", exact: true }),
  ).toBeVisible();
  await addPiece(page, "Linen shirt");
  await page.goto("/catalogue");
  await expect(page.getByRole("heading", { name: "Linen shirt" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "Favourite cream knit" })).toBeVisible();
  await page.getByRole("combobox", { name: "Category" }).selectOption("SHOES");
  await expect(page.getByText("No pieces match these filters.", { exact: false })).toBeVisible();
  await page.getByRole("combobox", { name: "Category" }).selectOption("TOP");
  await expect(page.getByRole("heading", { name: "Linen shirt" })).toBeVisible();
  await page.goto("/wardrobe");
  const rail = page.getByRole("listbox");
  await expect(rail).toBeVisible();
  await rail.focus();
  await page.keyboard.press("ArrowRight");
  await expect(
    page.getByRole("option", { name: "Favourite cream knit, available" }),
  ).toHaveAttribute("aria-selected", "true");
  await page.keyboard.press("Enter");
  await expect(page).toHaveURL(firstPieceUrl);
  await page.goBack();
  await expect(
    page.getByRole("option", { name: "Favourite cream knit, available" }),
  ).toHaveAttribute("aria-selected", "true");
  await expect(page.locator(".rail-piece")).toHaveCount(2);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.goto(firstPieceUrl);
  await page.getByRole("button", { name: "Delete piece", exact: true }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await page.getByRole("button", { name: "Delete piece", exact: true }).click();
  await page.getByRole("button", { name: "Permanently delete", exact: true }).click();
  await expect(page).toHaveURL(/\/catalogue/);
  await expect(page.getByRole("heading", { name: "Favourite cream knit" })).not.toBeVisible();
  const exposedSession = await page.request.get("/api/auth/session");
  const session = await exposedSession.json();
  expect(session.accessToken).toBeUndefined();
  expect(session.refreshToken).toBeUndefined();
  const cookies = await page.context().cookies();
  expect(
    cookies
      .filter((cookie) => cookie.name.startsWith("next-auth.session-token"))
      .every((cookie) => cookie.httpOnly),
  ).toBe(true);
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page).toHaveURL(/\/signin/);
  await page.goto("/catalogue");
  await expect(page).toHaveURL(/\/signin/);
});

test("unauthenticated access and cross-origin writes are rejected", async ({ page }) => {
  const unauthenticated = await page.request.get("/api/backend/garments");
  expect(unauthenticated.status()).toBe(401);
  const forgedOrigin = await page.request.post("/api/backend/garments", {
    headers: { Origin: "https://untrusted.example" },
    data: { name: "Injected piece", category: "TOP" },
  });
  expect(forgedOrigin.status()).toBe(403);
});

test("sign-in is responsive and accessible", async ({ page }) => {
  await page.goto("/signin");
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  const results = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag22aa"])
    .analyze();
  expect(results.violations).toEqual([]);
});

test("200-piece rail stays virtualised and responds to pointer gestures", async ({
  page,
  browserName,
  isMobile,
}) => {
  await register(page);
  for (let batch = 0; batch < 20; batch++) {
    const results = await Promise.all(
      Array.from({ length: 10 }, (_, index) =>
        page.request.post("/api/backend/garments", {
          headers: { Origin: appOrigin },
          data: { name: `Rail piece ${batch * 10 + index}`, category: "TOP" },
        }),
      ),
    );
    for (const response of results) expect(response.status()).toBe(201);
  }
  await page.goto("/wardrobe");
  const rail = page.getByRole("listbox");
  await expect(rail).toBeVisible();
  const before = await rail.getAttribute("aria-activedescendant");
  const bounds = await rail.boundingBox();
  expect(bounds).not.toBeNull();
  const x = bounds!.x + bounds!.width * 0.65;
  const y = bounds!.y + bounds!.height * 0.45;
  if (isMobile && browserName === "chromium") {
    const session = await page.context().newCDPSession(page);
    await session.send("Input.dispatchTouchEvent", { type: "touchStart", touchPoints: [{ x, y }] });
    for (let step = 1; step <= 8; step++) {
      await session.send("Input.dispatchTouchEvent", {
        type: "touchMove",
        touchPoints: [{ x: x - step * 22, y }],
      });
      await page.waitForTimeout(30);
    }
    await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    await session.detach();
  } else {
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x - 210, y, { steps: 10 });
    await page.mouse.up();
  }
  await expect(rail).not.toHaveAttribute("aria-activedescendant", before!);
  expect(await page.locator(".rail-piece").count()).toBeLessThanOrEqual(7);
  await rail.focus();
  for (let index = 0; index < 65; index++) await page.keyboard.press("ArrowRight");
  await expect(page.getByText(/of 120/)).toBeVisible();
  expect(await page.locator(".rail-piece").count()).toBeLessThanOrEqual(7);
  if (isMobile) {
    await page.waitForTimeout(200);
    const rotations = await page
      .locator(".rail-piece")
      .evaluateAll((nodes) =>
        nodes.map((node) => new DOMMatrix(getComputedStyle(node).transform).b),
      );
    expect(rotations.every((rotation) => Math.abs(rotation) < 0.0001)).toBe(true);
  }
});
