import { cpSync, existsSync } from "node:fs";
import { join } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";

const appRoot = fileURLToPath(new URL("../", import.meta.url));
const standalone = join(appRoot, ".next/standalone/apps/web");
const environment = join(appRoot, ".env.local");
if (existsSync(environment)) process.loadEnvFile(environment);
cpSync(join(appRoot, ".next/static"), join(standalone, ".next/static"), { recursive: true });
cpSync(join(appRoot, "public"), join(standalone, "public"), { recursive: true });
process.env.HOSTNAME = "127.0.0.1";
await import(pathToFileURL(join(standalone, "server.js")).href);
