import { afterEach, describe, expect, it, vi } from "vitest";
import { personalDataFile } from "@/lib/personal-data-download";

afterEach(() => vi.unstubAllGlobals());

describe("personal data downloads", () => {
  it("keeps the complete file and forwards cancellation", async () => {
    const contents = '{"schemaVersion":1,"garments":[],"complete":true}';
    const fetch = vi.fn().mockResolvedValue(new Response(contents));
    vi.stubGlobal("fetch", fetch);
    const signal = new AbortController().signal;
    expect(await (await personalDataFile(signal)).text()).toBe(contents);
    expect(fetch).toHaveBeenCalledWith("/api/backend/me/data", expect.objectContaining({ signal }));
  });

  it("rejects a truncated stream instead of offering a partial data file", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response('{"garments":[{"notes":"complete"}')),
    );
    await expect(personalDataFile(new AbortController().signal)).rejects.toThrow("interrupted");
  });

  it("preserves an allowance error instead of downloading the error response", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({
            detail: "Try your data download again later.",
            code: "ACTION_LIMIT",
          }),
          { status: 429 },
        ),
      ),
    );
    await expect(personalDataFile(new AbortController().signal)).rejects.toMatchObject({
      status: 429,
      code: "ACTION_LIMIT",
      message: "Try your data download again later.",
    });
  });
});
