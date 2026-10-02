import { appOrigin } from "./environment";
import { test, expect, type Page } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

const origin = { Origin: appOrigin };
type Piece = { id: string; name: string; category: string; version: number; status: string };
async function seed(page: Page) {
  const pieces: Piece[] = [];
  for (const [name, category] of [
    ["Linen travel shirt", "TOP"],
    ["Spare cotton shirt", "TOP"],
    ["Olive trousers", "BOTTOM"],
    ["Navy trousers", "BOTTOM"],
    ["Walking shoes", "SHOES"],
  ]) {
    const response = await page.request.post("/api/backend/garments", {
      headers: origin,
      data: {
        name,
        category,
        styleTags: ["mild-weather"],
        seasonTags: ["spring"],
        formality: "Casual",
        occasionTags: ["travel"],
      },
    });
    expect(response.status()).toBe(201);
    pieces.push(await response.json());
  }
  return pieces;
}
async function accessible(page: Page) {
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}
async function current(page: Page, id: string) {
  const response = await page.request.get(`/api/backend/packing-lists/${id}`);
  expect(response.status()).toBe(200);
  return response.json();
}

test("native capsule generation, required and excluded pieces, verified manual swaps, and packing survive refresh", async ({
  page,
}, testInfo) => {
  test.setTimeout(120_000);
  await register(page);
  const pieces = await seed(page);
  const errors: string[] = [];
  page.on("pageerror", (error) => errors.push(error.message));
  await page.getByRole("link", { name: "Pack", exact: true }).click();
  await page.getByRole("link", { name: "Create a trip", exact: true }).click();
  await page.getByLabel("Trip name *", { exact: true }).fill("A spring weekend");
  await page.getByLabel("Destination", { exact: true }).fill("London");
  await page.getByLabel("Start date *", { exact: true }).fill("2026-03-28");
  await page.getByLabel("End date *", { exact: true }).fill("2026-03-29");
  await page.getByLabel("Maximum pieces *", { exact: true }).fill("3");
  await page.getByLabel("Mild", { exact: true }).check();
  await page.getByLabel("Season", { exact: true }).selectOption("SPRING");
  await page.getByLabel("Occasion tag", { exact: true }).fill("travel");
  await page.getByLabel("Formality", { exact: true }).fill("Casual");
  await page.getByLabel("Require Linen travel shirt", { exact: true }).check();
  await page.getByLabel("Exclude Spare cotton shirt", { exact: true }).check();
  await accessible(page);
  await page.getByRole("button", { name: "Save trip", exact: true }).click();
  await expect(page).toHaveURL(/\/packing\/[0-9a-f-]+$/);
  const id = new URL(page.url()).pathname.split("/").at(-1)!;
  await page.getByRole("button", { name: "Generate capsule", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Optimised capsule", exact: true })).toBeVisible();
  await expect(
    page.getByText("3 unique pieces · 0 packed · 2 scheduled outfits", { exact: true }),
  ).toBeVisible();
  const planned = await current(page, id);
  expect(planned.plan.selectedGarments).toContain(pieces[0].id);
  expect(planned.plan.selectedGarments).not.toContain(pieces[1].id);
  expect(planned.plan.outfits).toHaveLength(2);
  expect(planned.schedule.map((entry: { date: string }) => entry.date)).toEqual([
    "2026-03-28",
    "2026-03-29",
  ]);
  await page.reload();
  await expect(page.getByLabel("Require Linen travel shirt", { exact: true })).toBeChecked();
  await expect(page.getByLabel("Exclude Spare cotton shirt", { exact: true })).toBeChecked();
  await page.getByLabel("Piece to replace", { exact: true }).selectOption(pieces[4].id);
  await page
    .getByRole("button", { name: "Choose Spare cotton shirt as replacement", exact: true })
    .click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Something needs another try.",
  );
  expect((await current(page, id)).version).toBe(planned.version);
  const selectedBottom = pieces.find(
    (piece) => piece.category === "BOTTOM" && planned.plan.selectedGarments.includes(piece.id),
  )!;
  const otherBottom = pieces.find(
    (piece) => piece.category === "BOTTOM" && piece.id !== selectedBottom.id,
  )!;
  await page.getByLabel("Piece to replace", { exact: true }).selectOption(selectedBottom.id);
  await page
    .getByRole("button", { name: `Choose ${otherBottom.name} as replacement`, exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Your adjusted capsule", exact: true }),
  ).toBeVisible();
  const adjusted = await current(page, id);
  expect(adjusted.manualOverride).toBe(true);
  expect(adjusted.plan.status).toBe("FEASIBLE");
  expect(adjusted.plan.selectedGarments).toContain(otherBottom.id);
  expect(adjusted.plan.selectedGarments).not.toContain(selectedBottom.id);
  await page.getByRole("button", { name: "Mark packed: Linen travel shirt", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Unpack: Linen travel shirt", exact: true }),
  ).toBeVisible();
  expect(
    (await (await page.request.get(`/api/backend/garments/${pieces[0].id}`)).json()).status,
  ).toBe("PACKED");
  await expect(
    page.getByRole("button", { name: "Regenerate capsule", exact: true }),
  ).toBeDisabled();
  await expect(page.getByLabel("Trip name *", { exact: true })).toBeDisabled();
  await page.reload();
  await page.getByRole("button", { name: "Unpack: Linen travel shirt", exact: true }).click();
  await expect(
    page.getByRole("button", { name: "Mark packed: Linen travel shirt", exact: true }),
  ).toBeVisible();
  expect(
    (await (await page.request.get(`/api/backend/garments/${pieces[0].id}`)).json()).status,
  ).toBe("AVAILABLE");
  await accessible(page);
  await page.screenshot({ path: testInfo.outputPath("packing-capsule.png"), fullPage: true });
  expect(errors).toEqual([]);
});

test("infeasible capsules explain the limit and stale edits require an explicit reload", async ({
  page,
}) => {
  test.setTimeout(120_000);
  await register(page);
  await seed(page);
  await page.goto("/packing/new");
  await page.getByLabel("Trip name *", { exact: true }).fill("A small bag");
  await page.getByLabel("Mild", { exact: true }).check();
  await page.getByLabel("Maximum pieces *", { exact: true }).fill("1");
  await page.getByRole("button", { name: "Save trip", exact: true }).click();
  await expect(page).toHaveURL(/\/packing\/[0-9a-f-]+$/);
  const id = new URL(page.url()).pathname.split("/").at(-1)!;
  await page.getByRole("button", { name: "Generate capsule", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "These constraints cannot be met", exact: true }),
  ).toBeVisible();
  const infeasible = await current(page, id);
  expect(infeasible.plan.status).toBe("INFEASIBLE");
  expect(infeasible.plan.selectedGarments).toEqual([]);
  expect(infeasible.plan.outfits).toEqual([]);
  await expect(page.locator(".packing-capsule-pieces")).toHaveCount(0);
  await accessible(page);
  const external = await page.request.patch(`/api/backend/packing-lists/${id}`, {
    headers: origin,
    data: { version: infeasible.version, name: "Changed in another tab" },
  });
  expect(external.status()).toBe(200);
  await page.locator(".packing-settings > summary").press("Enter");
  await page.getByLabel("Trip name *", { exact: true }).fill("My unsaved trip");
  await page.getByRole("button", { name: "Save trip changes", exact: true }).click();
  await expect(page.getByRole("main").getByRole("alert")).toContainText(
    "Reloading discards unsaved edits.",
  );
  await expect(page.getByLabel("Trip name *", { exact: true })).toHaveValue("My unsaved trip");
  await page.getByRole("button", { name: "Reload current trip", exact: true }).click();
  await expect(page.getByLabel("Trip name *", { exact: true })).toHaveValue(
    "Changed in another tab",
  );
  await page.getByLabel("Maximum pieces *", { exact: true }).fill("3");
  await page.getByRole("button", { name: "Save trip changes", exact: true }).click();
  await page.getByRole("button", { name: "Generate capsule", exact: true }).click();
  await expect(page.getByRole("heading", { name: "Optimised capsule", exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Delete trip", exact: true }).click();
  await page.getByRole("button", { name: "Delete trip permanently", exact: true }).click();
  await expect(page).toHaveURL(/\/packing$/);
  expect((await page.request.get(`/api/backend/packing-lists/${id}`)).status()).toBe(404);
});
