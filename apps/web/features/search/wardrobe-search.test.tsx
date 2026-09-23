import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { ApiError, api } from "@/lib/api";
import { SearchPhotoProvider } from "./search-photos";
import { WardrobeSearch } from "./wardrobe-search";
import type { SearchPage } from "./types";

const navigation = vi.hoisted(() => ({ params: new URLSearchParams(), push: vi.fn() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => navigation.params,
  useRouter: () => ({ push: navigation.push }),
}));
vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));

const garment = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "Olive cotton shirt",
  category: "TOP" as const,
  status: "AVAILABLE" as const,
  processingStatus: "READY",
  wearCount: 0,
  lastWornAt: null,
  costPerWear: null,
  version: 1,
  createdAt: "2026-01-01",
  updatedAt: "2026-01-01",
};
const result: SearchPage = {
  items: [{ garment, explanations: ["Related to your description"] }],
  nextCursor: null,
  mode: "SEMANTIC",
  appliedConstraints: [],
  eligibleCount: 1,
  indexedCount: 1,
};

function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retryDelay: 0 } } });
  const tree = () => (
    <QueryClientProvider client={client}>
      <SearchPhotoProvider>
        <WardrobeSearch />
      </SearchPhotoProvider>
    </QueryClientProvider>
  );
  const view = render(tree());
  return {
    ...view,
    client,
    navigate: (query: string) => {
      navigation.params = new URLSearchParams(query);
      view.rerender(tree());
    },
  };
}

beforeEach(() => {
  navigation.params = new URLSearchParams();
  navigation.push.mockReset();
  vi.mocked(api).mockReset().mockResolvedValue(result);
});

it("submits descriptions and hard filters together, then restores values from history", async () => {
  const user = userEvent.setup();
  const view = setup();
  await user.type(screen.getByRole("searchbox"), "a relaxed silhouette");
  await user.click(screen.getByText("Choose filters"));
  await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "TOP");
  await user.type(screen.getByRole("textbox", { name: "Brand" }), "Studio");
  await user.type(screen.getByRole("spinbutton", { name: "Maximum wears" }), "2");
  await user.click(screen.getByRole("button", { name: "Search wardrobe" }));
  const submitted = new URL(navigation.push.mock.calls[0][0], "https://closetos.test");
  expect(Object.fromEntries(submitted.searchParams)).toEqual({
    brand: "Studio",
    category: "TOP",
    maxWearCount: "2",
    mode: "HYBRID",
    q: "a relaxed silhouette",
  });
  view.navigate("q=green+shirt&mode=SEMANTIC&brand=Arket&category=TOP");
  await waitFor(() => expect(screen.getByRole("searchbox")).toHaveValue("green shirt"));
  await user.click(screen.getByText("Choose filters"));
  expect(screen.getByRole("textbox", { name: "Brand" })).toHaveValue("Arket");
  view.navigate("");
  await waitFor(() => expect(screen.getByRole("searchbox")).toHaveValue(""));
});

