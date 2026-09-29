import assert from "node:assert/strict";
import { mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import { checkInfrastructureTests } from "./check-infrastructure-tests.mjs";

const success = {
  type: "test_summary",
  test_summary: { status: "pass", passed: 1, failed: 0, errored: 0, skipped: 0 },
};
const workflow = (definition) => ({
  type: "test_state",
  "@testrun": "runtime",
  test_state: {
    root_module: {
      resources: [
        { type: "aws_sfn_state_machine", values: { definition: JSON.stringify(definition) } },
      ],
    },
  },
});

async function verify(events, requiredWorkflows = ["runtime"]) {
  const directory = await mkdtemp(join(tmpdir(), "closetos-workflow-gate-"));
  try {
    const filename = join(directory, "terraform.jsonl");
    await writeFile(filename, events.map((event) => JSON.stringify(event)).join("\n"));
    await checkInfrastructureTests(filename, { requiredWorkflows, report: () => {} });
  } finally {
    await rm(directory, { recursive: true, force: true });
  }
}

test("accepts rendered workflows and expected Terraform validation failures", async () => {
  await verify([
    { type: "diagnostic", diagnostic: { severity: "error", summary: "Expected rejection" } },
    workflow({ StartAt: "Done", States: { Done: { Type: "Succeed" } } }),
    success,
  ]);
});

test("rejects a Terraform success log that omits the required workflow", async () => {
  await assert.rejects(verify([success]), /missing: runtime/);
});

test("rejects unresolved transitions in an otherwise successful Terraform run", async () => {
  await assert.rejects(
    verify([
      workflow({ StartAt: "Start", States: { Start: { Type: "Pass", Next: "Missing" } } }),
      success,
    ]),
    /Workflow validation failed/,
  );
});

test("rejects an invalid prepared release even when the active workflow still passes", async () => {
  const event = workflow({ StartAt: "Done", States: { Done: { Type: "Succeed" } } });
  event.test_state.outputs = {
    processing_workflow_definition: {
      value: { StartAt: "Start", States: { Start: { Type: "Pass", Next: "Missing" } } },
    },
  };
  await assert.rejects(verify([event, success]), /Workflow validation failed/);
});

test("accepts independently valid active and prepared release workflows", async () => {
  const event = workflow({ StartAt: "Old", States: { Old: { Type: "Succeed" } } });
  event.test_state.outputs = {
    processing_workflow_definition: {
      value: { StartAt: "New", States: { New: { Type: "Succeed" } } },
    },
  };
  await verify([event, success]);
});

test("rejects an invalid reference path that Terraform treats as an opaque string", async () => {
  await assert.rejects(
    verify([
      workflow({
        StartAt: "Start",
        States: { Start: { Type: "Pass", ResultPath: "not-a-path", End: true } },
      }),
      success,
    ]),
    /Workflow validation failed/,
  );
});

test("rejects incomplete, failed and skipped Terraform runs", async () => {
  await assert.rejects(verify([], []), /did not finish/);
  for (const counts of [{ failed: 1 }, { errored: 1 }, { skipped: 1 }, { status: "fail" }]) {
    await assert.rejects(
      verify([{ ...success, test_summary: { ...success.test_summary, ...counts } }], []),
      /did not finish/,
    );
  }
});
