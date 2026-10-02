import { appOrigin } from "./environment";
import { expect, test } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

const headers = { Origin: appOrigin };

test("manual selection and nested smart rules persist and respond to wear history", async ({
  page,
}) => {
  await register(page);
  const top = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: { name: "Cream collection knit", category: "TOP", occasionTags: ["Work"] },
    })
  ).json();
  const bottom = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: { name: "Indigo collection trousers", category: "BOTTOM" },
    })
  ).json();
  await page.goto("/catalogue");
  await page.getByRole("link", { name: "Saved collections", exact: true }).click();
  await page.getByRole("link", { name: "Create a collection", exact: true }).click();
  await page.getByRole("textbox", { name: "Collection name", exact: true }).fill("Weekday capsule");
  await page.getByRole("combobox", { name: "Selection category", exact: true }).selectOption("TOP");
  await page.getByRole("checkbox", { name: top.name, exact: true }).check();
  await page
    .getByRole("combobox", { name: "Selection category", exact: true })
    .selectOption("BOTTOM");
  await page.getByRole("checkbox", { name: bottom.name, exact: true }).check();
  await expect(
    page.getByText("2 selected. Selections stay selected when you change filters.", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Save collection", exact: true }).click();
  await expect(page).toHaveURL(/\/collections\/[0-9a-f-]{36}$/);
  const manualPath = new URL(page.url()).pathname;
  await page.reload();
  await expect(page.getByRole("checkbox", { name: top.name, exact: true })).toBeChecked();
  await expect(page.getByRole("checkbox", { name: bottom.name, exact: true })).toBeChecked();
  await expect(page.getByRole("link", { name: /Cream collection knit Tops/ })).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);

  await page.goto("/collections/new");
  await page
    .getByRole("textbox", { name: "Collection name", exact: true })
    .fill("Unworn work edit");
  await page.getByRole("combobox", { name: "How to collect", exact: true }).selectOption("SMART");
  await page.getByRole("button", { name: "Add group", exact: true }).click();
  const nested = page.getByRole("group", { name: "Rule group", exact: true }).nth(1);
  await nested.getByRole("combobox", { name: "Field", exact: true }).selectOption("occasion");
  await nested.getByRole("textbox", { name: "Value", exact: true }).fill("Work");
  await nested.getByRole("button", { name: "Add condition", exact: true }).click();
  await nested
    .getByRole("combobox", { name: "Field", exact: true })
    .nth(1)
    .selectOption("category");
  await page.getByRole("button", { name: "Save collection", exact: true }).click();
  await expect(page).toHaveURL(/\/collections\/[0-9a-f-]{36}$/);
  const smartPath = new URL(page.url()).pathname;
  const saved = await (await page.request.get(`/api/backend${smartPath}`)).json();
  expect(saved.queryDefinition).toEqual({
    all: [
      { field: "wearCount", operator: "EQ", value: 0 },
      {
        any: [
          { field: "occasion", operator: "CONTAINS", value: "Work" },
          { field: "category", operator: "EQ", value: "TOP" },
        ],
      },
    ],
  });
  await expect(page.getByRole("link", { name: /Cream collection knit Tops/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /Indigo collection trousers Bottoms/ })).toHaveCount(
    0,
  );
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Match", exact: true }).nth(1)).toHaveValue(
    "any",
  );
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.getByRole("link", { name: /Cream collection knit Tops/ }).click();
  await page.getByRole("button", { name: "Mark worn", exact: true }).click();
  await expect(page.getByText("Wear recorded for 1 piece.", { exact: true })).toBeVisible();
  await page.goto(smartPath);
  await expect(
    page.getByRole("heading", { name: "No matching pieces in this collection.", exact: true }),
  ).toBeVisible();
  await page.goto(`/garments/${top.id}`);
  await page.getByRole("button", { name: /^Remove wear from/ }).click();
  await page.getByRole("button", { name: "Remove wear entry", exact: true }).click();
  await expect(
    page.getByText("Your first wear will start the story.", { exact: true }),
  ).toBeVisible();
  await page.goto(smartPath);
  await expect(page.getByRole("link", { name: /Cream collection knit Tops/ })).toBeVisible();
  await page.goto(manualPath);
  await page.getByRole("checkbox", { name: bottom.name, exact: true }).uncheck();
  await page.getByRole("button", { name: "Save collection", exact: true }).click();
  await expect(page.getByText("Collection saved.", { exact: true })).toBeVisible();
  await expect(page.getByRole("link", { name: /Indigo collection trousers Bottoms/ })).toHaveCount(
    0,
  );
});

