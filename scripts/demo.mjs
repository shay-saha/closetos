import { createHash, randomBytes, randomUUID } from "node:crypto";
import assert from "node:assert/strict";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { chromium } from "@playwright/test";

const root = fileURLToPath(new URL("../", import.meta.url));
const origin = new URL(process.env.DEMO_APP_URL ?? "http://localhost:3000").origin;
if (!["localhost", "127.0.0.1", "[::1]"].includes(new URL(origin).hostname))
  throw new Error("Demo accounts are for a local development instance.");
const credentials = `${root}.demo-accounts.json`;
const mode = process.argv[2] ?? "seed";
if (!["seed", "screenshots", "verify"].includes(mode))
  throw new Error("Use seed, screenshots or verify.");

const pieces = [
  ["cream-knit", "Cream merino knit", "TOP", "Cream", "#e8dfcf", "Merino wool", 85],
  ["navy-coat", "Navy wool coat", "OUTERWEAR", "Navy", "#202b3c", "Wool", 180],
  ["indigo-jeans", "Straight-leg indigo denim", "BOTTOM", "Indigo", "#35516c", "Denim", 75],
  ["white-sneakers", "Everyday white sneakers", "SHOES", "White", "#f0ece4", "Leather", 95],
  ["linen-shirt", "White linen shirt", "TOP", "White", "#f2efe8", "Linen", 65],
  ["olive-overshirt", "Olive cotton overshirt", "TOP", "Olive", "#657257", "Cotton twill", 80],
  ["sand-chinos", "Sand tapered chinos", "BOTTOM", "Sand", "#c7b58f", "Cotton", 60],
  ["burgundy-loafers", "Burgundy penny loafers", "SHOES", "Burgundy", "#6a3440", "Leather", 120],
];

let state;
try {
  state = JSON.parse(await readFile(credentials, "utf8"));
  if (state.origin !== origin)
    throw new Error("The saved demo accounts belong to a different app origin.");
} catch (error) {
  if (error.code !== "ENOENT") throw error;
  if (mode !== "seed") throw new Error("Run make demo first.");
  const suffix = randomBytes(3).toString("hex");
  state = {
    origin,
    accounts: [
      { firstName: "Alex", lastName: "Morgan", count: 8 },
      { firstName: "Sam", lastName: "Rivera", count: 6 },
    ].map((person) => ({
      ...person,
      email: `${person.firstName.toLowerCase()}.demo.${suffix}@closetos.example.test`,
      password: `Closet-${randomBytes(12).toString("base64url")}!`,
      registered: false,
      garments: {},
      outfits: {},
      collections: {},
      wear: {},
    })),
  };
  await save();
}

async function save() {
  await writeFile(credentials, JSON.stringify(state, null, 2) + "\n", { mode: 0o600 });
}

async function api(page, method, path, data, key) {
  const response = await page.request.fetch(`${origin}/api/backend${path}`, {
    method,
    headers: { Origin: origin, ...(key ? { "Idempotency-Key": key } : {}) },
    ...(data === undefined ? {} : { data }),
    timeout: 120_000,
  });
  if (!response.ok())
    throw new Error(`${method} ${path}: HTTP ${response.status()} ${await response.text()}`);
  return response.status() === 204 ? undefined : response.json();
}

async function session(browser, account) {
  const context = await browser.newContext({
    viewport: { width: 1440, height: 1000 },
    deviceScaleFactor: 1,
  });
  const page = await context.newPage();
  await page.goto(`${origin}/signin`);
  await page.getByRole("button", { name: "Open your wardrobe" }).click();
  if (!account.registered) {
    await page.getByRole("link", { name: "Register", exact: true }).click();
    await page.getByRole("textbox", { name: "Email", exact: false }).fill(account.email);
    await page.locator("#password").fill(account.password);
    await page.locator("#password-confirm").fill(account.password);
    await page.getByRole("textbox", { name: "First name" }).fill(account.firstName);
    await page.getByRole("textbox", { name: "Last name" }).fill(account.lastName);
    await page.getByRole("button", { name: "Register", exact: true }).click();
  } else {
    await page.locator("#username").fill(account.email);
    await page.locator("#password").fill(account.password);
    await page.locator("#kc-login").click();
  }
  await page.waitForURL(`${origin}/wardrobe`, { timeout: 30_000 });
  account.registered = true;
  await save();
  return { context, page };
}

