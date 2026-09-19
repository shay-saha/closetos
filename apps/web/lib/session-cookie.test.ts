// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import { decode } from "next-auth/jwt";
import { NextRequest, NextResponse } from "next/server";
import { persistRefreshedSession } from "./session-cookie";

afterEach(() => vi.unstubAllEnvs());

it("encrypts refreshed credentials in HttpOnly cookies and clears obsolete chunks", async () => {
  vi.stubEnv("NEXTAUTH_SECRET", "local-test-secret-with-enough-entropy-for-tests");
  const request = new NextRequest("http://localhost:3000/api/backend/garments", {
    headers: { cookie: "next-auth.session-token.0=old; next-auth.session-token.1=old" },
  });
  const response = await persistRefreshedSession(request, NextResponse.json({ items: [] }), {
    accessToken: "new-access",
    refreshToken: "new-refresh",
  });
  const session = response.cookies.get("next-auth.session-token");
  expect(session?.httpOnly).toBe(true);
  expect(session?.sameSite).toBe("lax");
  expect(response.cookies.get("next-auth.session-token.1")?.maxAge).toBe(0);
  const decrypted = await decode({ token: session?.value, secret: process.env.NEXTAUTH_SECRET! });
  expect(decrypted?.accessToken).toBe("new-access");
  expect(decrypted?.refreshToken).toBe("new-refresh");
  expect(await response.json()).toEqual({ items: [] });
});
