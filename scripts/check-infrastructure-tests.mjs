import { createReadStream } from "node:fs";
import { resolve } from "node:path";
import { createInterface } from "node:readline";
import { pathToFileURL } from "node:url";
import validateWorkflow from "asl-validator";

export const runtimeScenarios = [
  "runtime_preparation_keeps_services_stopped",
  "development_runtime_contract",
  "production_runtime_redundancy",
];

export async function checkInfrastructureTests(
  filename,
  { requiredWorkflows = [], report = console.log } = {},
) {
  const lines = createInterface({
    input: createReadStream(filename),
    crlfDelay: Infinity,
  });
  const workflows = new Set();
  const diagnostics = [];
  let summary;
  let failedWorkflow = false;
  for await (const line of lines) {
    if (!line.trim()) continue;
    const event = JSON.parse(line);
    if (event.type === "test_run" && event.test_run.progress === "complete") {
      report(`${event.test_run.path}: ${event.test_run.run}: ${event.test_run.status}`);
    }
    if (event.type === "diagnostic") diagnostics.push(event.diagnostic);
    if (event.type === "test_summary") summary = event.test_summary;
    if (event.type !== "test_state") continue;
    for (const resource of event.test_state.root_module?.resources ?? []) {
      if (resource.type !== "aws_sfn_state_machine") continue;
      const result = validateWorkflow(JSON.parse(resource.values.definition));
      if (!result.isValid) {
        failedWorkflow = true;
        report(`${event["@testrun"]}: invalid workflow: ${result.errorsText()}`);
      } else {
        workflows.add(event["@testrun"]);
        report(`${event["@testrun"]}: rendered workflow schema and paths pass`);
      }
    }
  }
  if (
    !summary ||
    summary.status !== "pass" ||
    summary.failed ||
    summary.errored ||
    summary.skipped
  ) {
    for (const diagnostic of diagnostics) {
      report(`${diagnostic.severity}: ${diagnostic.summary}\n${diagnostic.detail}`);
    }
    throw new Error("Terraform tests did not finish successfully without skipped scenarios");
  }
  report(`${summary.passed} Terraform scenarios passed`);
  const missing = requiredWorkflows.filter((scenario) => !workflows.has(scenario));
  if (failedWorkflow || missing.length) {
    throw new Error(
      `Workflow validation failed${missing.length ? `; missing: ${missing.join(", ")}` : ""}`,
    );
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const [filename, module] = process.argv.slice(2);
  if (!filename || !["aws", "bootstrap"].includes(module)) {
    console.error(
      "Usage: node scripts/check-infrastructure-tests.mjs <terraform-json-log> <aws|bootstrap>",
    );
    process.exitCode = 1;
  } else {
    try {
      await checkInfrastructureTests(filename, {
        requiredWorkflows: module === "aws" ? runtimeScenarios : [],
      });
    } catch (error) {
      console.error(error.message);
      process.exitCode = 1;
    }
  }
}