async function prepared(page, path, accepted) {
  const deadline = Date.now() + 180_000;
  while (Date.now() < deadline) {
    const progress = await api(page, "GET", path);
    if (accepted.includes(progress.state)) return progress;
    if (progress.state === "FAILED")
      throw new Error(
        `${path}: ${progress.failureCode ?? progress.failureDetail ?? "processing failed"}`,
      );
    await new Promise((resolve) => setTimeout(resolve, 1000));
  }
  throw new Error(`${path} did not finish within three minutes.`);
}

function date(daysAgo) {
  const value = new Date();
  value.setUTCDate(value.getUTCDate() - daysAgo);
  return value.toISOString().slice(0, 10);
}

async function seed(page, account) {
  const profile = await api(page, "GET", "/me");
  await api(page, "PATCH", "/me", {
    displayName: `${account.firstName} ${account.lastName}`,
    locale: "en-GB",
    currency: "GBP",
    timezone: "Europe/London",
    deleteOriginalAfterIsolation: false,
    version: profile.version,
  });
  for (const [slug, name, category, colour, hex, material, price] of pieces.slice(
    0,
    account.count,
  )) {
    const item = (account.garments[slug] ??= { uploadKey: randomUUID() });
    await save();
    const bytes = await readFile(`${root}assets/demo/${slug}.png`);
    if (!item.id) {
      const reserved = await api(
        page,
        "POST",
        "/garments/uploads",
        {
          filename: `${slug}.png`,
          mimeType: "image/png",
          size: bytes.length,
          checksumSha256: createHash("sha256").update(bytes).digest("base64"),
          imageRole: "FRONT",
        },
        item.uploadKey,
      );
      item.id = reserved.garmentId;
      item.imageId = reserved.imageId;
      await save();
    }
    if (!item.reviewed) {
      const reservation = await api(
        page,
        "POST",
        "/garments/uploads",
        {
          filename: `${slug}.png`,
          mimeType: "image/png",
          size: bytes.length,
          checksumSha256: createHash("sha256").update(bytes).digest("base64"),
          imageRole: "FRONT",
        },
        item.uploadKey,
      );
      const uploaded = await page.request.fetch(reservation.upload.url, {
        method: reservation.upload.method,
        headers: reservation.upload.headers,
        data: bytes,
      });
      if (![200, 412].includes(uploaded.status()))
        throw new Error(`${slug}: upload HTTP ${uploaded.status()}`);
      await prepared(page, `/processing/${item.imageId}`, ["READY_FOR_REVIEW", "COMPLETE"]);
      const garment = await api(page, "GET", `/garments/${item.id}`);
      await api(page, "PATCH", `/garments/${item.id}`, {
        version: garment.version,
        name,
        category,
        primaryColourName: colour,
        primaryColourHex: hex,
        material,
        sizeLabel: category === "SHOES" ? "UK 8" : "M",
        brand: "Everyday Goods",
        formality: "Casual",
        seasonTags: ["AUTUMN", "SPRING"],
        occasionTags: ["everyday", "travel", "work"],
        styleTags: ["Minimal", "Classic", "mild-weather"],
        purchasePrice: price,
        purchaseCurrency: "GBP",
        purchaseDate: date(180),
        notes: "Fictional piece for the local demo wardrobe.",
      });
      await prepared(page, `/garments/${item.id}/embedding`, ["READY"]);
      item.reviewed = true;
      await save();
    }
    const current = await api(page, "GET", `/garments/${item.id}`);
    if (!current.styleTags.includes("mild-weather")) {
      await api(page, "PATCH", `/garments/${item.id}`, {
        version: current.version,
        styleTags: [...current.styleTags, "mild-weather"],
      });
      await prepared(page, `/garments/${item.id}/embedding`, ["READY"]);
    }
    console.log(`${account.firstName}: ${name} ready`);
  }
  const arrangements = [
    ["weekend", "A slow Sunday", ["cream-knit", "indigo-jeans", "white-sneakers"]],
    ["layers", "City layers", ["olive-overshirt", "indigo-jeans", "white-sneakers", "navy-coat"]],
    [
      "lighter",
      "Coffee, then somewhere",
      account.count === 8
        ? ["linen-shirt", "sand-chinos", "burgundy-loafers"]
        : ["linen-shirt", "indigo-jeans", "white-sneakers"],
    ],
  ];
  for (const [slug, name, included] of arrangements) {
    if (account.outfits[slug]) continue;
    const positions = [
      [32, 29, 1.3],
      [67, 56, 1.2],
      [29, 80, 1.2],
      [73, 24, 1.3],
    ];
    const outfit = await api(page, "POST", "/outfits", {
      name,
      occasion: "Everyday",
      season: "Autumn",
      rating: 5,
      tags: ["Favourite", "Capsule"],
      notes: "Easy layers, already in the wardrobe.",
      archived: false,
      items: included.map((id, index) => ({
        garmentId: account.garments[id].id,
        x: positions[index][0],
        y: positions[index][1],
        scale: positions[index][2],
        rotation: 0,
        zIndex: index,
      })),
    });
    account.outfits[slug] = outfit.id;
    await save();
  }
  for (let index = 0; index < 28; index++) {
    const key = `${index}`;
    const entry = (account.wear[key] ??= { key: randomUUID(), done: false });
    if (entry.done) continue;
    await save();
    const outfitId =
      account.outfits[index % 4 === 0 ? "lighter" : index % 3 === 0 ? "layers" : "weekend"];
    const outfit = await api(page, "GET", `/outfits/${outfitId}`);
    await api(
      page,
      "POST",
      `/outfits/${outfitId}/wear`,
      {
        version: outfit.version,
        wornOn: date(1 + index * 3),
        context: index % 3 === 0 ? "Work" : "Weekend",
        notes: index % 3 === 0 ? "A day in the city." : "Walk, coffee, the usual.",
      },
      entry.key,
    );
    entry.done = true;
    await save();
  }
  for (const data of [
    {
      name: "The everyday edit",
      type: "MANUAL",
      garmentIds: ["cream-knit", "indigo-jeans", "white-sneakers", "navy-coat"].map(
        (id) => account.garments[id].id,
      ),
    },
    {
      name: "Light layers",
      type: "SMART",
      queryDefinition: { all: [{ field: "category", operator: "EQ", value: "TOP" }] },
    },
  ]) {
    if (account.collections[data.name]) continue;
    const collection = await api(page, "POST", "/collections", data);
    account.collections[data.name] = collection.id;
    await save();
  }
  if (!account.trip) {
    const trip = await api(page, "POST", "/packing-lists", {
      name: "Four days in Copenhagen",
      locationText: "Copenhagen",
      startDate: date(-10),
      endDate: date(-13),
      constraints: {
        maximumGarments: 6,
        weather: {
          minimumTemperatureC: 10,
          maximumTemperatureC: 16,
          assumptions: ["MILD"],
          season: "AUTUMN",
        },
        occasions: [
          { name: "Exploring the city", occasionTag: "travel", formality: "Casual", days: 4 },
        ],
        formalEvents: [],
        laundryEveryDays: 0,
        maximumWearsBetweenLaundry: 4,
        requiredGarments: [account.garments["cream-knit"].id],
        excludedGarments: [],
      },
    });
    account.trip = trip.id;
    await save();
  }
  const trip = await api(page, "GET", `/packing-lists/${account.trip}`);
  const planned =
    !trip.plan || trip.stale || trip.plan.status === "INFEASIBLE"
      ? await api(page, "POST", `/packing-lists/${account.trip}/optimise`, {
          version: trip.version,
        })
      : trip;
  if (!["OPTIMAL", "FEASIBLE"].includes(planned.plan?.status))
    throw new Error(`${account.firstName}: the demo trip has no valid capsule`);
  console.log(`${account.firstName}: wardrobe, outfits, wear history, collections and trip saved`);
}

