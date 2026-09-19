import { encode, type JWT } from "next-auth/jwt";
import { type NextRequest, NextResponse } from "next/server";

export const secureSessionCookie = (process.env.NEXTAUTH_URL ?? "").startsWith("https://");
const cookieName = `${secureSessionCookie ? "__Secure-" : ""}next-auth.session-token`;
const maxAge = 60 * 60 * 24 * 7;

export async function persistRefreshedSession(
  request: NextRequest,
  response: NextResponse,
  token: JWT,
) {
  const value = await encode({ token, secret: process.env.NEXTAUTH_SECRET!, maxAge });
  for (const cookie of request.cookies.getAll()) {
    if (cookie.name === cookieName || cookie.name.startsWith(`${cookieName}.`)) {
      response.cookies.set(cookie.name, "", {
        maxAge: 0,
        path: "/",
        httpOnly: true,
        secure: secureSessionCookie,
        sameSite: "lax",
      });
    }
  }
  const chunks = Math.ceil(value.length / 3800);
  for (let index = 0; index < chunks; index++) {
    response.cookies.set(
      chunks === 1 ? cookieName : `${cookieName}.${index}`,
      value.slice(index * 3800, (index + 1) * 3800),
      {
        httpOnly: true,
        secure: secureSessionCookie,
        sameSite: "lax",
        path: "/",
        maxAge,
      },
    );
  }
  return response;
}
