// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import { getToken } from "next-auth/jwt";
import { NextRequest } from "next/server";
import { GET } from "../app/api/backend/[...path]/route";

vi.mock("next-auth/jwt", () => ({ getToken: vi.fn() }));
vi.mock("@/lib/auth", () => ({ refreshAccessToken: vi.fn() }));

afterEach(() => {
  vi.unstubAllGlobals();
  vi.resetAllMocks();
});

it("preserves the account limit message and retry timing through the authenticated proxy", async () => {
  vi.mocked(getToken).mockResolvedValue({ accessToken: "private-session-token" });
  const fetch = vi.fn().mockResolvedValue(
    Response.json(
      {
        code: "ACTION_LIMIT",
        detail: "You've reached the semantic search limit. Try again in 1 minute.",
      },
      {
        status: 429,
        headers: {
          "Retry-After": "60",
          "X-Request-Id": "limit-request",
          "X-Internal-Secret": "private",
        },
      },
    ),
  );
  vi.stubGlobal("fetch", fetch);
  const response = await GET(new NextRequest("http://localhost:3000/api/backend/search?q=cream"), {
    params: Promise.resolve({ path: ["search"] }),
  });
  expect(response.status).toBe(429);
  expect(response.headers.get("retry-after")).toBe("60");
  expect(response.headers.get("cache-control")).toBe("no-store");
  expect(response.headers.get("x-request-id")).toBe("limit-request");
  expect(response.headers.get("x-internal-secret")).toBeNull();
  expect(await response.json()).toMatchObject({
    code: "ACTION_LIMIT",
    detail: expect.stringContaining("semantic search limit"),
  });
  expect(fetch).toHaveBeenCalledOnce();
});
