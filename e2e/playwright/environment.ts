export const appOrigin = new URL(process.env.E2E_BASE_URL ?? "http://localhost:3000").origin;
export const storageOrigin = new URL(process.env.E2E_STORAGE_URL ?? "http://localhost:9000").origin;
export const identityOrigin = new URL(process.env.E2E_IDENTITY_URL ?? "http://localhost:8081")
  .origin;
