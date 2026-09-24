import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import type { CostInsights, ForgottenInsights, UsageInsights } from "./types";
import { WardrobeInsights } from "./wardrobe-insights";

const navigation = vi.hoisted(() => ({ params: new URLSearchParams(), push: vi.fn() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => navigation.params,
  useRouter: () => ({ push: navigation.push }),
}));
vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const shirt: Garment = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "Olive shirt",
  category: "TOP",
  status: "AVAILABLE",
  processingStatus: "READY",
  wearCount: 5,
  lastWornAt: "2026-01-01",
  purchasePrice: 50,
  purchaseCurrency: "GBP",
  costPerWear: 10,
  version: 0,
  createdAt: "2025-01-01T00:00:00Z",
  updatedAt: "2025-01-01T00:00:00Z",
};
const unused: Garment = {
  ...shirt,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Unworn linen shirt",
  wearCount: 0,
  lastWornAt: null,
  purchasePrice: 75,
  costPerWear: 75,
};
const gift: Garment = {
  ...shirt,
  id: "00000000-0000-4000-8000-000000000003",
  name: "Gifted shirt",
  purchasePrice: 0,
  costPerWear: 0,
};
const dollars: Garment = {
  ...shirt,
  id: "00000000-0000-4000-8000-000000000004",
  name: "Dollar shirt",
  purchasePrice: 200,
  costPerWear: 40,
  purchaseCurrency: "USD",
};
const usage: UsageInsights = {
  asOf: "2026-10-01",
  includesArchived: false,
  garmentCount: 4,
  garmentWearOccurrences: 15,
  neverWornCount: 1,
  unknownLastWornCount: 1,
  categories: { TOP: 4 },
  availability: { AVAILABLE: 4 },
  mostWorn: [shirt],
  leastWorn: [unused],
  neverWorn: [unused],
  explanations: ["Garment wear occurrences count each piece, rather than outfits."],
};
const costs: CostInsights = {
  asOf: "2026-10-01",
  includesArchived: false,
  garmentCount: 6,
  missingPriceCount: 1,
  missingCurrencyCount: 1,
  currencies: [
    {
      currency: "GBP",
      pricedGarmentCount: 3,
      knownPurchaseTotal: 125,
      lowestCostPerWear: [gift, shirt],
      highestCostPerWear: [shirt, gift],
      neverWorn: [unused],
    },
    {
      currency: "USD",
      pricedGarmentCount: 1,
      knownPurchaseTotal: 200,
      lowestCostPerWear: [dollars],
      highestCostPerWear: [dollars],
      neverWorn: [],
    },
  ],
  explanations: ["Prices are grouped by their recorded currency; no conversion is assumed."],
};
const forgotten: ForgottenInsights = {
  asOf: "2026-10-01",
  season: null,
  minimumOwnershipDays: 120,
  minimumDaysSinceWear: 45,
  garmentCount: 4,
  eligibleCount: 2,
  unknownLastWornCount: 1,
  items: [
    {
      garment: unused,
      ownedSince: "2025-01-01",
      ownershipBasis: "ADDED_DATE",
      ownershipDays: 638,
      daysSinceLastWear: null,
      seasonCompatibility: "NOT_REQUESTED",
      reasons: [
        "Added 638 days ago; purchase date is unknown.",
        "No wear has been recorded.",
        "No season selected; season compatibility was not inferred.",
      ],
    },
  ],
  explanations: ["Archived pieces are excluded; unavailable pieces receive lower priority."],
};

function setup(query = "") {
  navigation.params = new URLSearchParams(query);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = () => (
    <QueryClientProvider client={client}>
      <WardrobeInsights />
    </QueryClientProvider>
  );
  const rendered = render(tree());
  return {
    client,
    navigate: (next: string) => {
      navigation.params = new URLSearchParams(next);
      rendered.rerender(tree());
    },
  };
}
beforeEach(() => {
  navigation.push.mockReset();
  vi.mocked(api)
    .mockReset()
    .mockImplementation(async (url) => {
      if (url.startsWith("insights/cost-per-wear")) return costs as never;
      if (url.startsWith("insights/usage")) return usage as never;
      return forgotten as never;
    });
});

