import { getToken } from "next-auth/jwt";
import { NextRequest, NextResponse } from "next/server";
import { refreshAccessToken } from "@/lib/auth";
import { persistRefreshedSession, secureSessionCookie } from "@/lib/session-cookie";

async function proxy(request: NextRequest, context: { params: Promise<{ path: string[] }> }) {
  if (
    !["GET", "HEAD"].includes(request.method) &&
    request.headers.get("origin") !== new URL(process.env.NEXTAUTH_URL ?? request.url).origin
  ) {
    return NextResponse.json({ detail: "This request could not be verified." }, { status: 403 });
  }
  let token = await getToken({
    req: request,
    secret: process.env.NEXTAUTH_SECRET,
    secureCookie: secureSessionCookie,
  });
  const refreshed = !!token?.accessTokenExpires && token.accessTokenExpires <= Date.now() + 30_000;
  if (refreshed && token) token = await refreshAccessToken(token);
  if (!token?.accessToken || token.refreshFailed) {
    return NextResponse.json({ detail: "Your session has ended. Sign in again." }, { status: 401 });
  }
  const { path } = await context.params;
  if (path.some((segment) => segment === "." || segment === ".." || segment.includes("/"))) {
    return NextResponse.json({ detail: "Invalid request path." }, { status: 400 });
  }
  const endpoint = new URL(
    `/api/v1/${path.map(encodeURIComponent).join("/")}`,
    process.env.API_URL ?? "http://localhost:8080",
  );
  endpoint.search = request.nextUrl.search;
  try {
    const response = await fetch(endpoint, {
      method: request.method,
      headers: {
        Authorization: `Bearer ${token.accessToken}`,
        "Content-Type": "application/json",
        ...(request.headers.has("idempotency-key")
          ? { "Idempotency-Key": request.headers.get("idempotency-key")! }
          : {}),
      },
      body: ["GET", "HEAD"].includes(request.method) ? undefined : await request.text(),
      cache: "no-store",
      signal: AbortSignal.timeout(
        request.method === "GET" && path.join("/") === "me/data" ? 90_000 : 15_000,
      ),
    });
    const result = new NextResponse(response.body, {
      status: response.status,
      headers: {
        "Content-Type": response.headers.get("content-type") ?? "application/json",
        "Cache-Control": "no-store",
        ...(response.headers.has("content-disposition")
          ? { "Content-Disposition": response.headers.get("content-disposition")! }
          : {}),
        ...(response.headers.has("x-request-id")
          ? { "X-Request-Id": response.headers.get("x-request-id")! }
          : {}),
        ...(response.headers.has("retry-after")
          ? { "Retry-After": response.headers.get("retry-after")! }
          : {}),
      },
    });
    return refreshed ? persistRefreshedSession(request, result, token) : result;
  } catch {
    return NextResponse.json(
      { detail: "Your wardrobe is temporarily unavailable. Try again shortly." },
      { status: 502 },
    );
  }
}

export { proxy as GET, proxy as POST, proxy as PATCH, proxy as DELETE };