it("explains parsed constraints and removes them without removing explicit filters", async () => {
  navigation.params = new URLSearchParams("q=black+dress&category=DRESS&mode=HYBRID");
  vi.mocked(api).mockResolvedValue({
    ...result,
    mode: "HYBRID",
    appliedConstraints: [
      { field: "colour", operator: "IN", value: ["black"], explanation: "Colour includes black" },
    ],
  });
  const user = userEvent.setup();
  setup();
  expect(await screen.findByText("Colour includes black")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Use description without these filters" }));
  const changed = new URL(navigation.push.mock.calls[0][0], "https://closetos.test");
  expect(changed.searchParams.get("mode")).toBe("SEMANTIC");
  expect(changed.searchParams.get("category")).toBe("DRESS");
  expect(changed.searchParams.get("q")).toBe("black dress");
  await user.click(screen.getByRole("button", { name: "Remove Category filter" }));
  expect(navigation.push.mock.lastCall?.[0]).not.toContain("category=");
});

it("keeps photograph data out of URLs and cache keys, and sends typed filters on every page", async () => {
  const user = userEvent.setup();
  const view = setup();
  await user.click(screen.getByText("Search with a photograph"));
  await user.upload(
    screen.getByLabelText("Reference photograph"),
    new File(["reference photo bytes"], "shirt.png", { type: "image/png" }),
  );
  expect(await screen.findByAltText("Your search reference photograph")).toBeVisible();
  await user.click(screen.getByText("Choose filters"));
  await user.type(screen.getByRole("spinbutton", { name: "Maximum wears" }), "3");
  await user.click(screen.getByRole("button", { name: "Search wardrobe" }));
  const url = new URL(navigation.push.mock.lastCall?.[0], "https://closetos.test");
  expect(url.searchParams.get("photo")).toMatch(/^[\da-f-]{36}$/);
  expect(url.search).not.toMatch(/shirt|base64|reference/);
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (path === "search/image") {
      const body = JSON.parse(String(options?.body));
      return body.filters.cursor
        ? {
            ...result,
            items: [
              { garment: { ...garment, id: "second", name: "Sage linen shirt" }, explanations: [] },
            ],
          }
        : { ...result, nextCursor: "next-page" };
    }
    return result;
  });
  view.navigate(url.searchParams.toString());
  await user.click(await screen.findByRole("button", { name: "Show more pieces" }));
  await screen.findByRole("heading", { name: "Sage linen shirt" });
  const requests = vi.mocked(api).mock.calls.filter(([path]) => path === "search/image");
  expect(requests).toHaveLength(2);
  for (const [, options] of requests) {
    const body = JSON.parse(String(options?.body));
    expect(body.imageBase64).toBe(btoa("reference photo bytes"));
    expect(body.filters.maxWearCount).toBe(3);
    expect(body.filters.limit).toBe(40);
  }
  expect(JSON.parse(String(requests[1][1]?.body)).filters.cursor).toBe("next-page");
  expect(
    JSON.stringify(
      view.client
        .getQueryCache()
        .getAll()
        .map((query) => query.queryKey),
    ),
  ).not.toContain(btoa("reference photo bytes"));
});

it("blocks a missing session photograph instead of silently running a different search", async () => {
  navigation.params = new URLSearchParams("photo=missing&q=shirt&mode=SEMANTIC");
  const user = userEvent.setup();
  setup();
  expect(screen.getByRole("heading", { name: "Select your photograph again." })).toBeVisible();
  expect(api).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Continue without a photograph" }));
  expect(navigation.push.mock.lastCall?.[0]).toBe("/search?mode=SEMANTIC&q=shirt");
});

it("offers keyword recovery when similarity is unavailable and keeps hard filters", async () => {
  navigation.params = new URLSearchParams("q=green+shirt&category=TOP&mode=HYBRID");
  vi.mocked(api).mockRejectedValue(
    new ApiError(503, "Similarity search is temporarily unavailable."),
  );
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole("button", { name: "Search words in details instead" }));
  expect(navigation.push.mock.lastCall?.[0]).toBe(
    "/search?category=TOP&mode=KEYWORD&q=green+shirt",
  );
});

it("rejects oversized photographs before sending them and allows recovery", async () => {
  const user = userEvent.setup();
  setup();
  await user.click(screen.getByText("Search with a photograph"));
  await user.upload(
    screen.getByLabelText("Reference photograph"),
    new File([new Uint8Array(8 * 1024 * 1024 + 1)], "large.png", { type: "image/png" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("Choose a photograph up to 8 MB.");
  expect(screen.getByRole("button", { name: "Search wardrobe" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Remove photograph" }));
  expect(screen.getByRole("button", { name: "Search wardrobe" })).toBeEnabled();
  expect(vi.mocked(api).mock.calls.some(([path]) => path === "search/image")).toBe(false);
});

it("keeps earlier results visible when loading the next page fails", async () => {
  navigation.params = new URLSearchParams("q=shirt&mode=SEMANTIC");
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.includes("cursor=")) throw new ApiError(503, "Please try again.");
    return { ...result, nextCursor: "next-page" };
  });
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole("button", { name: "Show more pieces" }));
  await waitFor(() =>
    expect(screen.getByRole("alert")).toHaveTextContent("More pieces could not be loaded"),
  );
  expect(screen.getByRole("heading", { name: garment.name })).toBeVisible();
});
