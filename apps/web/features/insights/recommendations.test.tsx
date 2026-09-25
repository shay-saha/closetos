import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import { PotentialDuplicates } from "./potential-duplicates";
import { WorksWith } from "./works-with";
import type { DuplicateInsights, WorksWithInsights } from "./recommendation-types";

const navigation = vi.hoisted(() => ({ params: new URLSearchParams(), push: vi.fn() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => navigation.params,
  useRouter: () => ({ push: navigation.push }),
}));
vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const source: Garment = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "Olive shirt",
  category: "TOP",
  status: "AVAILABLE",
  processingStatus: "READY",
  wearCount: 4,
  lastWornAt: "2026-09-01",
  costPerWear: null,
  version: 0,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};
const second: Garment = {
  ...source,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Sage shirt",
};
const trousers: Garment = {
  ...source,
  id: "00000000-0000-4000-8000-000000000003",
  name: "Linen trousers",
  category: "BOTTOM",
};
const duplicates: DuplicateInsights = {
  reviewedGarmentCount: 4,
  photoGarmentCount: 3,
  missingColourCount: 1,
  candidatePairCount: 2,
  truncated: false,
  embeddings: { state: "PENDING", model: null, eligibleCount: 3, indexedCount: 2 },
  items: [
    {
      first: source,
      second,
      reasons: [
        "Recorded colours are close.",
        "A potential match to review, rather than a confirmed duplicate.",
      ],
    },
  ],
  explanations: [
    "No pieces are automatically changed or labelled.",
    "Only approved photos with current embeddings are compared.",
  ],
};
const works: WorksWithInsights = {
  source,
  context: { season: null, formality: null, weather: null },
  sourceMatchesContext: true,
  eligibleCount: 4,
  embeddings: { state: "UNAVAILABLE", model: null, eligibleCount: 5, indexedCount: 0 },
  items: [
    {
      garment: trousers,
      wornTogetherCount: 3,
      savedTogetherCount: 1,
      semanticAffinityKnown: false,
      reasons: [
        "Adds a complementary garment slot.",
        "Recorded together in 3 wear entries.",
        "Paired in 1 saved outfit.",
        "Semantic affinity is unavailable; this suggestion uses recorded metadata and history.",
      ],
    },
  ],
  explanations: [
    "Requested filters apply to both pieces.",
    "Removed wear entries and archived saved outfits do not contribute to ranking.",
  ],
};
function setup(kind: "duplicates" | "works", query = "") {
  navigation.params = new URLSearchParams(query);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = () => (
    <QueryClientProvider client={client}>
      {kind === "duplicates" ? <PotentialDuplicates /> : <WorksWith id={source.id} />}
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
    .mockImplementation(async (url) =>
      url.startsWith("insights/duplicates") ? (duplicates as never) : (works as never),
    );
});

it("exposes advisory photo pairs with actual garment links and understandable incomplete coverage", async () => {
  const user = userEvent.setup();
  setup("duplicates");
  const pair = await screen.findByRole("region", { name: "Compare Olive shirt and Sage shirt" });
  expect(within(pair).getByRole("link", { name: /Olive shirt/ })).toHaveAttribute(
    "href",
    `/garments/${source.id}`,
  );
  expect(within(pair).getByRole("link", { name: /Sage shirt/ })).toHaveAttribute(
    "href",
    `/garments/${second.id}`,
  );
  expect(screen.getByText(/nothing is changed automatically/)).toBeVisible();
  expect(
    screen.getByText("Showing 1 of 2 potential matches in this comparison shortlist."),
  ).toBeVisible();
  expect(screen.getByText(/2 of 3 pieces are prepared/)).toBeVisible();
  expect(screen.getByText(/1 photo piece has no recorded colour/)).toBeVisible();
  await user.click(within(pair).getByText("Why these pieces?"));
  expect(within(pair).getByText("Recorded colours are close.")).toBeVisible();
  expect(screen.queryByRole("button", { name: /delete|merge|remove/i })).not.toBeInTheDocument();
  expect(vi.mocked(api).mock.calls.every(([, options]) => options?.method == null)).toBe(true);
});

