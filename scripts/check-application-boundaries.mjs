import assert from "node:assert/strict";
import { iamPermission } from "./check-iam-permissions.mjs";

const array = (value) => (Array.isArray(value) ? value : [value]);
const sample = (value) => value.replaceAll("*", "example").replaceAll("?", "X");
const roles = [
  "api-task",
  "api-execution",
  "web-task",
  "web-execution",
  "media-worker-task",
  "media-worker-execution",
  "migration-task",
  "migration-execution",
  "media-workflow",
];

export function checkApplicationBoundaries(resources) {
  const policies = resources.filter((resource) =>
    resource.address.startsWith("aws_iam_policy.application_permissions_boundary["),
  );
  assert.equal(policies.length, 9, "Render separate boundaries for all application roles");
  const boundaries = Object.fromEntries(
    policies.map((resource) => {
      const [, role] = /\["([^"]+)"\]$/.exec(resource.address);
      assert(
        resource.values.policy.length <= 6144,
        "Boundary exceeds the AWS managed-policy size limit",
      );
      return [role, { name: resource.values.name, policy: JSON.parse(resource.values.policy) }];
    }),
  );
  assert.deepEqual(Object.keys(boundaries).sort(), [...roles].sort());
  const runTask = boundaries["media-workflow"].policy.Statement.find((statement) =>
    array(statement.Action).includes("ecs:RunTask"),
  );
  const [, region, account, name] = /^arn:aws:ecs:([^:]+):([0-9]{12}):cluster\/(.+)$/.exec(
    runTask.Condition.ArnEquals["ecs:cluster"],
  );
  const [, application, environment] = /^(.+)-(dev|prod)$/.exec(name);
  const otherName = `${application}-${environment === "dev" ? "prod" : "dev"}`;
  const context = {
    "aws:ResourceTag/Application": application,
    "aws:ResourceTag/Environment": environment,
    "aws:ResourceTag/ClosetosWorker": "media",
    "aws:RequestTag/Application": application,
    "aws:RequestTag/Environment": environment,
    "aws:RequestTag/ClosetosWorker": "media",
    "ecs:CreateAction": "RunTask",
    "ecs:cluster": `arn:aws:ecs:${region}:${account}:cluster/${name}`,
    "iam:PassedToService": "ecs-tasks.amazonaws.com",
    "s3:prefix": "users/owner/garments/garment/images/photo/",
  };
  let checks = 0;
  const expect = (role, action, resource, expected, suppliedContext = context) => {
    assert.equal(
      iamPermission([boundaries[role].policy], action, resource, suppliedContext),
      expected,
      `${role}: ${action} ${resource}`,
    );
    checks++;
  };
  const bucket = `arn:aws:s3:::${name}-${account}-media`;
  const original = `${bucket}/users/owner/garments/garment/images/photo/original.jpg`;
  const derivative = `${bucket}/users/owner/garments/garment/images/photo/pipelines/v1/display.webp`;
  const secret = (key) =>
    `arn:aws:secretsmanager:${region}:${account}:secret:${name}/${key}-ABC123`;
  const master = resources.find(
    (resource) => resource.address === "data.aws_db_instance.boundary_database[0]",
  )?.values.master_user_secret?.[0]?.secret_arn;
  assert(master, "Bind migration startup to native RDS secret metadata");
  const roleArn = (role) => `arn:aws:iam::${account}:role/${name}-${role}`;
  const machine = `arn:aws:states:${region}:${account}:stateMachine:${name}-media`;
  const queue = (key) => `arn:aws:sqs:${region}:${account}:${name}-${key}`;
  const key = `arn:aws:kms:${region}:${account}:key/example`;
  for (const role of roles) {
    assert.equal(boundaries[role].name, `${name}-${role}-permissions-boundary`);
    assert.equal(boundaries[role].policy.Version, "2012-10-17");
    for (const statement of boundaries[role].policy.Statement) {
      assert(
        array(statement.Resource).every(
          (resource) => typeof resource === "string" && resource.length,
        ),
      );
      assert(!statement.NotAction && !statement.NotResource && !statement.Principal);
      if (statement.Effect === "Deny") {
        assert(["web-task", "migration-task"].includes(role));
        assert.deepEqual(statement, { Effect: "Deny", Action: "*", Resource: "*" });
      } else {
        assert.equal(statement.Effect, "Allow");
        assert(
          array(statement.Action).every((action) => !action.includes("*") && !action.includes("?")),
        );
      }
    }
    for (const action of [
      "iam:CreateRole",
      "iam:PutRolePolicy",
      "iam:AttachRolePolicy",
      "iam:UpdateAssumeRolePolicy",
    ]) {
      expect(role, action, roleArn(role), false);
    }
    expect(role, "s3:PutBucketPolicy", bucket, false);
    expect(
      role,
      "ecs:UpdateService",
      `arn:aws:ecs:${region}:${account}:service/${name}/${name}-api`,
      false,
    );
    expect(role, "states:UpdateStateMachine", machine, false);
    expect(role, "secretsmanager:PutSecretValue", secret("database-app"), false);
    expect(role, "secretsmanager:GetSecretValue", master, role === "migration-execution");
    expect(role, "secretsmanager:GetSecretValue", master.replace(account, "999999999999"), false);
    expect(role, "secretsmanager:GetSecretValue", secret("unconfigured"), false);
    expect(role, "s3:GetObject", original.replace(name, otherName), false);
    expect(
      role,
      "bedrock:InvokeModel",
      `arn:aws:bedrock:${region}::foundation-model/unconfigured.model`,
      false,
    );
    expect(role, "sqs:SetQueueAttributes", queue("media-results"), false);
    expect(role, "kms:Decrypt", key, ["api-task", "media-workflow"].includes(role));
    expect(role, "kms:Decrypt", key, false, {});
    expect(role, "kms:Decrypt", key, false, {
      ...context,
      "aws:ResourceTag/Environment": "another-environment",
    });
  }
  for (const action of ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]) {
    expect("api-task", action, original, true);
    expect("api-task", action, `${bucket}/outside-wardrobes.jpg`, false);
  }
  expect("api-task", "s3:ListBucket", bucket, true);
  expect("api-task", "s3:ListBucket", bucket, false, { ...context, "s3:prefix": "users/" });
  expect("api-task", "states:StartExecution", machine, true);
  expect("api-task", "states:StartExecution", machine.replace(name, otherName), false);
  expect("api-task", "sqs:ReceiveMessage", queue("media-results"), true);
  expect("api-task", "sqs:ReceiveMessage", queue("media-results-dlq"), false);
  expect("media-worker-task", "s3:GetObject", original, true);
  expect("media-worker-task", "s3:GetObject", derivative, true);
  expect("media-worker-task", "s3:PutObject", derivative, true);
  expect("media-worker-task", "s3:PutObject", original, false);
  expect("media-worker-task", "s3:DeleteObject", derivative, false);
  expect("media-worker-task", "secretsmanager:GetSecretValue", secret("database-app"), false);
  expect("media-workflow", "s3:GetObject", original, false);
  for (const role of ["api-task", "media-worker-task"]) {
    const model = boundaries[role].policy.Statement.find((statement) =>
      array(statement.Action).includes("bedrock:InvokeModel"),
    );
    for (const arn of array(model.Resource)) expect(role, "bedrock:InvokeModel", arn, true);
  }
  const startupSecrets = {
    api: ["database-app", "media-signing"],
    web: ["web-session"],
    "media-worker": [],
    migration: ["database-app"],
  };
  for (const service of ["api", "web", "media-worker", "migration"]) {
    const role = `${service}-execution`;
    const image = service === "migration" ? "api" : service;
    const repository = `arn:aws:ecr:${region}:${account}:repository/${name}/${image}`;
    expect(role, "ecr:GetAuthorizationToken", "*", true);
    expect(role, "ecr:BatchGetImage", repository, true);
    expect(role, "ecr:BatchGetImage", repository.replace(name, otherName), false);
    expect(
      role,
      "ecr:BatchGetImage",
      `arn:aws:ecr:${region}:${account}:repository/${name}/unconfigured`,
      false,
    );
    expect(role, "ecr:PutImage", repository, false);
    expect(
      role,
      "logs:PutLogEvents",
      `arn:aws:logs:${region}:${account}:log-group:/ecs/${name}/${service}:log-stream:startup`,
      true,
    );
    expect(
      role,
      "logs:PutLogEvents",
      `arn:aws:logs:${region}:${account}:log-group:/ecs/${name}/other:log-stream:startup`,
      false,
    );
    for (const key of ["database-app", "media-signing", "web-session"]) {
      expect(
        role,
        "secretsmanager:GetSecretValue",
        secret(key),
        startupSecrets[service].includes(key),
      );
    }
    expect(role, "s3:GetObject", original, false);
  }
  const mediaTask = `arn:aws:ecs:${region}:${account}:task-definition/${name}-media:1`;
  expect("media-workflow", "ecs:RunTask", mediaTask, true);
  expect("media-workflow", "ecs:RunTask", mediaTask.replace("-media:", "-api:"), false);
  expect("media-workflow", "ecs:RunTask", mediaTask, false, {
    ...context,
    "ecs:cluster": `arn:aws:ecs:${region}:${account}:cluster/${otherName}`,
  });
  const task = `arn:aws:ecs:${region}:${account}:task/${name}/0123456789abcdef0123456789abcdef`;
  for (const role of roles) {
    expect(role, "ecs:StopTask", task, ["api-task", "media-workflow"].includes(role));
    expect(role, "ecs:StopTask", task, false, {});
    expect(role, "ecs:StopTask", task, false, {
      ...context,
      "aws:ResourceTag/ClosetosWorker": "api",
    });
    expect(role, "ecs:StopTask", task, false, {
      ...context,
      "aws:ResourceTag/Environment": "other",
    });
    expect(role, "ecs:StopTask", task, false, {
      ...context,
      "aws:ResourceTag/Application": "other",
    });
    expect(role, "ecs:StopTask", task.replace(name, otherName), false);
    expect(role, "ecs:StopTask", task.replace(account, "999999999999"), false);
    expect(role, "ecs:TagResource", task, role === "media-workflow");
    expect(role, "ecs:TagResource", task, false, {
      ...context,
      "ecs:CreateAction": "RegisterTaskDefinition",
    });
    expect(role, "ecs:TagResource", task, false, { ...context, "ecs:CreateAction": undefined });
    expect(role, "ecs:TagResource", task, false, {
      ...context,
      "aws:RequestTag/ClosetosWorker": "web",
    });
    expect(role, "ecs:UntagResource", task, false);
  }
  expect("api-task", "ecs:ListTasks", "*", true);
  expect("api-task", "ecs:ListTasks", "*", false, {});
  expect("api-task", "ecs:ListTasks", "*", false, {
    ...context,
    "ecs:cluster": context["ecs:cluster"].replace(name, otherName),
  });
  for (const action of [
    "states:DescribeExecution",
    "states:GetExecutionHistory",
    "states:StopExecution",
  ]) {
    const execution = machine.replace(":stateMachine:", ":execution:") + ":job";
    expect("api-task", action, execution, true);
    expect("api-task", action, execution.replace(name, otherName), false);
  }
  for (const role of ["media-worker-task", "media-worker-execution"]) {
    expect("media-workflow", "iam:PassRole", roleArn(role), true);
    expect("media-workflow", "iam:PassRole", roleArn(role), false, {
      ...context,
      "iam:PassedToService": "lambda.amazonaws.com",
    });
  }
  expect("media-workflow", "iam:PassRole", roleArn("api-task"), false);
  expect("media-workflow", "sqs:SendMessage", queue("media-results"), true);
  expect("media-workflow", "sqs:SendMessage", queue("media-ingest"), false);
  expect("media-workflow", "kms:GenerateDataKey", key, true);
  for (const role of ["web-task", "migration-task"]) {
    expect(role, "ecr:GetAuthorizationToken", "*", false);
    expect(role, "s3:GetObject", original, false);
    expect(role, "states:StartExecution", machine, false);
  }
  return { boundaries, context, name, account, region, checks };
}

