// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import { refreshAccessToken } from "./auth";

afterEach(() => vi.unstubAllGlobals());

it("refreshes expiring access tokens while preserving a reusable refresh token", async () => {
  const fetch = vi
    .fn()
    .mockResolvedValueOnce(Response.json({ token_endpoint: "https://identity.test/token" }))
    .mockResolvedValueOnce(Response.json({ access_token: "refreshed-access", expires_in: 300 }));
  vi.stubGlobal("fetch", fetch);
  const result = await refreshAccessToken({
    accessToken: "expired-access",
    refreshToken: "refresh",
  });
  expect(result.accessToken).toBe("refreshed-access");
  expect(result.refreshToken).toBe("refresh");
  expect(result.refreshFailed).toBe(false);
  expect(result.accessTokenExpires).toBeGreaterThan(Date.now());
  expect(fetch.mock.calls[1][1].body.get("grant_type")).toBe("refresh_token");
});

it("persists rotated refresh credentials", async () => {
  vi.stubGlobal(
    "fetch",
    vi
      .fn()
      .mockResolvedValueOnce(Response.json({ token_endpoint: "https://identity.test/token" }))
      .mockResolvedValueOnce(
        Response.json({ access_token: "access", expires_in: 300, refresh_token: "rotated" }),
      ),
  );
  const result = await refreshAccessToken({ refreshToken: "previous" });
  expect(result.refreshToken).toBe("rotated");
});

it("requires reauthentication if the provider rejects or corrupts the refresh result", async () => {
  for (const response of [
    new Response(null, { status: 400 }),
    Response.json({ access_token: "missing-expiry" }),
  ]) {
    vi.stubGlobal(
      "fetch",
      vi
        .fn()
        .mockResolvedValueOnce(Response.json({ token_endpoint: "https://identity.test/token" }))
        .mockResolvedValueOnce(response),
    );
    expect((await refreshAccessToken({ refreshToken: "expired" })).refreshFailed).toBe(true);
  }
});

it("does not contact the provider when refresh credentials are missing", async () => {
  const fetch = vi.fn();
  vi.stubGlobal("fetch", fetch);
  expect((await refreshAccessToken({})).refreshFailed).toBe(true);
  expect(fetch).not.toHaveBeenCalled();
});