test("catalogue preserves advanced filters and stale collection edits require a reload", async ({
  page,
}) => {
  await register(page);
  const garment = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: {
        name: "Cream linen work shirt",
        category: "TOP",
        brand: "Studio",
        primaryColourName: "Cream",
        occasionTags: ["Work"],
      },
    })
  ).json();
  const archived = await (
    await page.request.post("/api/backend/garments", {
      headers,
      data: {
        name: "Archived evening dress",
        category: "DRESS",
      },
    })
  ).json();
  await page.request.post(`/api/backend/garments/${archived.id}/archive`, {
    headers,
    data: { version: 0 },
  });
  await page.goto("/catalogue");
  await page.getByRole("combobox", { name: "Smart view", exact: true }).selectOption("WORK");
  await expect(page.getByRole("link", { name: /Cream linen work shirt Tops/ })).toBeVisible();
  await page.getByText("More filters", { exact: true }).click();
  await page.getByRole("textbox", { name: "Brand", exact: true }).fill("Studio");
  await page.getByRole("textbox", { name: "Colour", exact: true }).fill("Cream");
  await page.getByRole("button", { name: "Apply filters", exact: true }).click();
  await expect(page).toHaveURL(/brand=Studio/);
  const params = new URL(page.url()).searchParams;
  expect(params.get("view")).toBe("WORK");
  expect(params.get("colour")).toBe("Cream");
  await page.getByRole("searchbox", { name: "Find a piece", exact: true }).fill("linen");
  await page.getByRole("searchbox", { name: "Find a piece", exact: true }).press("Enter");
  await expect(page).toHaveURL(/q=linen/);
  expect(new URL(page.url()).searchParams.get("brand")).toBe("Studio");
  await page.reload();
  await expect(page.getByRole("combobox", { name: "Smart view", exact: true })).toHaveValue("WORK");
  await expect(page.getByRole("link", { name: /Cream linen work shirt Tops/ })).toBeVisible();
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.goto("/catalogue?view=ARCHIVED");
  await expect(page.getByRole("link", { name: /Archived evening dress Dresses/ })).toBeVisible();
  await expect(page.getByRole("link", { name: /Cream linen work shirt Tops/ })).toHaveCount(0);
  const collection = await (
    await page.request.post("/api/backend/collections", {
      headers,
      data: {
        name: "Shared tab edit",
        type: "MANUAL",
        garmentIds: [garment.id],
      },
    })
  ).json();
  await page.goto(`/collections/${collection.id}`);
  await expect(page.getByRole("textbox", { name: "Collection name", exact: true })).toHaveValue(
    "Shared tab edit",
  );
  const edit = await page.request.patch(`/api/backend/collections/${collection.id}`, {
    headers,
    data: { version: 0, name: "Changed in another tab" },
  });
  expect(edit.status()).toBe(200);
  await page
    .getByRole("textbox", { name: "Collection name", exact: true })
    .fill("Stale local edit");
  await page.getByRole("button", { name: "Save collection", exact: true }).click();
  await expect(
    page.getByText("This collection changed. Reload the current version before saving again.", {
      exact: true,
    }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Reload current collection", exact: true }).click();
  await expect(page.getByRole("textbox", { name: "Collection name", exact: true })).toHaveValue(
    "Changed in another tab",
  );
  await page.getByRole("textbox", { name: "Collection name", exact: true }).fill("Renamed capsule");
  await page.getByRole("button", { name: "Save collection", exact: true }).click();
  await expect(page.getByText("Collection saved.", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Delete collection", exact: true }).click();
  await page.getByRole("button", { name: "Delete collection permanently", exact: true }).click();
  await expect(page).toHaveURL(/\/collections$/);
  expect((await page.request.get(`/api/backend/collections/${collection.id}`)).status()).toBe(404);
  expect((await page.request.get(`/api/backend/garments/${garment.id}`)).status()).toBe(200);
});
