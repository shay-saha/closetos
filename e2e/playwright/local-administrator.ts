import { loadEnvFile } from "node:process";

export async function setLocalAdministrator(email: string, enabled: boolean) {
  if (!process.env.KC_BOOTSTRAP_ADMIN_PASSWORD) loadEnvFile(".env");
  const password = process.env.KC_BOOTSTRAP_ADMIN_PASSWORD;
  if (!password) throw new Error("Local identity administration is not configured.");
  const identity = "http://localhost:8081";
  const login = await fetch(`${identity}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      client_id: "admin-cli",
      grant_type: "password",
      username: "closetos-local-admin",
      password,
    }),
  });
  if (!login.ok) throw new Error(`Local identity administration failed (${login.status}).`);
  const token = (await login.json()) as { access_token: string };
  const headers = {
    Authorization: `Bearer ${token.access_token}`,
    "Content-Type": "application/json",
  };
  const roleEndpoint = `${identity}/admin/realms/closetos/roles/closetos-admin`;
  let response = await fetch(roleEndpoint, { headers });
  if (response.status === 404) {
    const created = await fetch(`${identity}/admin/realms/closetos/roles`, {
      method: "POST",
      headers,
      body: JSON.stringify({ name: "closetos-admin" }),
    });
    if (!created.ok && created.status !== 409)
      throw new Error(`Local role creation failed (${created.status}).`);
    response = await fetch(roleEndpoint, { headers });
  }
  if (!response.ok) throw new Error(`Local role lookup failed (${response.status}).`);
  const role = (await response.json()) as { id: string; name: string };
  const users = await fetch(
    `${identity}/admin/realms/closetos/users?${new URLSearchParams({ email, exact: "true" })}`,
    { headers },
  );
  if (!users.ok) throw new Error(`Local account lookup failed (${users.status}).`);
  const matches = (await users.json()) as { id: string }[];
  if (matches.length !== 1)
    throw new Error("The local test account could not be identified uniquely.");
  const updated = await fetch(
    `${identity}/admin/realms/closetos/users/${encodeURIComponent(matches[0].id)}/role-mappings/realm`,
    {
      method: enabled ? "POST" : "DELETE",
      headers,
      body: JSON.stringify([role]),
    },
  );
  if (!updated.ok) throw new Error(`Local role assignment failed (${updated.status}).`);
}