async function capture(page, route, filename, prepare, fullPage = true) {
  await page.goto(`${origin}${route}`);
  await page.locator("main").waitFor();
  if (prepare) await prepare();
  await page.waitForLoadState("networkidle");
  // Load lazy garment images below the fold before capturing the full page.
  await page.evaluate(async () => {
    for (const picture of document.images) picture.loading = "eager";
  });
  await page.evaluate(async () => {
    await document.fonts.ready;
    await Promise.all(
      [...document.images].map((image) =>
        image.complete
          ? undefined
          : new Promise((resolve) => {
              image.addEventListener("load", resolve, { once: true });
              image.addEventListener("error", resolve, { once: true });
            }),
      ),
    );
    await Promise.all([...document.images].map((image) => image.decode()));
    // Scrolling also paints images that the browser defers outside the viewport.
    for (let y = 0; y < document.documentElement.scrollHeight; y += innerHeight) {
      scrollTo(0, y);
      await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
    }
    scrollTo(0, 0);
    await new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve)));
  });
  const failedImages = await page
    .locator("img")
    .evaluateAll(
      (images) => images.filter((image) => !image.complete || image.naturalWidth === 0).length,
    );
  if (failedImages) throw new Error(`${filename}: ${failedImages} garment images failed to load`);
  await page.screenshot({
    path: `${root}assets/screenshots/${filename}.png`,
    fullPage,
    animations: "disabled",
    // The Next.js development toolbar is outside the product interface.
    style: "nextjs-portal { display: none !important; }",
  });
  console.log(`Saved ${filename}.png`);
}

