import assert from "node:assert/strict";

const array = (value) => (Array.isArray(value) ? value : [value]);
const matches = (pattern, value, insensitive = false) => {
  const expression = pattern
    .replace(/[.+^${}()|[\]\\]/g, "\\$&")
    .replaceAll("*", ".*")
    .replaceAll("?", ".");
  return new RegExp(`^${expression}$`, insensitive ? "i" : "").test(value);
};

export function planningPermission(policies, action, resource, context = {}) {
  let allowed = false;
  for (const policy of policies) {
    for (const statement of policy.Statement) {
      assert(!statement.NotAction && !statement.NotResource && !statement.Principal);
      if (!array(statement.Action).some((pattern) => matches(pattern, action, true))) continue;
      if (!array(statement.Resource).some((pattern) => matches(pattern, resource))) continue;
      const conditions = Object.entries(statement.Condition ?? {});
      if (
        !conditions.every(([operator, keys]) => {
          assert.equal(operator, "StringEquals", "Unsupported planning policy condition");
          return Object.entries(keys).every(([key, values]) =>
            array(values).some((value) => value === context[key]),
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

export function checkPlanningAccess(resources) {
  const role = resources.find((resource) => resource.address === "aws_iam_role.github_planner[0]");
  assert(role, "Missing rendered infrastructure planner role");
  const [, application, environment] = /^(.+)-(dev|prod)-github-planner$/.exec(role.values.name);
  const trust = JSON.parse(role.values.assume_role_policy).Statement[0];
  assert.equal(trust.Action, "sts:AssumeRoleWithWebIdentity");
  assert.equal(
    trust.Condition.StringEquals["token.actions.githubusercontent.com:aud"],
    "sts.amazonaws.com",
  );
  assert.match(
    trust.Condition.StringEquals["token.actions.githubusercontent.com:sub"],
    /^repo:[A-Za-z0-9-]+@[1-9][0-9]*\/[A-Za-z0-9_.-]+@[1-9][0-9]*:ref:refs\/heads\/main$/,
  );
  const readResources = resources.filter((resource) =>
    resource.address.startsWith("aws_iam_policy.github_configuration_read["),
  );
  assert.equal(readResources.length, 2);
  const configuration = readResources.map((resource) => JSON.parse(resource.values.policy));
  const stateResource = resources.find(
    (resource) => resource.address === "aws_iam_role_policy.github_planner_state[0]",
  );
  assert(stateResource, "Missing rendered planner state permissions");
  const state = JSON.parse(stateResource.values.policy);
  const policies = [...configuration, state];
  const statements = configuration.flatMap((policy) => policy.Statement);
  const statement = (sid) => {
    const result = statements.find((value) => value.Sid === sid);
    assert(result, `Missing planner prerequisite ${sid}`);
    return result;
  };
  const [, region, account, name] = /^arn:aws:ecs:([^:]+):([0-9]{12}):cluster\/(.+)$/.exec(
    statement("InspectApplicationCluster").Resource,
  );
  assert.equal(name, `${application}-${environment}`);
  assert.equal(
    trust.Principal.Federated,
    `arn:aws:iam::${account}:oidc-provider/token.actions.githubusercontent.com`,
  );
  const foreignEnvironment = environment === "dev" ? "prod" : "dev";
  const otherName = `${application}-${foreignEnvironment}`;
  const context = {
    "aws:RequestedRegion": region,
    "aws:ResourceTag/Application": application,
    "aws:ResourceTag/Environment": environment,
  };
  let checks = 0;
  const expect = (action, resource, expected, suppliedContext = context) => {
    assert.equal(
      planningPermission(policies, action, resource, suppliedContext),
      expected,
      `Planner ${action} ${resource} with ${JSON.stringify(suppliedContext)}`,
    );
    checks++;
  };

  for (const policy of policies) {
    assert.equal(policy.Version, "2012-10-17");
    for (const entry of policy.Statement) {
      assert.equal(entry.Effect, "Allow");
      for (const action of array(entry.Action)) {
        assert(!action.includes("*") && !action.includes("?"), "Planner actions must be explicit");
        assert(
          /^[a-z0-9-]+:(Get|Describe|List|View)[A-Za-z]+$/.test(action) ||
            (entry.Sid === "HoldOnlyTheEnvironmentPlanLock" &&
              ["s3:PutObject", "s3:DeleteObject"].includes(action)),
          `Unexpected planner mutation ${action}`,
        );
      }
    }
  }

  const bucket = `arn:aws:s3:::${name}-${account}-state`;
  const stateArn = `${bucket}/${environment}/closetos.tfstate`;
  const lockArn = `${stateArn}.tflock`;
  expect("s3:GetObject", stateArn, true);
  expect("s3:PutObject", stateArn, false);
  expect("s3:DeleteObject", stateArn, false);
  for (const action of ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]) {
    expect(action, lockArn, true);
    expect(action, `${bucket}/${foreignEnvironment}/closetos.tfstate.tflock`, false);
    expect(action, `${bucket}/${environment}/closetos.tfstate.backup`, false);
    expect(
      action,
      `arn:aws:s3:::${otherName}-${account}-state/${foreignEnvironment}/closetos.tfstate`,
      false,
    );
  }
  expect("s3:ListBucket", bucket, true, { "s3:prefix": `${environment}/closetos.tfstate` });
  expect("s3:ListBucket", bucket, false, { "s3:prefix": "" });
  expect("s3:ListBucket", bucket, false, { "s3:prefix": `${foreignEnvironment}/closetos.tfstate` });
  expect("s3:ListBucket", bucket, false, {});

  const media = `arn:aws:s3:::${name}-${account}-media`;
  expect("s3:GetBucketPolicy", media, true);
  for (const action of ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]) {
    expect(action, `${media}/users/owner/garments/garment/images/photo/original.jpg`, false);
  }
  expect("s3:PutBucketPolicy", media, false);
  expect("s3:GetBucketPolicy", `arn:aws:s3:::${otherName}-${account}-media`, false);

  const secret = `arn:aws:secretsmanager:${region}:${account}:secret:${name}/database-app-ABC123`;
  expect("secretsmanager:DescribeSecret", secret, true);
  expect("secretsmanager:GetSecretValue", secret, false);
  expect("secretsmanager:PutSecretValue", secret, false);
  expect("secretsmanager:BatchGetSecretValue", "*", false);
  expect("secretsmanager:DescribeSecret", secret.replace(name, otherName), false);
  expect("secretsmanager:DescribeSecret", secret.replace("database-app", "extra-secret"), false);
  expect(
    "secretsmanager:DescribeSecret",
    `arn:aws:secretsmanager:${region}:${account}:secret:rds!db-master-ABC123`,
    false,
  );

  const appRole = `arn:aws:iam::${account}:role/${name}-api-task`;
  expect("iam:GetRole", appRole, true);
  expect("iam:GetRolePolicy", appRole, true);
  expect("iam:GetRole", appRole.replace(name, otherName), false);
  expect("iam:GetRole", `arn:aws:iam::${account}:role/${name}-github-infrastructure`, false);
  for (const action of [
    "iam:PutRolePolicy",
    "iam:UpdateAssumeRolePolicy",
    "iam:PassRole",
    "iam:CreateRole",
  ]) {
    expect(action, appRole, false);
  }

  const repository = `arn:aws:ecr:${region}:${account}:repository/${name}/api`;
  expect("ecr:DescribeRepositories", repository, true);
  expect("ecr:DescribeRepositories", repository.replace(name, otherName), false);
  expect("ecr:PutImage", repository, false);
  expect("ecr:BatchGetImage", repository, false);
  expect("ecr:GetAuthorizationToken", "*", false);
  const database = `arn:aws:rds:${region}:${account}:db:${name}`;
  expect("rds:DescribeDBInstances", database, true);
  expect("rds:DescribeDBInstances", database.replace(name, otherName), false);
  expect("rds:ModifyDBInstance", database, false);

  const tagged = [
    ["ec2:DescribeVpcAttribute", `arn:aws:ec2:${region}:${account}:vpc/vpc-123`],
    ["kms:DescribeKey", `arn:aws:kms:${region}:${account}:key/123`],
    [
      "servicediscovery:GetNamespace",
      `arn:aws:servicediscovery:${region}:${account}:namespace/ns-123`,
    ],
    [
      "cognito-idp:DescribeUserPool",
      `arn:aws:cognito-idp:${region}:${account}:userpool/${region}_123`,
    ],
    ["acm:DescribeCertificate", `arn:aws:acm:${region}:${account}:certificate/123`],
    ["cloudfront:GetDistribution", `arn:aws:cloudfront::${account}:distribution/123`],
  ];
  for (const [action, resource] of tagged) {
    expect(action, resource, true);
    expect(action, resource, false, {});
    expect(action, resource, false, {
      ...context,
      "aws:ResourceTag/Environment": foreignEnvironment,
    });
    expect(action, resource, false, { ...context, "aws:ResourceTag/Application": "another-app" });
    expect(action, resource.replace(account, "999999999999"), false);
  }
  expect("kms:Decrypt", `arn:aws:kms:${region}:${account}:key/123`, false);
  for (const action of [
    "cognito-idp:ListUsers",
    "cognito-idp:AdminGetUser",
    "cognito-idp:ListUsersInGroup",
  ]) {
    expect(action, `arn:aws:cognito-idp:${region}:${account}:userpool/${region}_123`, false);
  }
  for (const action of [
    "ec2:DescribeSubnets",
    "ec2:DescribeNetworkAcls",
    "ecs:DescribeTaskDefinition",
    "logs:DescribeLogGroups",
  ]) {
    expect(action, "*", true);
    expect(action, "*", false, { ...context, "aws:RequestedRegion": "ap-southeast-1" });
  }
  for (const service of ["api", "web"]) {
    const arn = `arn:aws:ecs:${region}:${account}:service/${name}/${name}-${service}`;
    expect("ecs:DescribeServices", arn, true);
    expect("ecs:DescribeServices", arn.replaceAll(name, otherName), false);
    expect("ecs:UpdateService", arn, false);
  }
  expect("ecs:RegisterTaskDefinition", "*", false);
  expect(
    "ecs:RunTask",
    `arn:aws:ecs:${region}:${account}:task-definition/${name}-migration:1`,
    false,
  );
  const machine = `arn:aws:states:${region}:${account}:stateMachine:${name}-media`;
  expect("states:DescribeStateMachine", machine, true);
  expect("states:UpdateStateMachine", machine, false);
  expect("states:StartExecution", machine, false);
  const queue = `arn:aws:sqs:${region}:${account}:${name}-media-results`;
  expect("sqs:GetQueueAttributes", queue, true);
  expect("sqs:ReceiveMessage", queue, false);
  expect("sqs:SendMessage", queue, false);
  expect(
    "logs:GetLogEvents",
    `arn:aws:logs:${region}:${account}:log-group:/ecs/${name}/api:log-stream:live`,
    false,
  );
  expect(
    "logs:ListTagsForResource",
    `arn:aws:logs:${region}:${account}:log-group:/aws/vendedlogs/states/${name}-media`,
    true,
  );
  expect(
    "logs:ListTagsForResource",
    `arn:aws:logs:${region}:${account}:log-group:/aws/vendedlogs/states/${name}-media:*`,
    true,
  );
  const zone = statement("InspectConfiguredPublicDnsZone").Resource;
  expect("route53:ListResourceRecordSets", zone, true);
  expect("route53:ListResourceRecordSets", "arn:aws:route53:::hostedzone/ZOTHER", false);
  expect("route53:ChangeResourceRecordSets", zone, false);
  const budget = `arn:aws:budgets::${account}:budget/${name}-monthly-account-spend`;
  expect("budgets:ViewBudget", budget, true);
  expect("budgets:ModifyBudget", budget, false);
  return checks;
}