export function checkBoundedApplicationRoles(resources, metadata) {
  assert(metadata, "Render the bootstrap boundaries before checking runtime-role permissions");
  const { boundaries, context, name, account } = metadata;
  const applicationRoles = resources.filter((resource) => resource.type === "aws_iam_role");
  assert.equal(applicationRoles.length, 9);
  for (const role of applicationRoles) {
    assert.equal(
      role.values.permissions_boundary,
      `arn:aws:iam::${account}:policy/${role.values.name}-permissions-boundary`,
    );
    assert(boundaries[role.values.name.slice(name.length + 1)], "Unknown application role");
  }
  let checks = 0;
  const identityPolicies = resources.filter((resource) => resource.type === "aws_iam_role_policy");
  assert.equal(identityPolicies.length, 7);
  for (const resource of identityPolicies) {
    const role =
      resource.address === "aws_iam_role_policy.api"
        ? "api-task"
        : resource.address === "aws_iam_role_policy.worker"
          ? "media-worker-task"
          : resource.address === "aws_iam_role_policy.workflow"
            ? "media-workflow"
            : resource.address === "aws_iam_role_policy.migration_execution"
              ? "migration-execution"
              : `${/\["([^"]+)"\]$/.exec(resource.address)[1]}-execution`;
    const policy = JSON.parse(resource.values.policy);
    if (["api-task", "media-workflow"].includes(role)) {
      const task = `arn:aws:ecs:${metadata.region}:${account}:task/${name}/0123456789abcdef0123456789abcdef`;
      for (const deniedContext of [
        {},
        { ...context, "aws:ResourceTag/ClosetosWorker": "api" },
        { ...context, "aws:ResourceTag/Environment": "another-environment" },
        { ...context, "aws:ResourceTag/Application": "another-application" },
      ]) {
        assert(
          !iamPermission([policy], "ecs:StopTask", task, deniedContext),
          `${role}: identity policy may stop only tagged media workers`,
        );
        checks++;
      }
      assert(
        !iamPermission([policy], "ecs:StopTask", task.replace(account, "999999999999"), context),
      );
      checks++;
      if (role === "media-workflow") {
        for (const createAction of [undefined, "RegisterTaskDefinition"]) {
          assert(
            !iamPermission([policy], "ecs:TagResource", task, {
              ...context,
              "ecs:CreateAction": createAction,
            }),
            "The workflow may tag tasks only during RunTask",
          );
          checks++;
        }
      }
    }
    for (const statement of policy.Statement) {
      assert.equal(statement.Effect, "Allow");
      const suppliedContext = { ...context };
      for (const keys of Object.values(statement.Condition ?? {})) {
        for (const [key, values] of Object.entries(keys))
          suppliedContext[key] = sample(String(array(values)[0]));
      }
      for (const action of array(statement.Action)) {
        for (const arn of array(statement.Resource)) {
          const resourceArn = arn === "*" ? "*" : sample(arn);
          assert(
            iamPermission([policy], action, resourceArn, suppliedContext),
            "The identity sample must represent a granted operation",
          );
          assert(
            iamPermission([boundaries[role].policy], action, resourceArn, suppliedContext),
            `${role}: boundary blocks required ${action} ${resourceArn}`,
          );
          checks++;
        }
      }
    }
  }
  return checks;
}