it("keeps category and comparison-size filters in URLs and restores them on back navigation", async () => {
  const user = userEvent.setup();
  const view = setup("duplicates");
  await screen.findByRole("region", { name: "Compare Olive shirt and Sage shirt" });
  await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "TOP");
  expect(navigation.push).toHaveBeenLastCalledWith("/discover/duplicates?category=TOP", {
    scroll: false,
  });
  view.navigate("category=TOP");
  await user.selectOptions(screen.getByRole("combobox", { name: "Pairs per list" }), "24");
  expect(navigation.push).toHaveBeenLastCalledWith("/discover/duplicates?category=TOP&limit=24", {
    scroll: false,
  });
  view.navigate("category=TOP&limit=24");
  await waitFor(() =>
    expect(
      vi
        .mocked(api)
        .mock.calls.some(([url]) => url === "insights/duplicates?limit=24&category=TOP"),
    ).toBe(true),
  );
  view.navigate("");
  expect(screen.getByRole("combobox", { name: "Category" })).toHaveValue("");
  expect(screen.getByRole("combobox", { name: "Pairs per list" })).toHaveValue("12");
});

it.each(["UNAVAILABLE", "PENDING"] as const)(
  "does not present incomplete %s comparisons as a completed negative result",
  async (state) => {
    vi.mocked(api).mockResolvedValueOnce({
      ...duplicates,
      items: [],
      candidatePairCount: 0,
      embeddings: { ...duplicates.embeddings, state },
    });
    setup("duplicates");
    expect(
      await screen.findByRole("heading", {
        name:
          state === "UNAVAILABLE"
            ? "Comparisons need another try."
            : "Comparisons are still being prepared.",
      }),
    ).toBeVisible();
    expect(screen.queryByText("No potential matches in this shortlist.")).not.toBeInTheDocument();
    expect(screen.getByText(/Comparisons are still incomplete/)).toBeVisible();
  },
);

it("distinguishes missing photos from no matched pairs and states truncation clearly", async () => {
  vi.mocked(api).mockResolvedValueOnce({
    ...duplicates,
    photoGarmentCount: 0,
    items: [],
    embeddings: {
      ...duplicates.embeddings,
      state: "NOT_NEEDED",
      eligibleCount: 0,
      indexedCount: 0,
    },
  });
  const view = setup("duplicates");
  expect(await screen.findByText("A little more to compare.")).toBeVisible();
  expect(screen.getByText(/Add and review photos for at least two pieces/)).toBeVisible();
  vi.mocked(api).mockResolvedValueOnce({ ...duplicates, truncated: true });
  await act(async () => {
    await view.client.invalidateQueries({ queryKey: ["search"] });
  });
  expect(
    await screen.findByText(/Comparing a stable selection of up to 2,000 photo pieces/),
  ).toBeVisible();
});

it("shows useful fallback pairings and real history explanations without invented similarity percentages", async () => {
  const user = userEvent.setup();
  setup("works");
  const selected = await screen.findByRole("region", { name: "Selected piece" });
  expect(within(selected).getByRole("link", { name: /Olive shirt/ })).toHaveAttribute(
    "href",
    `/garments/${source.id}`,
  );
  expect(within(selected).getByRole("link", { name: "Build an outfit" })).toHaveAttribute(
    "href",
    `/studio?garment=${source.id}`,
  );
  expect(screen.getByText(/Some comparisons are temporarily unavailable/)).toBeVisible();
  expect(
    screen.getByText(
      "Showing 1 of 4 eligible pieces, ranked by how they complement the selected piece.",
    ),
  ).toBeVisible();
  expect(screen.getByRole("link", { name: /Linen trousers/ })).toHaveAttribute(
    "href",
    `/garments/${trousers.id}`,
  );
  await user.click(screen.getByText("Why these pieces?"));
  expect(screen.getByText("Recorded together in 3 wear entries.")).toBeVisible();
  expect(screen.getByText("Paired in 1 saved outfit.")).toBeVisible();
  expect(screen.getByText(/Semantic affinity is unavailable/)).toBeVisible();
  expect(screen.queryByText(/\d+%/)).not.toBeInTheDocument();
});

