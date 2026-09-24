import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { WardrobeTopology } from "./wardrobe-topology";
import type { WardrobeGraph } from "./types";

const navigation = vi.hoisted(() => ({ params: new URLSearchParams(), push: vi.fn() }));
vi.mock("next/navigation", () => ({
  useSearchParams: () => navigation.params,
  useRouter: () => ({ push: navigation.push }),
}));
vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
vi.mock("next/dynamic", () => ({
  default: () =>
    function Graph({
      graph,
      metric,
      showEdges,
      selected,
      onOpen,
    }: {
      graph: WardrobeGraph;
      metric: string;
      showEdges: boolean;
      selected: string;
      onOpen: (id: string) => void;
    }) {
      return (
        <div data-testid="graph">
          <p>
            {metric} · {String(showEdges)} · {selected}
          </p>
          <button onClick={() => onOpen(graph.nodes[0].id)}>Open graph piece</button>
        </div>
      );
    },
}));

const graph: WardrobeGraph = {
  nodes: [
    {
      id: "00000000-0000-4000-8000-000000000001",
      name: "Olive shirt",
      category: "TOP",
      assets: null,
      colour: "#45664c",
      wearCount: 0,
      lastWornAt: null,
      costPerWear: null,
      purchaseCurrency: null,
      indexed: true,
    },
    {
      id: "00000000-0000-4000-8000-000000000002",
      name: "Sage shirt",
      category: "TOP",
      assets: null,
      colour: null,
      wearCount: 5,
      lastWornAt: "2026-09-01",
      costPerWear: 10,
      purchaseCurrency: "GBP",
      indexed: true,
    },
  ],
  edges: [
    {
      source: "00000000-0000-4000-8000-000000000001",
      target: "00000000-0000-4000-8000-000000000002",
      weight: 0.9,
    },
  ],
  eligibleCount: 2,
  indexedCount: 2,
  truncated: false,
  model: { provider: "local-clip", modelId: "clip", dimensions: 512 },
  embeddingAvailability: "AVAILABLE",
};
function setup(query = "") {
  navigation.params = new URLSearchParams(query);
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  const tree = () => (
    <QueryClientProvider client={client}>
      <WardrobeTopology />
    </QueryClientProvider>
  );
  const view = render(tree());
  return {
    client,
    navigate: (next: string) => {
      navigation.params = new URLSearchParams(next);
      view.rerender(tree());
    },
  };
}
beforeEach(() => {
  navigation.push.mockReset();
  vi.mocked(api).mockReset().mockResolvedValue(graph);
});

it("keeps view, category, colour, and relationship controls in navigable URLs", async () => {
  const user = userEvent.setup();
  const view = setup();
  await screen.findByTestId("graph");
  await user.selectOptions(screen.getByRole("combobox", { name: "Category" }), "TOP");
  expect(navigation.push).toHaveBeenCalledWith("/discover/topology?category=TOP", {
    scroll: false,
  });
  view.navigate("category=TOP&colour=wear&edges=hidden");
  expect(screen.getByRole("combobox", { name: "Colour by" })).toHaveValue("wear");
  expect(await screen.findByRole("checkbox", { name: "Show relationships" })).not.toBeChecked();
  expect(screen.getByTestId("graph")).toHaveTextContent("wear · false");
  expect(within(screen.getByLabelText("Map colour legend")).getByText("Never worn")).toBeVisible();
  await user.click(screen.getByRole("button", { name: "List view" }));
  expect(navigation.push).toHaveBeenCalledWith(
    "/discover/topology?category=TOP&colour=wear&edges=hidden&view=list",
    { scroll: false },
  );
  view.navigate("category=TOP&colour=wear&edges=hidden&view=list");
  expect(screen.queryByTestId("graph")).not.toBeInTheDocument();
  expect(screen.getByRole("button", { name: "List view" })).toHaveAttribute("aria-pressed", "true");
  expect(screen.getByRole("combobox", { name: "Colour by" })).toBeDisabled();
  view.navigate("");
  expect(screen.getByRole("combobox", { name: "Category" })).toHaveValue("");
  expect(screen.getByRole("checkbox", { name: "Show relationships" })).toBeChecked();
  await waitFor(() =>
    expect(
      vi.mocked(api).mock.calls.some(([url]) => url === "insights/topology?category=TOP"),
    ).toBe(true),
  );
});

