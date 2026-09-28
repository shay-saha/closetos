import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { spawnSync } from "node:child_process";
import { test } from "node:test";
import { inspectReport } from "./check-codeql-results.mjs";

function report(score = "9.8") {
  return {
    version: "2.1.0",
    runs: [
      {
        tool: {
          driver: {
            name: "CodeQL",
            rules: [{ id: "java/sql-injection", properties: { "security-severity": score } }],
          },
        },
        results: [{ ruleId: "java/sql-injection", ruleIndex: 0, level: "warning" }],
        invocations: [{ executionSuccessful: true }],
      },
    ],
  };
}

test("security metadata blocks a critical finding even when its display level is warning", () => {
  assert.deepEqual(inspectReport(report()), {
    findings: 1,
    critical: [{ rule: "java/sql-injection", score: 9.8 }],
  });
  assert.equal(inspectReport(report("8.9")).critical.length, 0);
  assert.equal(inspectReport(report("9.0")).critical.length, 1);
});

test("existing, suppressed, and supposedly absent critical findings still block", () => {
  for (const baselineState of ["unchanged", "updated", "absent"]) {
    const input = report();
    Object.assign(input.runs[0].results[0], {
      baselineState,
      suppressions: [{ kind: "external", status: "accepted" }],
    });
    assert.equal(inspectReport(input).critical.length, 1);
  }
});

test("valid completed reports with no findings are accepted", () => {
  const input = report();
  input.runs[0].results = [];
  input.runs[0].tool.driver.rules = [];
  assert.deepEqual(inspectReport(input), { findings: 0, critical: [] });
});

test("truncated reports and foreign scanner output cannot produce a green result", () => {
  for (const input of [null, {}, { version: "2.1.0", runs: [] }, { version: "2.1.0", runs: [{}] }])
    assert.throws(() => inspectReport(input));
  const input = report();
  input.runs[0].tool.driver.name = "Unrelated scanner";
  assert.throws(() => inspectReport(input), /Missing CodeQL/);
  input.runs[0].tool.driver.name = "CodeQL";
  delete input.runs[0].results;
  assert.throws(() => inspectReport(input), /Missing CodeQL/);
});

test("unsuccessful or partially failed analysis cannot hide behind empty findings", () => {
  const input = report();
  input.runs[0].results = [];
  input.runs[0].invocations[0].executionSuccessful = false;
  assert.throws(() => inspectReport(input), /did not complete/);
  input.runs[0].invocations[0].executionSuccessful = true;
  for (const field of ["toolExecutionNotifications", "toolConfigurationNotifications"]) {
    input.runs[0].invocations[0][field] = [{ level: "error" }];
    assert.throws(() => inspectReport(input), /did not complete/);
    delete input.runs[0].invocations[0][field];
  }
});

test("unknown, duplicated, and mismatched rule references fail closed", () => {
  for (const index of [-1, 1, 0.5, "0"]) {
    const input = report();
    input.runs[0].results[0].ruleIndex = index;
    assert.throws(() => inspectReport(input));
  }
  const input = report();
  input.runs[0].results[0].ruleId = "java/other-rule";
  assert.throws(() => inspectReport(input), /does not reference/);
  input.runs[0].results[0].ruleId = "java/sql-injection";
  delete input.runs[0].results[0].ruleIndex;
  assert.equal(inspectReport(input).critical.length, 1);
  input.runs[0].tool.driver.rules.push(input.runs[0].tool.driver.rules[0]);
  assert.throws(() => inspectReport(input), /duplicated/);
});

test("missing, nonnumeric, and out-of-range severity cannot bypass the critical gate", () => {
  for (const score of [undefined, null, "", " ", "0x0", "unknown", "NaN", true, "11", "-1"]) {
    assert.throws(() => inspectReport(report(score === undefined ? null : score)), /severity/);
  }
});

test("the CLI fails for missing, empty, malformed, and critical reports and checks every file", async () => {
  const directory = await mkdtemp(join(tmpdir(), "closetos-codeql-gate-"));
  const run = (path) =>
    spawnSync(process.execPath, ["scripts/check-codeql-results.mjs", path], { encoding: "utf8" });
  try {
    assert.equal(run(join(directory, "missing.sarif")).status, 1);
    assert.equal(run(directory).status, 1);
    await writeFile(join(directory, "valid.sarif"), JSON.stringify(report("4.0")));
    assert.equal(run(directory).status, 0);
    await writeFile(join(directory, "critical.sarif"), JSON.stringify(report()));
    assert.equal(run(directory).status, 1);
    await writeFile(join(directory, "critical.sarif"), "{truncated");
    assert.equal(run(directory).status, 1);
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
});