it("shows configured eligibility, missing-data explanations and actual garment links", async () => {
  const user = userEvent.setup();
  setup();
  const link = await screen.findByRole("link", { name: /Unworn linen shirt/ });
  expect(link).toHaveAttribute("href", `/garments/${unused.id}`);
  expect(screen.getByRole("combobox", { name: "Your current season" })).toHaveValue("");
  expect(screen.getByText(/owned for at least 120 days/i)).toHaveTextContent("last 45 days");
  expect(
    screen.getByText("Showing 1 of 2 eligible pieces, ranked from 4 reviewed pieces."),
  ).toBeVisible();
  expect(screen.getByText(/1 worn piece is excluded/)).toBeVisible();
  const reasonToggle = screen.getByText("Why this appeared");
  await user.click(reasonToggle);
  expect(screen.getByText("Added 638 days ago; purchase date is unknown.")).toBeVisible();
  expect(
    screen.getByText("No season selected; season compatibility was not inferred."),
  ).toBeVisible();
  expect(vi.mocked(api)).toHaveBeenCalledWith(
    "insights/forgotten?limit=12",
    expect.objectContaining({ signal: expect.any(AbortSignal) }),
  );
});

it("preserves season, view, archive and list-size controls in navigation and restores them", async () => {
  const user = userEvent.setup();
  const view = setup();
  await screen.findByRole("link", { name: /Unworn linen shirt/ });
  await user.selectOptions(screen.getByRole("combobox", { name: "Your current season" }), "WINTER");
  expect(navigation.push).toHaveBeenLastCalledWith("/discover/insights?season=WINTER", {
    scroll: false,
  });
  view.navigate("season=WINTER");
  await user.click(screen.getByRole("button", { name: "Wear history" }));
  expect(navigation.push).toHaveBeenLastCalledWith("/discover/insights?season=WINTER&view=usage", {
    scroll: false,
  });
  view.navigate("season=WINTER&view=usage");
  await screen.findByRole("region", { name: "Most worn" });
  await user.click(screen.getByRole("checkbox", { name: "Include archived pieces" }));
  expect(navigation.push).toHaveBeenLastCalledWith(
    "/discover/insights?season=WINTER&view=usage&archived=true",
    { scroll: false },
  );
  view.navigate("season=WINTER&view=usage&archived=true");
  await user.selectOptions(screen.getByRole("combobox", { name: "Pieces per list" }), "24");
  expect(navigation.push).toHaveBeenLastCalledWith(
    "/discover/insights?season=WINTER&view=usage&archived=true&limit=24",
    { scroll: false },
  );
  view.navigate("season=WINTER&view=usage&archived=true&limit=24");
  await waitFor(() =>
    expect(
      vi
        .mocked(api)
        .mock.calls.some(([url]) => url === "insights/usage?limit=24&includeArchived=true"),
    ).toBe(true),
  );
  expect(screen.getByRole("checkbox", { name: "Include archived pieces" })).toBeChecked();
  view.navigate("season=WINTER");
  await screen.findByText("Why this appeared");
  expect(screen.getByRole("combobox", { name: "Your current season" })).toHaveValue("WINTER");
  expect(screen.getByRole("combobox", { name: "Pieces per list" })).toHaveValue("12");
  expect(
    screen.queryByRole("checkbox", { name: "Include archived pieces" }),
  ).not.toBeInTheDocument();
  expect(
    vi.mocked(api).mock.calls.some(([url]) => url === "insights/forgotten?limit=12&season=WINTER"),
  ).toBe(true);
});

it("compares within a currency, retains zero costs, and separates never-worn purchase prices", async () => {
  const user = userEvent.setup();
  const view = setup("view=costs");
  const low = await screen.findByRole("region", { name: "Lowest cost per wear" });
  expect(within(low).getByRole("link", { name: /Gifted shirt/ })).toHaveTextContent(
    "£0.00 per recorded wear",
  );
  expect(within(low).queryByRole("link", { name: /Unworn linen/ })).not.toBeInTheDocument();
  expect(
    within(screen.getByRole("region", { name: "Never worn purchases" })).getByRole("link", {
      name: /Unworn linen/,
    }),
  ).toHaveTextContent("£75.00 purchase price · never worn");
  expect(screen.getByText(/1 piece has no purchase price/)).toHaveTextContent(
    "1 priced piece has no recorded currency",
  );
  expect(screen.getByText("£125.00")).toBeVisible();
  expect(screen.queryByText("£325.00")).not.toBeInTheDocument();
  expect(screen.queryByRole("link", { name: /Dollar shirt/ })).not.toBeInTheDocument();
  await user.selectOptions(screen.getByRole("combobox", { name: "Recorded currency" }), "USD");
  expect(navigation.push).toHaveBeenLastCalledWith("/discover/insights?view=costs&currency=USD", {
    scroll: false,
  });
  view.navigate("view=costs&currency=USD");
  expect(screen.getByRole("combobox", { name: "Recorded currency" })).toHaveValue("USD");
  expect(screen.getByText("US$200.00")).toBeVisible();
  expect(screen.getAllByRole("link", { name: /Dollar shirt/ })).toHaveLength(2);
  expect(screen.queryByRole("link", { name: /Olive shirt/ })).not.toBeInTheDocument();
  await user.click(screen.getByText("How these insights are calculated"));
  expect(screen.getByText(/no conversion is assumed/)).toBeVisible();
});