async function verify(page, account) {
  const catalogue = await api(page, "GET", "/garments");
  assert.equal(catalogue.items.length, account.count);
  const ownedIds = new Set(Object.values(account.garments).map((garment) => garment.id));
  for (const piece of catalogue.items) {
    assert(ownedIds.has(piece.id));
    assert.equal(piece.processingStatus, "READY");
    assert(piece.assets?.cardUrl);
    assert(piece.wearCount > 0);
  }
  for (const id of Object.values(account.outfits)) {
    const outfit = await api(page, "GET", `/outfits/${id}`);
    assert(outfit.items.length >= 3);
    assert(outfit.items.every((item) => ownedIds.has(item.garmentId)));
  }
  for (const id of Object.values(account.collections))
    assert.equal((await api(page, "GET", `/collections/${id}`)).id, id);
  const wear = await api(page, "GET", "/wear-events?limit=100");
  assert.equal(wear.items.length, 28);
  assert(wear.items.every((entry) => entry.garmentIds.every((id) => ownedIds.has(id))));
  const trip = await api(page, "GET", `/packing-lists/${account.trip}`);
  assert(["OPTIMAL", "FEASIBLE"].includes(trip.plan?.status));
  assert.equal(trip.plan.outfits.length, 4);
  assert(trip.plan.selectedGarments.includes(account.garments["cream-knit"].id));
  const other = state.accounts.find((person) => person.email !== account.email);
  for (const path of [
    `/garments/${other.garments["cream-knit"].id}`,
    `/outfits/${other.outfits.weekend}`,
    `/packing-lists/${other.trip}`,
  ]) {
    const response = await page.request.get(`${origin}/api/backend${path}`);
    assert.equal(response.status(), 404, `${account.firstName} must not access the other account`);
  }
  console.log(`${account.firstName}: saved data, processed photos and account isolation verified`);
}

const browser = await chromium.launch({ headless: true });
try {
  await mkdir(`${root}assets/screenshots`, { recursive: true });
  for (const [index, account] of state.accounts.entries()) {
    const { context, page } = await session(browser, account);
    try {
      if (mode === "seed") await seed(page, account);
      else if (mode === "screenshots" && index === 0) {
        await capture(page, "/catalogue", "catalogue", () =>
          page.getByRole("heading", { name: "Cream merino knit", exact: true }).waitFor(),
        );
        await capture(page, "/wardrobe", "wardrobe", async () => {
          await page.getByRole("listbox").waitFor();
          await page.getByRole("button", { name: "Next piece", exact: true }).click();
          await page.getByText("2 of 3", { exact: true }).waitFor();
        });
        await page.setViewportSize({ width: 1440, height: 1260 });
        await capture(
          page,
          `/outfits/${account.outfits.weekend}`,
          "outfit-studio",
          async () => {
            await page.getByRole("textbox", { name: "Outfit name", exact: true }).waitFor();
            await page.getByText("Loading pieces…", { exact: true }).waitFor({ state: "hidden" });
            await page.locator(".canvas-piece img").first().waitFor();
          },
          false,
        );
        await page.setViewportSize({ width: 1440, height: 1000 });
        await capture(page, "/discover/insights", "wear-insights", async () => {
          await page.getByRole("button", { name: "Cost per wear", exact: true }).click();
          await page.getByText("Cream merino knit", { exact: true }).first().waitFor();
        });
        await capture(page, `/packing/${account.trip}`, "packing", () =>
          page.getByRole("heading", { name: /^(Optimised|Valid) capsule$/ }).waitFor(),
        );
        await page.setViewportSize({ width: 390, height: 844 });
        await capture(page, "/catalogue", "catalogue-mobile", () =>
          page.getByRole("heading", { name: "Cream merino knit", exact: true }).waitFor(),
        );
      } else if (mode === "screenshots") {
        await capture(page, "/catalogue", "sam-wardrobe", () =>
          page.getByRole("heading", { name: "Cream merino knit", exact: true }).waitFor(),
        );
      }
      const user = await api(page, "GET", "/me");
      account.userId = user.id;
      await save();
      if (mode === "verify") await verify(page, account);
    } finally {
      await context.close();
    }
  }
  console.log(`Demo credentials are saved privately in ${credentials}`);
} finally {
  await browser.close();
}
