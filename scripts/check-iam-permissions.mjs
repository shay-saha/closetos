import assert from "node:assert/strict";

const array = (value) => (Array.isArray(value) ? value : [value]);
const matches = (pattern, value, insensitive = false) => {
  const expression = pattern
    .replace(/[.+^${}()|[\]\\]/g, "\\$&")
    .replaceAll("*", ".*")
    .replaceAll("?", ".");
  return new RegExp(`^${expression}$`, insensitive ? "i" : "").test(value);
};

export function iamPermission(policies, action, resource, context = {}) {
  let allowed = false;
  for (const policy of policies) {
    for (const statement of policy.Statement) {
      assert(!statement.NotAction && !statement.NotResource && !statement.Principal);
      if (!array(statement.Action).some((pattern) => matches(pattern, action, true))) continue;
      if (!array(statement.Resource).some((pattern) => matches(pattern, resource))) continue;
      const conditions = Object.entries(statement.Condition ?? {});
      if (
        !conditions.every(([operator, keys]) => {
          assert(
            ["StringEquals", "ArnEquals", "StringLike", "ArnLike", "Bool"].includes(operator),
            "Unsupported IAM policy condition",
          );
          return Object.entries(keys).every(([key, values]) => {
            if (context[key] === undefined || context[key] === null) return false;
            return array(values).some((value) =>
              ["StringLike", "ArnLike"].includes(operator)
                ? matches(String(value), String(context[key]))
                : String(value) === String(context[key]),
            );
          });
        })
      ) {
        continue;
      }
      if (statement.Effect === "Deny") return false;
      assert.equal(statement.Effect, "Allow");
      allowed = true;
    }
  }
  return allowed;
}
