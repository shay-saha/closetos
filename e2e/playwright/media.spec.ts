import { createHash, randomUUID } from "node:crypto";
import { readFile } from "node:fs/promises";
import { test, expect } from "@playwright/test";
import { register } from "./register";
import AxeBuilder from "@axe-core/playwright";

test("capture queue survives an interrupted upload and refresh, then opens metadata review", async ({
  page,
}) => {
  test.setTimeout(180_000);
  await register(page);
  await page.goto("/add");
  const accessibility = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(accessibility.violations).toEqual([]);
  await page.route("http://localhost:9000/**", async (route) => {
    if (route.request().method() === "PUT") await route.abort("failed");
    else await route.continue();
  });
  await page.getByRole("button", { name: "Choose photographs", exact: true }).waitFor();
  await expect(page.getByRole("button", { name: "Choose photographs", exact: true })).toBeEnabled();
  await page
    .getByLabel("Photograph files", { exact: true })
    .setInputFiles("e2e/fixtures/shirt.png");
  await expect(page.getByRole("button", { name: "Retry", exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByText("shirt.png", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Retry", exact: true })).toBeVisible();
  await page.unroute("http://localhost:9000/**");
  await page.getByRole("button", { name: "Retry", exact: true }).click();
  await expect(page.getByRole("link", { name: "Review your piece" })).toBeVisible({
    timeout: 120_000,
  });
  await test
    .info()
    .attach("capture-ready", { body: await page.screenshot(), contentType: "image/png" });
  await page.getByRole("link", { name: "Review your piece" }).click();
  await expect(page.getByText("Your photograph is ready.", { exact: false })).toBeVisible();
  await expect(page.locator(".isolated-photo")).toBeVisible();
  await page.getByRole("button", { name: "Edit piece", exact: true }).click();
  await page.getByRole("textbox", { name: "Piece name *", exact: true }).fill("Olive cotton shirt");
  await page.getByRole("combobox", { name: "Category *", exact: true }).selectOption("TOP");
  await page.getByRole("button", { name: "Save changes", exact: true }).click();
  await expect(
    page.getByRole("heading", { name: "Olive cotton shirt", exact: true }),
  ).toBeVisible();
  await expect(page.getByText("Your photograph is ready.", { exact: false })).not.toBeVisible();
  const garmentId = new URL(page.url()).pathname.split("/").at(-1);
  await expect
    .poll(
      async () => {
        const response = await page.request.get(`/api/backend/garments/${garmentId}/embedding`);
        expect(response.status()).toBe(200);
        const embedding = await response.json();
        expect(embedding.state, embedding.failureCode).not.toBe("FAILED");
        return embedding.state;
      },
      { timeout: 60_000, intervals: [500, 1000] },
    )
    .toBe("READY");
  await page.goto("/catalogue");
  await expect(
    page.getByRole("heading", { name: "Olive cotton shirt", exact: true }),
  ).toBeVisible();
  await expect(page.locator(".isolated-photo")).toBeVisible();
});

test("signed direct upload, real isolation, private retrieval, and media cleanup", async ({
  page,
}) => {
  test.setTimeout(180_000);
  await register(page);
  const photograph = await readFile("e2e/fixtures/shirt.png");
  const request = {
    filename: "shirt.png",
    mimeType: "image/png",
    size: photograph.length,
    checksumSha256: createHash("sha256").update(photograph).digest("base64"),
    imageRole: "FRONT",
  };
  const headers = { Origin: "http://localhost:3000", "Idempotency-Key": randomUUID() };
  const response = await page.request.post("/api/backend/garments/uploads", {
    headers,
    data: request,
  });
  expect(response.status()).toBe(201);
  const reserved = await response.json();
  const replay = await page.request.post("/api/backend/garments/uploads", {
    headers,
    data: request,
  });
  expect((await replay.json()).imageId).toBe(reserved.imageId);

  const uploaded = await page.evaluate(
    async ({ upload, bytes }) => {
      const result = await fetch(upload.url, {
        method: upload.method,
        headers: upload.headers,
        body: new Uint8Array(bytes),
      });
      return { status: result.status, detail: result.ok ? "" : await result.text() };
    },
    { upload: reserved.upload, bytes: [...photograph] },
  );
  expect(uploaded).toEqual({ status: 200, detail: "" });

  await expect
    .poll(
      async () => {
        const progress = await page.request.get(`/api/backend/processing/${reserved.imageId}`);
        expect(progress.status()).toBe(200);
        const body = await progress.json();
        expect(body.state, body.failureDetail).not.toBe("FAILED");
        return body.state;
      },
      { timeout: 120_000, intervals: [1000, 2000] },
    )
    .toBe("READY_FOR_REVIEW");

  const detail = await page.request.get(`/api/backend/garments/${reserved.garmentId}`);
  const garment = await detail.json();
  expect(garment.name).toBe("New piece");
  expect(garment.category).toBe("OTHER");
  expect(garment.assets.imageId).toBe(reserved.imageId);
  const embedding = await page.request.get(`/api/backend/garments/${garment.id}/embedding`);
  expect((await embedding.json()).state).toBe("WAITING_FOR_REVIEW");
  const image = await page.evaluate(async (url) => {
    const response = await fetch(url);
    const bitmap = await createImageBitmap(await response.blob());
    const canvas = new OffscreenCanvas(bitmap.width, bitmap.height);
    const context = canvas.getContext("2d")!;
    context.drawImage(bitmap, 0, 0);
    return {
      width: bitmap.width,
      height: bitmap.height,
      alpha: context.getImageData(0, 0, 1, 1).data[3],
    };
  }, garment.assets.thumbnailUrl);
  expect(Math.max(image.width, image.height)).toBe(256);
  expect(image.alpha).toBe(0);
  const unsignedUrl = new URL(garment.assets.thumbnailUrl);
  unsignedUrl.search = "";
  expect((await page.request.get(unsignedUrl.toString())).status()).toBe(403);

  const removed = await page.request.delete(
    `/api/backend/garments/${garment.id}?version=${garment.version}`,
    {
      headers: { Origin: "http://localhost:3000" },
    },
  );
  expect(removed.status()).toBe(204);
  await expect
    .poll(async () => (await page.request.get(garment.assets.thumbnailUrl)).status(), {
      timeout: 60_000,
    })
    .toBe(404);
});
