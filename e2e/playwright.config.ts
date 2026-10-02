import { defineConfig, devices } from "@playwright/test";
import { appOrigin } from "./playwright/environment";

export default defineConfig({
  testDir: "./playwright",
  timeout: 60_000,
  expect: { timeout: 15_000 },
  fullyParallel: false,
  workers: process.env.CI ? 1 : 2,
  retries: process.env.CI ? 1 : 0,
  reporter: [["list"], ["html", { open: "never", outputFolder: "playwright-report" }]],
  use: {
    baseURL: appOrigin,
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  webServer: {
    command: "pnpm --filter @closetos/web start",
    url: `${appOrigin}/signin`,
    env: { PORT: new URL(appOrigin).port || "3000" },
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] } },
    { name: "mobile-reduced-motion", use: { ...devices["Pixel 7"], reducedMotion: "reduce" } },
  ],
});
