import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { beforeEach, expect, it, vi } from "vitest";
import { api, ApiError } from "@/lib/api";
import { ReembeddingAdmin } from "./reembedding-admin";

vi.mock("@/lib/api", async (original) => ({
  ...(await original<typeof import("@/lib/api")>()),
  api: vi.fn(),
}));
const job = {
  id: "00000000-0000-4000-8000-000000000001",
  wardrobeId: "00000000-0000-4000-8000-000000000002",
  model: {
    provider: "local-clip",
    modelId: "clip",
    modelVersion: "pinned",
    pipelineVersion: "multimodal-1",
    dimensions: 512,
  },
  state: "SUCCEEDED",
  total: 2,
  queued: 0,
  running: 0,
  succeeded: 2,
  skipped: 0,
  failed: 0,
  failureCounts: {},
  requestedAt: "2026-10-01T12:00:00Z",
  completedAt: "2026-10-01T12:01:00Z",
};

function setup() {
  const client = new QueryClient({
    defaultOptions: { queries: { retryDelay: 0 }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <ReembeddingAdmin />
    </QueryClientProvider>,
  );
  return client;
}
beforeEach(() => {
  vi.mocked(api).mockReset();
});

it("hides administrator controls when the server rejects an ordinary account", async () => {
  vi.mocked(api).mockRejectedValue(new ApiError(403, "Not permitted."));
  const client = setup();
  await waitFor(() => expect(client.getQueryState(["reembedding-jobs"])?.status).toBe("error"));
  expect(screen.queryByRole("region", { name: "Search administration" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Start search rebuild" })).not.toBeInTheDocument();
});

it("reuses an idempotency key after a lost reply and creates a new key for the next intentional rebuild", async () => {
  let calls = 0;
  vi.mocked(api).mockImplementation(async (path, options) => {
    if (options?.method === "POST") {
      if (++calls === 1) throw new ApiError(502, "The reply was lost.");
      return job;
    }
    return calls >= 2 ? [job] : [];
  });
  const user = userEvent.setup();
  setup();
  await user.click(await screen.findByRole("button", { name: "Start search rebuild" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("The reply was lost");
  await user.click(screen.getByRole("button", { name: "Start search rebuild" }));
  await screen.findByRole("heading", { name: "Complete" });
  await user.click(screen.getByRole("button", { name: "Start search rebuild" }));
  await waitFor(() => expect(calls).toBe(3));
  const posts = vi.mocked(api).mock.calls.filter(([, options]) => options?.method === "POST");
  const keys = posts.map(
    ([, options]) => (options!.headers as Record<string, string>)["Idempotency-Key"],
  );
  expect(keys[0]).toBe(keys[1]);
  expect(keys[2]).not.toBe(keys[1]);
  expect(posts.every(([, options]) => options!.body === "{}")).toBe(true);
});

it("validates a selected wardrobe and submits the explicit scope", async () => {
  vi.mocked(api).mockImplementation(async (_, options) => (options?.method === "POST" ? job : []));
  const user = userEvent.setup();
  setup();
  await user.selectOptions(
    await screen.findByRole("combobox", { name: "Rebuild scope" }),
    "selected",
  );
  await user.type(screen.getByRole("textbox", { name: "Wardrobe identifier" }), "invalid");
  await user.click(screen.getByRole("button", { name: "Start search rebuild" }));
  expect(vi.mocked(api).mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
  await user.clear(screen.getByRole("textbox", { name: "Wardrobe identifier" }));
  await user.type(screen.getByRole("textbox", { name: "Wardrobe identifier" }), job.wardrobeId);
  await user.click(screen.getByRole("button", { name: "Start search rebuild" }));
  await waitFor(() =>
    expect(vi.mocked(api).mock.calls.some(([, options]) => options?.method === "POST")).toBe(true),
  );
  const post = vi.mocked(api).mock.calls.find(([, options]) => options?.method === "POST")!;
  expect(JSON.parse(String(post[1]!.body))).toEqual({ wardrobeId: job.wardrobeId });
});

it("shows completed failures and skipped pieces without presenting a failed task as prepared", async () => {
  vi.mocked(api).mockResolvedValue([
    {
      ...job,
      state: "PARTIAL_FAILURE",
      total: 3,
      succeeded: 1,
      skipped: 1,
      failed: 1,
      failureCounts: { EMBEDDING_MODEL_CHANGED: 1 },
    },
  ]);
  setup();
  const card = await screen.findByRole("article", { name: `Rebuild ${job.id}` });
  expect(within(card).getByRole("heading", { name: "Complete with failures" })).toBeVisible();
  expect(within(card).getByRole("progressbar")).toHaveAttribute("value", "3");
  expect(within(card).getByText("Prepared").nextElementSibling).toHaveTextContent("1");
  expect(within(card).getByText("Failed", { exact: true }).nextElementSibling).toHaveTextContent(
    "1",
  );
  expect(within(card).getByText(/configured model changed/)).toBeVisible();
  expect(within(card).getByText(/Skipped pieces were removed/)).toBeVisible();
});
