import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { register } from "./register";
import { setLocalAdministrator } from "./local-administrator";

test("ordinary accounts cannot rebuild search and an administrator can retry and inspect a durable real-model job", async ({
  page,
}, testInfo) => {
  test.setTimeout(120_000);
  const { email } = await register(page);
  const headers = { Origin: "http://localhost:3000" };
  const wardrobe = await (await page.request.get("/api/backend/wardrobes/current")).json();
  const pieces: { id: string }[] = [];
  for (const name of ["Green cotton shirt", "Blue denim trousers"]) {
    const created = await page.request.post("/api/backend/garments", {
      headers,
      data: { name, category: "TOP" },
    });
    expect(created.status()).toBe(201);
    pieces.push(await created.json());
  }
  for (const piece of pieces)
    await expect
      .poll(
        async () => {
          const response = await page.request.get(`/api/backend/garments/${piece.id}/embedding`);
          expect(response.status()).toBe(200);
          const result = await response.json();
          expect(result.state, result.failureCode).not.toBe("FAILED");
          return result.state;
        },
        { timeout: 60_000, intervals: [500, 1000] },
      )
      .toBe("READY");
  const previous = await Promise.all(
    pieces.map(
      async (piece) =>
        (await (await page.request.get(`/api/backend/garments/${piece.id}/embedding`)).json())
          .updatedAt,
    ),
  );
  await page.getByRole("link", { name: "Settings", exact: true }).click();
  const endpoint = "/api/backend/admin/search/embedding-jobs";
  expect((await page.request.get(endpoint)).status()).toBe(403);
  expect(
    (
      await page.request.post(endpoint, {
        headers: { ...headers, "Idempotency-Key": crypto.randomUUID() },
        data: {},
      })
    ).status(),
  ).toBe(403);
  await expect(page.getByRole("region", { name: "Search administration" })).toHaveCount(0);
  await setLocalAdministrator(email, true);
  try {
    await page.getByRole("button", { name: "Sign out", exact: true }).click();
    await page.getByRole("button", { name: "Open your wardrobe", exact: true }).click();
    await expect(page).toHaveURL(/\/wardrobe/);
    await page.getByRole("link", { name: "Settings", exact: true }).click();
    const controls = page.getByRole("region", { name: "Search administration" });
    await expect(controls).toBeVisible();
    expect(
      (
        await page.request.post(endpoint, {
          headers: { Origin: "https://untrusted.example", "Idempotency-Key": crypto.randomUUID() },
          data: {},
        })
      ).status(),
    ).toBe(403);
    const keys: string[] = [];
    let first = true;
    await page.route(`**${endpoint}`, async (route) => {
      if (route.request().method() !== "POST") {
        await route.continue();
        return;
      }
      keys.push(route.request().headers()["idempotency-key"]);
      if (first) {
        first = false;
        await route.fetch();
        await route.abort("failed");
      } else await route.continue();
    });
    await controls.getByRole("button", { name: "Start search rebuild", exact: true }).click();
    await expect(controls.getByRole("alert")).toContainText(
      "Submit again to retry the same request",
    );
    await controls.getByRole("button", { name: "Start search rebuild", exact: true }).click();
    await expect
      .poll(
        async () => {
          const jobs = (await (await page.request.get(endpoint)).json()) as {
            wardrobeId: string | null;
            state: string;
          }[];
          return jobs.find((job) => job.wardrobeId === wardrobe.id)?.state;
        },
        { timeout: 30_000 },
      )
      .toBe("SUCCEEDED");
    expect(keys).toHaveLength(2);
    expect(keys[0]).toBe(keys[1]);
    const jobs = (await (await page.request.get(endpoint)).json()) as {
      id: string;
      wardrobeId: string | null;
      state: string;
    }[];
    const current = jobs.find((job) => job.wardrobeId === wardrobe.id)!;
    expect(current).toMatchObject({
      state: "SUCCEEDED",
      total: 2,
      succeeded: 2,
      failed: 0,
      skipped: 0,
    });
    const card = controls.getByRole("article", { name: `Rebuild ${current.id}`, exact: true });
    await expect(card.getByRole("progressbar")).toHaveAttribute("value", "2");
    for (let index = 0; index < pieces.length; index++) {
      const updated = await (
        await page.request.get(`/api/backend/garments/${pieces[index].id}/embedding`)
      ).json();
      expect(updated.state).toBe("READY");
      expect(Date.parse(updated.updatedAt)).toBeGreaterThan(Date.parse(previous[index]));
    }
    await page.reload();
    await expect(card).toBeVisible();
    expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(
      true,
    );
    await page.screenshot({
      path: testInfo.outputPath("search-administration.png"),
      fullPage: true,
    });
  } finally {
    await setLocalAdministrator(email, false);
  }
});
