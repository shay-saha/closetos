import assert from "node:assert/strict";
import test from "node:test";
import { iamPermission } from "./check-iam-permissions.mjs";

const policy = (Statement) => [{ Version: "2012-10-17", Statement }];

test("permission checks default to denial and match IAM actions independently of resource case", () => {
  const policies = policy([
    {
      Effect: "Allow",
      Action: "s3:GetObject",
      Resource: "arn:aws:s3:::state/dev/closetos.tfstate",
    },
  ]);
  assert(iamPermission(policies, "s3:getobject", "arn:aws:s3:::state/dev/closetos.tfstate"));
  assert(!iamPermission(policies, "s3:GetObject", "arn:aws:s3:::state/DEV/closetos.tfstate"));
  assert(!iamPermission(policies, "s3:PutObject", "arn:aws:s3:::state/dev/closetos.tfstate"));
  assert(!iamPermission([], "s3:GetObject", "arn:aws:s3:::state/dev/closetos.tfstate"));
});

test("resource wildcards do not reinterpret punctuation and one-character secret suffixes", () => {
  const policies = policy([
    {
      Effect: "Allow",
      Action: "secretsmanager:DescribeSecret",
      Resource:
        "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/database-app-??????",
    },
    {
      Effect: "Allow",
      Action: "ecs:ListTagsForResource",
      Resource: "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:*",
    },
    {
      Effect: "Allow",
      Action: "s3:GetObject",
      Resource: "arn:aws:s3:::state/dev/closetos.tfstate",
    },
  ]);
  const secret =
    "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/database-app-ABC123";
  assert(iamPermission(policies, "secretsmanager:DescribeSecret", secret));
  assert(!iamPermission(policies, "secretsmanager:DescribeSecret", `${secret}X`));
  assert(!iamPermission(policies, "secretsmanager:DescribeSecret", secret.slice(0, -1)));
  assert(
    iamPermission(
      policies,
      "ecs:ListTagsForResource",
      "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:42",
    ),
  );
  assert(
    !iamPermission(
      policies,
      "ecs:ListTagsForResource",
      "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-prod-api:42",
    ),
  );
  assert(!iamPermission(policies, "s3:GetObject", "arn:aws:s3:::state/dev/closetosXtfstate"));
});

test("StringEquals requires every context key while accepting each permitted value", () => {
  const policies = policy([
    {
      Effect: "Allow",
      Action: ["s3:ListBucket"],
      Resource: ["arn:aws:s3:::state"],
      Condition: {
        StringEquals: {
          "s3:prefix": ["dev/state", "dev/state.tflock"],
          "aws:RequestedRegion": "eu-west-2",
        },
      },
    },
  ]);
  for (const prefix of ["dev/state", "dev/state.tflock"]) {
    assert(
      iamPermission(policies, "s3:ListBucket", "arn:aws:s3:::state", {
        "s3:prefix": prefix,
        "aws:RequestedRegion": "eu-west-2",
      }),
    );
  }
  for (const context of [
    {},
    { "s3:prefix": "dev/state" },
    { "s3:prefix": "prod/state", "aws:RequestedRegion": "eu-west-2" },
  ]) {
    assert(!iamPermission(policies, "s3:ListBucket", "arn:aws:s3:::state", context));
  }
});

test("explicit denials override allows regardless of policy order", () => {
  const allow = { Effect: "Allow", Action: "s3:GetObject", Resource: "arn:aws:s3:::state/*" };
  const deny = { Effect: "Deny", Action: "s3:GetObject", Resource: "arn:aws:s3:::state/prod/*" };
  for (const statements of [
    [allow, deny],
    [deny, allow],
  ]) {
    assert(!iamPermission(policy(statements), "s3:GetObject", "arn:aws:s3:::state/prod/state"));
    assert(iamPermission(policy(statements), "s3:GetObject", "arn:aws:s3:::state/dev/state"));
  }
});

test("ARN and boolean conditions reject a different cluster or remote execution request", () => {
  const policies = policy([
    {
      Effect: "Allow",
      Action: "ecs:RunTask",
      Resource: "arn:aws:ecs:eu-west-2:123456789012:task-definition/media:*",
      Condition: {
        ArnEquals: { "ecs:cluster": "arn:aws:ecs:eu-west-2:123456789012:cluster/dev" },
        Bool: { "ecs:enable-execute-command": "false" },
      },
    },
  ]);
  const resource = "arn:aws:ecs:eu-west-2:123456789012:task-definition/media:1";
  const context = {
    "ecs:cluster": "arn:aws:ecs:eu-west-2:123456789012:cluster/dev",
    "ecs:enable-execute-command": false,
  };
  assert(iamPermission(policies, "ecs:RunTask", resource, context));
  assert(
    !iamPermission(policies, "ecs:RunTask", resource, {
      ...context,
      "ecs:enable-execute-command": true,
    }),
  );
  assert(
    !iamPermission(policies, "ecs:RunTask", resource, {
      ...context,
      "ecs:cluster": "arn:aws:ecs:eu-west-2:123456789012:cluster/prod",
    }),
  );
  assert(
    !iamPermission(policies, "ecs:RunTask", resource, { "ecs:cluster": context["ecs:cluster"] }),
  );
});

test("StringLike confines bucket listing to a complete wardrobe image prefix", () => {
  const policies = policy([
    {
      Effect: "Allow",
      Action: "s3:ListBucket",
      Resource: "arn:aws:s3:::media",
      Condition: { StringLike: { "s3:prefix": "users/*/garments/*/images/*/" } },
    },
  ]);
  assert(
    iamPermission(policies, "s3:ListBucket", "arn:aws:s3:::media", {
      "s3:prefix": "users/owner/garments/garment/images/photo/",
    }),
  );
  for (const prefix of ["", "users/", "users/owner/garments/"]) {
    assert(
      !iamPermission(policies, "s3:ListBucket", "arn:aws:s3:::media", { "s3:prefix": prefix }),
    );
  }
});

test("unsupported conditions and inverse policy elements fail instead of granting access", () => {
  const entry = { Effect: "Allow", Action: "s3:GetObject", Resource: "arn:aws:s3:::state/*" };
  assert.throws(
    () =>
      iamPermission(
        policy([{ ...entry, Condition: { UnknownOperator: { key: "value" } } }]),
        "s3:GetObject",
        "arn:aws:s3:::state/dev/state",
      ),
    /Unsupported/,
  );
  for (const forbidden of ["NotAction", "NotResource", "Principal"]) {
    assert.throws(() =>
      iamPermission(
        policy([{ ...entry, [forbidden]: "*" }]),
        "s3:GetObject",
        "arn:aws:s3:::state/dev/state",
      ),
    );
  }
});
