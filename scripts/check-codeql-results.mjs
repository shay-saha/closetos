import { readdir, readFile, stat } from "node:fs/promises";
import { join, resolve } from "node:path";
import { pathToFileURL } from "node:url";

export function inspectReport(report) {
  if (report?.version !== "2.1.0" || !Array.isArray(report.runs) || !report.runs.length)
    throw new Error("Expected a complete SARIF 2.1.0 report.");
  const critical = [];
  let findings = 0;
  for (const run of report.runs) {
    const driver = run.tool?.driver;
    if (driver?.name !== "CodeQL" || !Array.isArray(driver.rules) || !Array.isArray(run.results))
      throw new Error("Missing CodeQL rules or results.");
    for (const invocation of run.invocations ?? []) {
      if (
        invocation.executionSuccessful === false ||
        [
          ...(invocation.toolExecutionNotifications ?? []),
          ...(invocation.toolConfigurationNotifications ?? []),
        ].some((notification) => notification.level === "error")
      )
        throw new Error("CodeQL analysis did not complete successfully.");
    }
    const ids = new Set(driver.rules.map((rule) => rule.id));
    if (
      ids.size !== driver.rules.length ||
      driver.rules.some((rule) => typeof rule.id !== "string" || !rule.id.trim())
    )
      throw new Error("CodeQL rules have missing or duplicated identifiers.");
    for (const result of run.results) {
      let rule;
      if (result.ruleIndex !== undefined) {
        if (!Number.isInteger(result.ruleIndex) || result.ruleIndex < 0)
          throw new Error("Invalid finding rule index.");
        rule = driver.rules[result.ruleIndex];
      } else rule = driver.rules.find((candidate) => candidate.id === result.ruleId);
      if (!rule || (result.ruleId !== undefined && result.ruleId !== rule.id))
        throw new Error("Finding does not reference its CodeQL rule.");
      const raw = rule.properties?.["security-severity"];
      const score = Number(raw);
      if (
        (typeof raw !== "string" && typeof raw !== "number") ||
        (typeof raw === "string" && !/^\d+(?:\.\d+)?$/.test(raw)) ||
        !Number.isFinite(score) ||
        score < 0 ||
        score > 10
      )
        throw new Error("Finding has no valid security severity.");
      findings++;
      if (score >= 9) critical.push({ rule: rule.id, score });
    }
  }
  return { findings, critical };
}

export async function checkReports(target) {
  const location = resolve(target);
  const files = (await stat(location)).isDirectory()
    ? (await readdir(location))
        .filter((file) => file.endsWith(".sarif"))
        .map((file) => join(location, file))
    : [location];
  if (!files.length) throw new Error("No CodeQL reports were produced.");
  let findings = 0;
  let critical = 0;
  for (const file of files) {
    const result = inspectReport(JSON.parse(await readFile(file, "utf8")));
    findings += result.findings;
    for (const finding of result.critical)
      console.error(`Critical CodeQL finding: ${JSON.stringify(finding)}`);
    critical += result.critical.length;
  }
  if (critical) throw new Error(`${critical} critical CodeQL finding(s) block this change.`);
  console.log(`${files.length} CodeQL report(s) checked; ${findings} finding(s), none critical.`);
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  try {
    if (process.argv.length !== 3) throw new Error("Provide a SARIF report or report directory.");
    await checkReports(process.argv[2]);
  } catch (error) {
    console.error(`CodeQL gate failed: ${error.message}`);
    process.exitCode = 1;
  }
}
