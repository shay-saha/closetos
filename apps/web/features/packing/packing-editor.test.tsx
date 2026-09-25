import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import { PackingWorkspace } from "./packing-editor";
import { initialTrip, type PackingList } from "./types";

const navigation = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => navigation }));
vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const shirt: Garment = {
  id: "00000000-0000-4000-8000-000000000001",
  name: "Travel shirt",
  category: "TOP",
  status: "AVAILABLE",
  processingStatus: "READY",
  wearCount: 0,
  lastWornAt: null,
  costPerWear: null,
  version: 4,
  createdAt: "2026-01-01T00:00:00Z",
  updatedAt: "2026-01-01T00:00:00Z",
};
const replacement: Garment = {
  ...shirt,
  id: "00000000-0000-4000-8000-000000000002",
  name: "Spare shirt",
};
const saved: PackingList = {
  ...initialTrip("2026-10-02"),
  name: "Weekend away",
  id: "00000000-0000-4000-8000-000000000010",
  version: 2,
  manualOverride: false,
  plan: {
    status: "OPTIMAL",
    selectedGarments: [shirt.id],
    outfits: [{ demandIndex: 0, garmentIds: [shirt.id] }],
    warnings: [],
  },
  stale: false,
  explanations: ["Complete outfits have been verified."],
  items: [{ garment: shirt, status: "TO_PACK" }],
  constraintPieces: [],
  schedule: [
    {
      index: 0,
      date: "2026-10-02",
      name: "Everyday",
      occasionTag: null,
      formality: null,
      laundryPeriod: 0,
    },
  ],
  createdAt: "2026-10-01T00:00:00Z",
  updatedAt: "2026-10-01T00:00:00Z",
};
saved.constraints.weather.assumptions = ["MILD"];
function show(initial?: PackingList, reload?: () => Promise<PackingList>) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <PackingWorkspace initial={initial} reload={reload} />
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith("garments?")) return { items: [shirt, replacement], nextCursor: null };
    throw new Error(`Unexpected request: ${path}`);
  });
});

it("keeps a new trip local until weather and schedule validation succeed", async () => {
  const user = userEvent.setup();
  show();
  await user.type(screen.getByLabelText("Trip name *"), "A weekend");
  await user.click(screen.getByRole("button", { name: "Save trip" }));
  expect(
    await screen.findByText("Choose weather assumptions or provide a temperature range."),
  ).toBeVisible();
  expect(
    vi.mocked(api).mock.calls.filter(([path]) => path.startsWith("packing-lists")),
  ).toHaveLength(0);
});

it("preserves unsaved input on a version conflict until the user explicitly reloads", async () => {
  const user = userEvent.setup();
  const reload = vi.fn().mockResolvedValue({ ...saved, name: "Changed elsewhere", version: 3 });
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith("garments?")) return { items: [shirt], nextCursor: null };
    throw new ApiError(409, "The trip changed.", undefined, "VERSION_CONFLICT");
  });
  show(saved, reload);
  await user.click(screen.getByText("Trip details and constraints"));
  await user.clear(screen.getByLabelText("Trip name *"));
  await user.type(screen.getByLabelText("Trip name *"), "My draft");
  await user.click(screen.getByRole("button", { name: "Save trip changes" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Reloading discards unsaved edits.");
  expect(screen.getByLabelText("Trip name *")).toHaveValue("My draft");
  expect(reload).not.toHaveBeenCalled();
  await user.click(screen.getByRole("button", { name: "Reload current trip" }));
  await waitFor(() =>
    expect(screen.getByLabelText("Trip name *")).toHaveValue("Changed elsewhere"),
  );
  expect(reload).toHaveBeenCalledOnce();
});

it("retains the saved capsule when a manual replacement violates a hard constraint", async () => {
  const user = userEvent.setup();
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith("garments?")) return { items: [shirt, replacement], nextCursor: null };
    throw new ApiError(422, "A required piece is missing.");
  });
  show(saved);
  await user.selectOptions(screen.getByLabelText("Piece to replace"), shirt.id);
  await user.click(
    await screen.findByRole("button", { name: "Choose Spare shirt as replacement" }),
  );
  expect(await screen.findByRole("alert")).toHaveTextContent("A required piece is missing.");
  expect(screen.getByRole("heading", { name: "Optimised capsule" })).toBeVisible();
  expect(screen.queryByRole("heading", { name: "Your adjusted capsule" })).not.toBeInTheDocument();
  const request = vi.mocked(api).mock.calls.find(([path]) => path.endsWith("/manual"))!;
  const body = JSON.parse(String(request[1]?.body));
  expect(body.version).toBe(2);
  expect(body.plan.status).toBe("FEASIBLE");
  expect(body.plan.selectedGarments).toEqual([replacement.id]);
});

it("allows unpacking a stale capsule while blocking new packing and preserves garment versions", async () => {
  const user = userEvent.setup();
  const packed: PackingList = {
    ...saved,
    stale: true,
    items: [
      { garment: { ...shirt, status: "PACKED" }, status: "PACKED" },
      { garment: replacement, status: "TO_PACK" },
    ],
  };
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.startsWith("garments?")) return { items: [shirt, replacement], nextCursor: null };
    return {
      ...packed,
      version: 3,
      items: [
        { garment: { ...shirt, version: 5 }, status: "TO_PACK" },
        { garment: replacement, status: "TO_PACK" },
      ],
    };
  });
  show(packed);
  expect(screen.getByRole("button", { name: "Mark packed: Spare shirt" })).toBeDisabled();
  expect(screen.getByRole("button", { name: "Regenerate capsule" })).toBeDisabled();
  await user.click(screen.getByRole("button", { name: "Unpack: Travel shirt" }));
  await waitFor(() =>
    expect(vi.mocked(api).mock.calls.some(([path]) => path.endsWith(`/items/${shirt.id}`))).toBe(
      true,
    ),
  );
  const request = vi.mocked(api).mock.calls.find(([path]) => path.endsWith(`/items/${shirt.id}`))!;
  expect(JSON.parse(String(request[1]?.body))).toEqual({
    version: 2,
    garmentVersion: 4,
    packed: false,
  });
});
