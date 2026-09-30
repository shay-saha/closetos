import assert from "node:assert/strict";

const array = (value) => (Array.isArray(value) ? value : [value]);
const matches = (pattern, value, insensitive = false) => {
  const expression = pattern
    .replace(/[.+^${}()|[\]\\]/g, "\\$&")
    .replaceAll("*", ".*")
    .replaceAll("?", ".");
  return new RegExp(`^${expression}$`, insensitive ? "i" : "").test(value);
};

const condition = (operator, values, actual) => {
  const missing =
    actual === undefined || actual === null || (Array.isArray(actual) && !actual.length);
  if (operator === "Null") return array(values).some((value) => String(value) === String(missing));
  const [quantifier, comparison] = operator.includes(":")
    ? operator.split(":")
    : [undefined, operator];
  const optional = comparison.endsWith("IfExists");
  const base = optional ? comparison.slice(0, -8) : comparison;
  assert(
    [
      "StringEquals",
      "ArnEquals",
      "StringLike",
      "ArnLike",
      "Bool",
      "StringNotEquals",
      "ArnNotEquals",
    ].includes(base),
    "Unsupported IAM policy condition",
  );
  assert(
    !quantifier || ["ForAllValues", "ForAnyValue"].includes(quantifier),
    "Unsupported IAM set condition",
  );
  const negative = ["StringNotEquals", "ArnNotEquals"].includes(base);
  if (missing) return optional || quantifier === "ForAllValues" || (!quantifier && negative);
  const accepted = (value) => {
    const result = array(values).some((expected) =>
      ["StringLike", "ArnLike"].includes(base)
        ? matches(String(expected), String(value))
        : String(expected) === String(value),
    );
    return negative ? !result : result;
  };
  return quantifier === "ForAllValues"
    ? array(actual).every(accepted)
    : array(actual).some(accepted);
};

export function iamPermission(policies, action, resource, context = {}) {
  let allowed = false;
  for (const policy of policies) {
    for (const statement of policy.Statement) {
      assert(!statement.NotAction && !statement.Principal);
      assert(
        !statement.NotResource || (statement.Effect === "Deny" && !statement.Resource),
        "Inverse resources are supported only for explicit denials",
      );
      if (!array(statement.Action).some((pattern) => matches(pattern, action, true))) continue;
      if (statement.NotResource) {
        if (array(statement.NotResource).some((pattern) => matches(pattern, resource))) continue;
      } else if (!array(statement.Resource).some((pattern) => matches(pattern, resource))) continue;
      const conditions = Object.entries(statement.Condition ?? {});
      if (
        !conditions.every(([operator, keys]) => {
          return Object.entries(keys).every(([key, values]) =>
            condition(operator, values, context[key]),
          );
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
