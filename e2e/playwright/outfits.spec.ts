import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

test("studio persists pointer and keyboard arrangements, duplicates outfits, and records wear", async ({
  page,
  isMobile,
}) => {
  test.setTimeout(120_000);
  await register(page);
  const headers = { Origin: "http://localhost:3000" };
  const top = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: {
        name: "Cream studio knit",
        category: "TOP",
        purchasePrice: 60,
        purchaseCurrency: "GBP",
      },
    })
  ).json();
  const bottom = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: {
        name: "Indigo studio trousers",
        category: "BOTTOM",
        purchasePrice: 90,
        purchaseCurrency: "GBP",
      },
    })
  ).json();
  await page.goto("/studio");
  await page.getByRole("combobox", { name: "Drawer category", exact: true }).selectOption("TOP");
  await page.getByRole("button", { name: "Add Cream studio knit to outfit", exact: true }).click();
  await page.getByRole("combobox", { name: "Drawer category", exact: true }).selectOption("BOTTOM");
  await page
    .getByRole("button", { name: "Add Indigo studio trousers to outfit", exact: true })
    .click();
  await page.getByRole("button", { name: "Remove from canvas", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Position Indigo studio trousers", exact: true }),
  ).toHaveCount(0);
  await page
    .getByRole("button", { name: "Add Indigo studio trousers to outfit", exact: true })
    .click();
  const piece = page.getByRole("button", { name: "Position Indigo studio trousers", exact: true });
  await piece.focus();
  await piece.press("Shift+ArrowRight");
  await page.getByRole("slider", { name: "Scale", exact: true }).focus();
  await page.getByRole("slider", { name: "Scale", exact: true }).press("ArrowRight");
  await page.getByRole("slider", { name: "Rotation", exact: true }).focus();
  await page.getByRole("slider", { name: "Rotation", exact: true }).press("ArrowRight");
  await piece.scrollIntoViewIfNeeded();
  const bounds = (await piece.boundingBox())!;
  const x = bounds.x + bounds.width / 2,
    y = bounds.y + bounds.height / 2;
  if (isMobile) {
    const touch = await page.context().newCDPSession(page);
    await touch.send("Input.dispatchTouchEvent", {
      type: "touchStart",
      touchPoints: [{ x, y, id: 1 }],
    });
    await touch.send("Input.dispatchTouchEvent", {
      type: "touchMove",
      touchPoints: [{ x: x + 25, y: y + 35, id: 1 }],
    });
    await touch.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
    await touch.detach();
  } else {
    await page.mouse.move(x, y);
    await page.mouse.down();
    await page.mouse.move(x + 50, y + 60, { steps: 5 });
    await page.mouse.up();
  }
  await page.getByRole("button", { name: "Bring to front", exact: true }).click();
  await page
    .getByRole("textbox", { name: "Outfit name", exact: true })
    .fill("Considered everyday layers");
  await page.getByRole("textbox", { name: "Occasion", exact: true }).fill("Everyday");
  await page.getByRole("textbox", { name: "Season", exact: true }).fill("Autumn");
  await page.getByRole("combobox", { name: "Rating", exact: true }).selectOption("4");
  await page.getByRole("textbox", { name: "Outfit tags", exact: true }).fill("Favourite, Minimal");
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.getByRole("button", { name: "Save outfit", exact: true }).click();
  await expect(page).toHaveURL(/\/outfits\/[0-9a-f-]+$/);
  const id = page.url().split("/").pop();
  const saved = await (await page.request.get(`/api/backend/outfits/${id}`)).json();
  const moved = saved.items.find((item: { garmentId: string }) => item.garmentId === bottom.id);
  expect(moved.x).toBeGreaterThan(55);
  expect(moved.y).toBeGreaterThan(50);
  expect(moved.scale).toBeCloseTo(1.05);
  expect(moved.rotation).toBe(1);
  expect(saved).toMatchObject({
    occasion: "Everyday",
    season: "Autumn",
    rating: 4,
    tags: ["Favourite", "Minimal"],
  });
  await page.reload();
  await expect(page.getByRole("textbox", { name: "Outfit name", exact: true })).toHaveValue(
    saved.name,
  );
  await expect(piece).toHaveAttribute("style", expect.stringContaining(`left: ${moved.x}%;`));
  await expect(piece).toHaveAttribute("style", expect.stringContaining(`top: ${moved.y}%;`));
  await expect(piece).toHaveAttribute(
    "style",
    expect.stringContaining(`scale(${moved.scale}) rotate(${moved.rotation}deg)`),
  );
  expect((await (await page.request.get(`/api/backend/outfits/${id}`)).json()).items).toEqual(
    saved.items,
  );
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(page.getByText("Wear recorded for 2 pieces.", { exact: true })).toBeVisible();
  await expect(page.getByRole("region", { name: "Wear history" })).toContainText(saved.name);
  for (const garment of [top, bottom])
    expect(
      await (await page.request.get(`/api/backend/garments/${garment.id}`)).json(),
    ).toMatchObject({ wearCount: 1, costPerWear: garment.purchasePrice });
  await page.getByRole("button", { name: "Duplicate outfit", exact: true }).click();
  await expect(page).not.toHaveURL(new RegExp(`/outfits/${id}$`));
  await expect(page.getByRole("textbox", { name: "Outfit name", exact: true })).toHaveValue(
    `${saved.name} (copy)`,
  );
  const copyId = page.url().split("/").pop();
  expect((await (await page.request.get(`/api/backend/outfits/${copyId}`)).json()).items).toEqual(
    saved.items,
  );
  await page.getByRole("checkbox", { name: "Archive this outfit" }).check();
  await page.getByRole("button", { name: "Save outfit", exact: true }).click();
  await expect(page.getByText("Your outfit is saved.", { exact: true })).toBeVisible();
  await page.goto("/outfits");
  await expect(page.getByRole("heading", { name: saved.name, exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Archived outfits", exact: true }).click();
  await page.getByRole("heading", { name: `${saved.name} (copy)`, exact: true }).click();
  await page.getByRole("button", { name: "Delete outfit", exact: true }).click();
  await page.getByRole("button", { name: "Permanently delete outfit", exact: true }).click();
  await expect(page).toHaveURL(/\/outfits$/);
  expect((await page.request.get(`/api/backend/outfits/${copyId}`)).status()).toBe(404);
});

test("wear retry after a lost response records one event and removal repairs garment metrics", async ({
  page,
}) => {
  await register(page);
  const garment = await (
    await page.request.post("/api/backend/garments", {
      headers: { Origin: "http://localhost:3000" },
      data: { name: "Well worn knit", category: "TOP", purchasePrice: 60, purchaseCurrency: "GBP" },
    })
  ).json();
  await page.goto(`/garments/${garment.id}`);
  let intercepted = false;
  await page.route("**/api/backend/wear-events", async (route) => {
    if (route.request().method() !== "POST" || intercepted) return route.continue();
    intercepted = true;
    const committed = await route.fetch();
    expect(committed.status()).toBe(201);
    await route.abort("failed");
  });
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(page.getByText("Wear recorded for 1 piece.", { exact: true })).toBeVisible();
  expect(
    await (await page.request.get(`/api/backend/garments/${garment.id}`)).json(),
  ).toMatchObject({ wearCount: 1, costPerWear: 60 });
  expect(
    (await (await page.request.get(`/api/backend/wear-events?garmentId=${garment.id}`)).json())
      .items,
  ).toHaveLength(1);
  await page.getByRole("button", { name: /^Remove wear from/ }).click();
  await page.getByRole("button", { name: "Remove wear entry", exact: true }).click();
  await expect(
    page.getByText("Your first wear will start the story.", { exact: true }),
  ).toBeVisible();
  expect(
    await (await page.request.get(`/api/backend/garments/${garment.id}`)).json(),
  ).toMatchObject({ wearCount: 0, lastWornAt: null, costPerWear: 60 });
  await page.getByRole("link", { name: "Add to outfit", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Position Well worn knit", exact: true }),
  ).toBeVisible();
});