it("shows totals independently from limited rows and identifies unknown last-worn dates", async () => {
  setup("view=usage");
  await screen.findByRole("region", { name: "Most worn" });
  expect(screen.getByText("Garment wear occurrences").nextElementSibling).toHaveTextContent("15");
  expect(screen.getByText("Reviewed pieces").nextElementSibling).toHaveTextContent("4");
  expect(screen.getByRole("region", { name: "Pieces by category" })).toHaveTextContent("Tops4");
  expect(screen.getByText(/1 worn piece has no known last-worn date/)).toBeVisible();
  expect(
    within(screen.getByRole("region", { name: "Most worn" })).getAllByRole("link"),
  ).toHaveLength(1);
  expect(
    within(screen.getByRole("region", { name: "Never worn" })).getByRole("link", {
      name: /Unworn linen/,
    }),
  ).toHaveAttribute("href", `/garments/${unused.id}`);
});

it("offers retry and refreshes when wear or garment mutations invalidate insight queries", async () => {
  const user = userEvent.setup();
  vi.mocked(api).mockRejectedValueOnce(
    new ApiError(503, "History is temporarily unavailable.", "UNAVAILABLE"),
  );
  const view = setup("view=usage");
  expect(await screen.findByRole("alert")).toHaveTextContent("History is temporarily unavailable.");
  await user.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByRole("region", { name: "Most worn" });
  vi.mocked(api).mockResolvedValue({
    ...usage,
    garmentWearOccurrences: 16,
    neverWornCount: 0,
    neverWorn: [],
  });
  await act(async () => {
    await view.client.invalidateQueries({ queryKey: ["search"] });
  });
  await waitFor(() =>
    expect(screen.getByText("Garment wear occurrences").nextElementSibling).toHaveTextContent("16"),
  );
  expect(
    within(screen.getByRole("region", { name: "Never worn" })).getByText(/Every reviewed piece/),
  ).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Refresh insights" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Refresh insights" })).toBeEnabled(),
  );
});

it("handles empty wardrobes and missing prices without inventing money or favourites", async () => {
  vi.mocked(api).mockResolvedValueOnce({ ...costs, currencies: [], missingPriceCount: 6 });
  const view = setup("view=costs");
  expect(await screen.findByText("A little more context is needed.")).toBeVisible();
  expect(screen.queryByRole("combobox", { name: "Recorded currency" })).not.toBeInTheDocument();
  expect(screen.queryByText(/£0.00/)).not.toBeInTheDocument();
  vi.mocked(api).mockResolvedValueOnce({
    ...forgotten,
    items: [],
    eligibleCount: 0,
    garmentCount: 0,
    unknownLastWornCount: 0,
  });
  view.navigate("");
  expect(await screen.findByText("No wardrobe history yet.")).toBeVisible();
  vi.mocked(api).mockResolvedValueOnce({
    ...usage,
    garmentCount: 0,
    garmentWearOccurrences: 0,
    unknownLastWornCount: 0,
    neverWornCount: 0,
    mostWorn: [],
    leastWorn: [],
    neverWorn: [],
    categories: {},
  });
  view.navigate("view=usage");
  expect(await screen.findByText(/No wear has been recorded yet/)).toBeVisible();
  expect(screen.queryByRole("link", { name: /Olive shirt/ })).not.toBeInTheDocument();
});

it("normalises unsupported URL controls before making API requests", async () => {
  setup("view=invalid&season=constructor&limit=5000&archived=true");
  await screen.findByText("Why this appeared");
  expect(screen.getByRole("button", { name: "Forgotten pieces" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  expect(screen.getByRole("combobox", { name: "Your current season" })).toHaveValue("");
  expect(screen.getByRole("combobox", { name: "Pieces per list" })).toHaveValue("12");
  expect(vi.mocked(api)).toHaveBeenCalledWith("insights/forgotten?limit=12", expect.anything());
});

it("distinguishes a wardrobe with no eligible forgotten pieces from missing wardrobe history", async () => {
  vi.mocked(api).mockResolvedValueOnce({
    ...forgotten,
    items: [],
    eligibleCount: 0,
    unknownLastWornCount: 0,
  });
  setup();
  expect(await screen.findByText("Nothing forgotten in this view.")).toBeVisible();
  expect(
    screen.getByText("Showing 0 of 0 eligible pieces, ranked from 4 reviewed pieces."),
  ).toBeVisible();
  expect(screen.queryByText("No wardrobe history yet.")).not.toBeInTheDocument();
});
