import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import type { Garment } from "@/features/garments/types";
import { GarmentReview } from "./garment-review";
import type { Suggestion } from "./suggestions";

vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const garment: Garment = {
  id: "garment-1",
  name: "My piece",
  category: "OTHER",
  primaryColourName: "Blue",
  purchasePrice: null,
  purchaseCurrency: null,
  status: "AVAILABLE",
  processingStatus: "READY_FOR_REVIEW",
  wearCount: 0,
  lastWornAt: null,
  costPerWear: null,
  version: 3,
  createdAt: "2026-10-01",
  updatedAt: "2026-10-01",
};
const proposal: Suggestion = {
  id: "suggestion-1",
  version: 2,
  status: "PENDING",
  suggestions: {
    category: { value: "TOP", confidence: 0.98 },
    subcategory: { value: "Shirt", confidence: 0.92 },
    primaryColour: { value: "Olive", confidence: 0.98 },
    secondaryColours: { value: ["Cream", "Brown"], confidence: 0.85 },
    materialEstimate: { value: "Polyester", confidence: 0.61 },
    pattern: { value: null, confidence: 0.4 },
    length: { value: "Hip length", confidence: 0.7 },
    formality: { value: "Casual", confidence: 0.9 },
    seasonTags: { value: ["Spring", "Summer"], confidence: 0.9 },
    styleTags: { value: ["Minimal"], confidence: 0.9 },
    occasionTags: { value: ["Everyday"], confidence: 0.9 },
    brand: { value: null, confidence: 0 },
    notes: { value: null, confidence: 0 },
  },
};
beforeEach(() => {
  vi.mocked(api).mockReset();
});
function review(completed = vi.fn()) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <GarmentReview garment={garment} completed={completed} />
    </QueryClientProvider>,
  );
  return completed;
}

it("shows confidence, waits for approval, and sends corrections without read-only garment fields", async () => {
  const user = userEvent.setup();
  vi.mocked(api).mockImplementation(async (path, options) =>
    options?.method ? garment : [proposal],
  );
  const completed = review();
  expect(await screen.findByText("61% confidence · please check")).toBeInTheDocument();
  expect(api).toHaveBeenCalledTimes(1);
  expect(screen.getByLabelText("Material")).toHaveValue("Polyester");
  expect(screen.getByRole("textbox", { name: "Secondary colours" })).toHaveValue("Cream, Brown");
  await user.clear(screen.getByLabelText("Material"));
  await user.type(screen.getByLabelText("Material"), "Cotton");
  await user.clear(screen.getByRole("textbox", { name: "Seasons" }));
  await user.type(screen.getByRole("textbox", { name: "Seasons" }), "Autumn, Winter");
  await user.click(screen.getByRole("button", { name: "Looks right" }));
  await waitFor(() => expect(completed).toHaveBeenCalledOnce());
  const call = vi.mocked(api).mock.calls.find(([path]) => path.endsWith("/accept"))!;
  const body = JSON.parse(call[1]!.body as string);
  expect(body).toMatchObject({
    suggestionId: "suggestion-1",
    suggestionVersion: 2,
    garmentVersion: 3,
    corrections: {
      material: "Cotton",
      seasonTags: ["Autumn", "Winter"],
      category: "TOP",
      purchasePrice: null,
    },
  });
  expect(body.corrections).not.toHaveProperty("id");
  expect(body.corrections).not.toHaveProperty("version");
  expect(body.corrections).not.toHaveProperty("wearCount");
});

it("rejects a proposal and restores current metadata for manual review", async () => {
  const user = userEvent.setup();
  let rejected = false;
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (path.endsWith("/reject")) {
      rejected = true;
      return undefined;
    }
    if (options?.method) return garment;
    return rejected ? [{ ...proposal, status: "REJECTED" }] : [proposal];
  });
  const completed = review();
  await user.click(await screen.findByRole("button", { name: "Reject suggestions" }));
  await screen.findByRole("button", { name: "Save reviewed piece" });
  expect(screen.getByRole("textbox", { name: "Colour" })).toHaveValue("Blue");
  expect(screen.getByRole("textbox", { name: "Piece name *" })).toHaveValue("My piece");
  expect(completed).not.toHaveBeenCalled();
  expect(vi.mocked(api).mock.calls.some(([path]) => path.endsWith("/accept"))).toBe(false);
});

it("keeps edited values visible when the API rejects a stale review", async () => {
  const user = userEvent.setup();
  vi.mocked(api).mockImplementation(async (path) => {
    if (path.endsWith("/accept"))
      throw new ApiError(409, "This piece has changed. Refresh and try again.");
    return [proposal];
  });
  const completed = review();
  await screen.findByRole("button", { name: "Looks right" });
  await user.clear(screen.getByLabelText("Material"));
  await user.type(screen.getByLabelText("Material"), "Linen");
  await user.click(screen.getByRole("button", { name: "Looks right" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("This piece has changed");
  expect(screen.getByLabelText("Material")).toHaveValue("Linen");
  expect(completed).not.toHaveBeenCalled();
});