it("submits season weather and formality together and restores submitted filters from URLs", async () => {
  const user = userEvent.setup();
  const view = setup("works");
  await screen.findByRole("region", { name: "Selected piece" });
  await user.selectOptions(screen.getByRole("combobox", { name: "Season" }), "WINTER");
  await user.selectOptions(screen.getByRole("combobox", { name: "Weather assumption" }), "COLD");
  await user.type(screen.getByRole("textbox", { name: "Formality" }), "Smart casual");
  await user.selectOptions(screen.getByRole("combobox", { name: "Suggestions per list" }), "24");
  expect(navigation.push).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Apply filters" }));
  expect(navigation.push).toHaveBeenLastCalledWith(
    `/garments/${source.id}/works-with?season=WINTER&weather=COLD&formality=Smart+casual&limit=24`,
    { scroll: false },
  );
  view.navigate("season=WINTER&weather=COLD&formality=Smart+casual&limit=24");
  await waitFor(() =>
    expect(
      vi
        .mocked(api)
        .mock.calls.some(
          ([url]) =>
            url ===
            `garments/${source.id}/works-with?limit=24&season=WINTER&formality=Smart+casual&weather=COLD`,
        ),
    ).toBe(true),
  );
  expect(screen.getByRole("textbox", { name: "Formality" })).toHaveValue("Smart casual");
  expect(screen.getByRole("combobox", { name: "Weather assumption" })).toHaveValue("COLD");
  view.navigate("");
  expect(screen.getByRole("textbox", { name: "Formality" })).toHaveValue("");
  expect(screen.getByRole("combobox", { name: "Season" })).toHaveValue("");
});

it("explains a source that fails requested context and does not show incompatible suggestions", async () => {
  vi.mocked(api).mockResolvedValueOnce({
    ...works,
    sourceMatchesContext: false,
    eligibleCount: 0,
    items: [],
  });
  setup("works", "season=SUMMER");
  expect(
    await screen.findByText(/The selected piece does not meet the requested filters/),
  ).toBeVisible();
  expect(screen.getByRole("heading", { name: "No compatible pieces in this view." })).toBeVisible();
  expect(screen.queryByRole("link", { name: /Linen trousers/ })).not.toBeInTheDocument();
  expect(screen.queryByText(/Showing 0 of/)).not.toBeInTheDocument();
});

it("offers recovery for an unavailable source and refreshes when saved outfits change", async () => {
  const user = userEvent.setup();
  vi.mocked(api).mockRejectedValueOnce(
    new ApiError(
      409,
      "Choose a reviewed, available piece.",
      undefined,
      "PAIRING_SOURCE_UNAVAILABLE",
    ),
  );
  const view = setup("works");
  expect(await screen.findByRole("alert")).toHaveTextContent("Choose a reviewed, available piece.");
  expect(screen.getByRole("link", { name: "Review this piece’s availability" })).toHaveAttribute(
    "href",
    `/garments/${source.id}`,
  );
  await user.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByRole("link", { name: /Linen trousers/ });
  vi.mocked(api).mockResolvedValue({
    ...works,
    items: [{ ...works.items[0], savedTogetherCount: 2, reasons: ["Paired in 2 saved outfits."] }],
  });
  await act(async () => {
    await view.client.invalidateQueries({ queryKey: ["search", "works-with"] });
  });
  await user.click(screen.getByText("Why these pieces?"));
  expect(await screen.findByText("Paired in 2 saved outfits.")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "Refresh suggestions" }));
  await waitFor(() =>
    expect(screen.getByRole("button", { name: "Refresh suggestions" })).toBeEnabled(),
  );
});

it("normalises unsupported season and weather URL values before querying pairings", async () => {
  setup("works", "weather=constructor&season=invalid&limit=5000");
  await screen.findByRole("region", { name: "Selected piece" });
  expect(screen.getByRole("combobox", { name: "Season" })).toHaveValue("");
  expect(screen.getByRole("combobox", { name: "Weather assumption" })).toHaveValue("");
  expect(vi.mocked(api)).toHaveBeenCalledWith(
    `garments/${source.id}/works-with?limit=12`,
    expect.anything(),
  );
});
