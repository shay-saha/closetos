import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";

test("downloads actual wardrobe data and private retained photographs, with accessible allowance recovery", async ({
  page,
}) => {
  test.setTimeout(180_000);
  await register(page);
  const headers = { Origin: "http://localhost:3000" };
  const created = await page.request.post("/api/backend/garments", {
    headers,
    data: {
      name: "My exported coat",
      category: "OUTERWEAR",
      notes: 'Private note: "hello" </script>',
    },
  });
  expect(created.status()).toBe(201);
  const piece = await created.json();
  const bytes = await readFile("e2e/fixtures/shirt.png");
  const reserved = await page.request.post("/api/backend/garments/uploads", {
    headers: { ...headers, "Idempotency-Key": randomUUID() },
    data: {
      filename: "private-shirt.png",
      mimeType: "image/png",
      size: bytes.length,
      checksumSha256: createHash("sha256").update(bytes).digest("base64"),
      imageRole: "FRONT",
    },
  });
  expect(reserved.status()).toBe(201);
  const upload = await reserved.json();
  expect(
    (
      await page.request.put(upload.upload.url, { headers: upload.upload.headers, data: bytes })
    ).status(),
  ).toBe(200);
  await expect
    .poll(
      async () =>
        (await (await page.request.get(`/api/backend/processing/${upload.imageId}`)).json()).state,
      { timeout: 120_000, intervals: [1000, 2000] },
    )
    .toBe("READY_FOR_REVIEW");
  await page.goto("/settings");
  await expect(page.getByRole("button", { name: "Download my data", exact: true })).toBeEnabled();
  expect(
    (
      await new AxeBuilder({ page })
        .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
        .analyze()
    ).violations,
  ).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  const download = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download my data", exact: true }).click();
  const file = await download;
  expect(file.suggestedFilename()).toMatch(/^closetos-data-[0-9-]+\.json$/);
  const path = await file.path();
  const data = JSON.parse(await readFile(path!, "utf8"));
  expect(data).toMatchObject({ schemaVersion: 1, complete: true });
  expect(data.garments.find((garment: { id: string }) => garment.id === piece.id)).toMatchObject({
    name: "My exported coat",
    notes: 'Private note: "hello" </script>',
  });
  const image = data.images.find((image: { id: string }) => image.id === upload.imageId);
  const original = image.files.find((file: { role: string }) => file.role === "original");
  expect(original).toBeDefined();
  expect(Number(new URL(original.url).searchParams.get("X-Amz-Expires"))).toBeLessThanOrEqual(900);
  const originalResponse = await page.request.get(original.url);
  expect(originalResponse.status()).toBe(200);
  expect(await originalResponse.body()).toEqual(bytes);
  expect(originalResponse.headers()["cache-control"]).toContain("no-store");
  const isolated = image.files.find((file: { role: string }) => file.role === "isolated");
  expect((await page.request.get(isolated.url)).status()).toBe(200);
  const unsigned = new URL(original.url);
  unsigned.search = "";
  expect((await page.request.get(unsigned.toString())).status()).toBe(403);
  const second = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download my data", exact: true }).click();
  await second;
  await page.getByRole("button", { name: "Download my data", exact: true }).click();
  await expect(page.getByRole("alert").filter({ hasText: "data download limit" })).toBeVisible();
  await expect(page.getByRole("button", { name: "Download my data", exact: true })).toBeEnabled();
});

test("an interrupted data stream offers a retry instead of a partial download", async ({
  page,
}) => {
  await register(page);
  await page.goto("/settings");
  await page.route("**/api/backend/me/data", (route) =>
    route.fulfill({
      status: 200,
      contentType: "application/json",
      body: '{"schemaVersion":1,"garments":[',
    }),
  );
  const downloads: string[] = [];
  page.on("download", (file) => downloads.push(file.suggestedFilename()));
  await page.getByRole("button", { name: "Download my data", exact: true }).click();
  await expect(
    page.getByRole("alert").filter({ hasText: "Your data download was interrupted" }),
  ).toBeVisible();
  expect(downloads).toEqual([]);
  await expect(page.getByRole("button", { name: "Download my data", exact: true })).toBeEnabled();
});
