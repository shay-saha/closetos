import { appOrigin } from "./environment";
import { test, expect, type Page } from "@playwright/test";
import { register } from "./register";

async function waitForEmbedding(page: Page, garment: string) {
  await expect
    .poll(
      async () => {
        const response = await page.request.get(`/api/backend/garments/${garment}/embedding`);
        expect(response.status()).toBe(200);
        const status = await response.json();
        expect(status.garmentId).toBe(garment);
        expect(status.state, status.failureCode).not.toBe("FAILED");
        return status.state;
      },
      { timeout: 60_000, intervals: [500, 1000] },
    )
    .toBe("READY");
}

test("canonical edits regenerate embeddings through the real worker and stay private", async ({
  page,
  browser,
}) => {
  test.setTimeout(120_000);
  await register(page);
  const headers = { Origin: appOrigin };
  const created = await page.request.post("/api/backend/garments", {
    headers,
    data: { name: "Olive cotton shirt", category: "TOP", primaryColourName: "Olive" },
  });
  expect(created.status()).toBe(201);
  const garment = await created.json();
  await waitForEmbedding(page, garment.id);
  const updated = await page.request.patch(`/api/backend/garments/${garment.id}`, {
    headers,
    data: {
      name: "Cream wool jumper",
      primaryColourName: "Cream",
      material: "Wool",
      version: garment.version,
    },
  });
  expect(updated.status()).toBe(200);
  await waitForEmbedding(page, garment.id);

  const stranger = await browser.newContext({ baseURL: appOrigin });
  try {
    const otherPage = await stranger.newPage();
    const anonymous = await otherPage.request.get(`/api/backend/garments/${garment.id}/embedding`);
    expect(anonymous.status()).toBe(401);
    await register(otherPage);
    const forbidden = await otherPage.request.get(`/api/backend/garments/${garment.id}/embedding`);
    expect(forbidden.status()).toBe(404);
  } finally {
    await stranger.close();
  }
});