it("opens actual garments and exposes the same named relationships without 3D", async () => {
  const user = userEvent.setup();
  const view = setup();
  await user.click(await screen.findByRole("button", { name: "Open graph piece" }));
  expect(navigation.push).toHaveBeenCalledWith(`/garments/${graph.nodes[0].id}`);
  await user.selectOptions(
    screen.getByRole("combobox", { name: "Highlight a piece" }),
    graph.nodes[0].id,
  );
  expect(screen.getByTestId("graph")).toHaveTextContent(graph.nodes[0].id);
  expect(screen.getByText("1 related piece highlighted")).toBeVisible();
  expect(screen.getByRole("link", { name: "Open piece" })).toHaveAttribute(
    "href",
    `/garments/${graph.nodes[0].id}`,
  );
  view.navigate("view=list");
  const list = screen.getByRole("region", { name: "Pieces in this map" });
  const row = within(list).getByRole("heading", { name: "Olive shirt" }).closest("li")!;
  await user.click(within(row).getByText("Related pieces (1)"));
  expect(within(row).getByRole("link", { name: "Sage shirt" })).toHaveAttribute(
    "href",
    `/garments/${graph.nodes[1].id}`,
  );
  expect(within(row).getByText("Tops · 0 wears")).toBeVisible();
});

it("retains pieces and explains unavailable coverage and truncation honestly", async () => {
  vi.mocked(api).mockResolvedValue({
    ...graph,
    model: null,
    indexedCount: 0,
    eligibleCount: 50,
    truncated: true,
    embeddingAvailability: "UNAVAILABLE",
    nodes: graph.nodes.map((node) => ({ ...node, indexed: false })),
  });
  setup("view=list");
  expect(await screen.findByText(/Relationships are temporarily unavailable/)).toBeVisible();
  expect(screen.getByText(/This map shows 2 of 50/)).toBeVisible();
  expect(screen.getByRole("link", { name: "browse the full catalogue" })).toHaveAttribute(
    "href",
    "/catalogue",
  );
  expect(screen.getAllByText(/Relationships pending/)).toHaveLength(2);
  expect(screen.getByRole("heading", { name: "Olive shirt" })).toBeVisible();
  expect(screen.queryByText(/NaN|Infinity|£0/)).not.toBeInTheDocument();
});

it("retries a failed request and updates wear statistics when search data is invalidated", async () => {
  vi.mocked(api).mockRejectedValueOnce(new ApiError(503, "Please try again."));
  const user = userEvent.setup();
  const view = setup("view=list");
  expect(await screen.findByRole("alert")).toHaveTextContent("Please try again");
  await user.click(screen.getByRole("button", { name: "Try again" }));
  await screen.findByRole("heading", { name: "Olive shirt" });
  vi.mocked(api).mockResolvedValue({
    ...graph,
    nodes: [{ ...graph.nodes[0], wearCount: 1 }, graph.nodes[1]],
  });
  await act(async () => {
    await view.client.invalidateQueries({ queryKey: ["search"] });
  });
  expect(await screen.findByText("Tops · 1 wears")).toBeVisible();
});

it("limits the list DOM and makes additional pieces available on demand", async () => {
  vi.mocked(api).mockResolvedValue({
    ...graph,
    nodes: Array.from({ length: 121 }, (_, n) => ({
      ...graph.nodes[0],
      id: `piece-${n}`,
      name: `Piece ${n.toString().padStart(3, "0")}`,
    })),
    edges: [],
    eligibleCount: 121,
    indexedCount: 121,
  });
  const user = userEvent.setup();
  setup("view=list");
  await screen.findByText("60 of 121 pieces listed");
  expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(60);
  await user.click(screen.getByRole("button", { name: "Show more pieces" }));
  expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(120);
  await user.click(screen.getByRole("button", { name: "Show more pieces" }));
  expect(screen.getAllByRole("heading", { level: 3 })).toHaveLength(121);
  expect(screen.queryByRole("button", { name: "Show more pieces" })).not.toBeInTheDocument();
});
