import type { NextAuthOptions } from "next-auth";
import type { JWT } from "next-auth/jwt";
import { z } from "zod";

export const issuer = process.env.OIDC_ISSUER ?? "http://localhost:8081/realms/closetos";
const clientId = process.env.OIDC_CLIENT_ID ?? "closetos-web";
const tokenResponse = z.object({
  access_token: z.string(),
  expires_in: z.number().positive(),
  refresh_token: z.string().optional(),
});

export async function refreshAccessToken(token: JWT): Promise<JWT> {
  if (!token.refreshToken) return { ...token, refreshFailed: true };
  try {
    const metadata = await fetch(`${issuer}/.well-known/openid-configuration`, {
      signal: AbortSignal.timeout(8000),
    });
    if (!metadata.ok) throw new Error("Identity provider unavailable");
    const discovery = z.object({ token_endpoint: z.url() }).parse(await metadata.json());
    const response = await fetch(discovery.token_endpoint, {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        grant_type: "refresh_token",
        client_id: clientId,
        refresh_token: token.refreshToken,
        ...(process.env.OIDC_CLIENT_SECRET
          ? { client_secret: process.env.OIDC_CLIENT_SECRET }
          : {}),
      }),
      signal: AbortSignal.timeout(8000),
      cache: "no-store",
    });
    if (!response.ok) throw new Error("Session expired");
    const refreshed = tokenResponse.parse(await response.json());
    return {
      ...token,
      accessToken: refreshed.access_token,
      accessTokenExpires: Date.now() + refreshed.expires_in * 1000,
      refreshToken: refreshed.refresh_token ?? token.refreshToken,
      refreshFailed: false,
    };
  } catch {
    return { ...token, refreshFailed: true };
  }
}

export const authOptions: NextAuthOptions = {
  secret: process.env.NEXTAUTH_SECRET,
  session: { strategy: "jwt", maxAge: 60 * 60 * 24 * 7 },
  pages: { signIn: "/signin", error: "/signin" },
  providers: [
    {
      id: "wardrobe",
      name: "Wardrobe account",
      type: "oauth",
      wellKnown: `${issuer}/.well-known/openid-configuration`,
      clientId,
      clientSecret: process.env.OIDC_CLIENT_SECRET ?? "",
      idToken: true,
      checks: ["pkce", "state", "nonce"],
      authorization: { params: { scope: "openid profile email" } },
      client: {
        token_endpoint_auth_method: process.env.OIDC_CLIENT_SECRET ? "client_secret_post" : "none",
      },
      profile(profile: { sub: string; name?: string; email?: string }) {
        return { id: profile.sub, name: profile.name ?? "Your wardrobe", email: profile.email };
      },
    },
  ],
  callbacks: {
    async jwt({ token, account }) {
      if (account)
        return {
          ...token,
          accessToken: account.access_token,
          refreshToken: account.refresh_token,
          accessTokenExpires: (account.expires_at ?? 0) * 1000,
        };
      return token;
    },
  },
};
