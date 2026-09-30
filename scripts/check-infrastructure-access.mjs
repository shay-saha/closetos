import assert from "node:assert/strict";
import { iamPermission } from "./check-iam-permissions.mjs";

const array = (value) => (Array.isArray(value) ? value : [value]);
export function checkInfrastructureAccess(resources) {
  const at = (address) => {
    const resource = resources.find((value) => value.address === address);
    assert(resource, `Missing rendered infrastructure prerequisite ${address}`);
    return resource.values;
  };
  const role = at("aws_iam_role.github_infrastructure[0]");
  const [, application, environment] = /^(.+)-(dev|prod)-github-infrastructure$/.exec(role.name);
  const name = `${application}-${environment}`;
  const foreign = `${application}-${environment === "dev" ? "prod" : "dev"}`;
  const reads = resources.filter((value) =>
    value.address.startsWith("aws_iam_policy.github_configuration_read["),
  );
  const writes = resources.filter((value) =>
    value.address.startsWith("aws_iam_policy.github_infrastructure["),
  );
  assert.equal(reads.length, 2);
  assert.equal(writes.length, 8);
  for (const resource of [...reads, ...writes]) assert(resource.values.policy.length <= 6144);
  const inline = at("aws_iam_role_policy.github_infrastructure_guardrails[0]");
  assert(inline.policy.length <= 10240);
  assert.equal(
    resources.filter((value) =>
      /^aws_iam_role_policy_attachment.github_infrastructure_(read|write)\[/.test(value.address),
    ).length,
    10,
  );
  const policies = [...reads, ...writes].map((value) => JSON.parse(value.values.policy));
  policies.push(JSON.parse(inline.policy));
  const cluster = policies
    .flatMap((value) => value.Statement)
    .find((value) => array(value.Action).includes("ecs:UpdateCluster")).Resource;
  const [, region, account] = /^arn:aws:ecs:([^:]+):([0-9]{12}):cluster\//.exec(cluster);
  const trust = JSON.parse(role.assume_role_policy).Statement;
  assert.equal(trust.length, 1);
  assert.equal(trust[0].Action, "sts:AssumeRoleWithWebIdentity");
  assert.equal(
    trust[0].Principal.Federated,
    `arn:aws:iam::${account}:oidc-provider/token.actions.githubusercontent.com`,
  );
  assert.equal(
    trust[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"],
    "sts.amazonaws.com",
  );
  const subject = trust[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"];
  assert.match(
    subject,
    environment === "prod"
      ? /^repo:[\w-]+@[1-9][0-9]*\/[\w.-]+@[1-9][0-9]*:environment:production$/
      : /^repo:[\w-]+@[1-9][0-9]*\/[\w.-]+@[1-9][0-9]*:ref:refs\/heads\/main$/,
  );
  assert.equal(role.max_session_duration, 3600);
  const owned = {
    "aws:ResourceTag/Application": application,
    "aws:ResourceTag/Environment": environment,
    "aws:RequestedRegion": region,
  };
  const requested = {
    "aws:RequestTag/Application": application,
    "aws:RequestTag/Environment": environment,
    "aws:RequestedRegion": region,
  };
  const foreignTags = {
    ...owned,
    "aws:ResourceTag/Environment": environment === "dev" ? "prod" : "dev",
  };
  let checks = 0;
  const expect = (action, resource, permitted, context = owned, additional = []) => {
    assert.equal(
      iamPermission([...policies, ...additional], action, resource, context),
      permitted,
      `Infrastructure ${action} ${resource} with ${JSON.stringify(context)}`,
    );
    checks++;
  };
  const grantAll = [
    { Version: "2012-10-17", Statement: [{ Effect: "Allow", Action: "*", Resource: "*" }] },
  ];
  const state = `arn:aws:s3:::${name}-${account}-state/${environment}/closetos.tfstate`;
  for (const action of ["s3:GetObject", "s3:PutObject"]) expect(action, state, true);
  expect("s3:DeleteObject", state, false);
  for (const action of ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]) {
    expect(action, `${state}.tflock`, true);
    for (const resource of [
      state.replace(name, foreign),
      `${state}.backup`,
      `arn:aws:s3:::${name}-${account}-media/users/owner/photo.jpg`,
    ]) {
      expect(action, resource, false, owned, grantAll);
    }
  }
  for (const suffix of [
    "api-task",
    "api-execution",
    "web-task",
    "web-execution",
    "media-worker-task",
    "media-worker-execution",
    "migration-task",
    "migration-execution",
    "media-workflow",
  ]) {
    const resource = `arn:aws:iam::${account}:role/${name}-${suffix}`;
    const boundary = resource.replace(":role/", ":policy/") + "-permissions-boundary";
    const context = { ...requested, "iam:PermissionsBoundary": boundary };
    for (const action of [
      "iam:CreateRole",
      "iam:PutRolePermissionsBoundary",
      "iam:PutRolePolicy",
      "iam:DeleteRolePolicy",
      "iam:UpdateAssumeRolePolicy",
      "iam:UpdateRole",
    ]) {
      expect(action, resource, true, context);
      expect(action, resource, false, requested);
      expect(action, resource, false, {
        ...context,
        "iam:PermissionsBoundary": boundary.replace(name, foreign),
      });
      expect(action, resource, false, {
        ...context,
        "iam:PermissionsBoundary": boundary.replace(
          suffix,
          suffix === "api-task" ? "web-task" : "api-task",
        ),
      });
      expect(action, resource.replace(name, foreign), false, context);
    }
    expect("iam:PassRole", resource, true, {
      "iam:PassedToService":
        suffix === "media-workflow" ? "states.amazonaws.com" : "ecs-tasks.amazonaws.com",
    });
    expect("iam:PassRole", resource, false, { "iam:PassedToService": "lambda.amazonaws.com" });
    expect("iam:DeleteRolePermissionsBoundary", resource, false, {}, grantAll);
  }
  for (const action of [
    "iam:CreateRole",
    "iam:PutRolePolicy",
    "iam:UpdateAssumeRolePolicy",
    "iam:PassRole",
  ])
    expect(action, `arn:aws:iam::${account}:role/${name}-github-infrastructure`, false, requested);
  for (const action of ["iam:CreatePolicyVersion", "iam:SetDefaultPolicyVersion"])
    expect(
      action,
      `arn:aws:iam::${account}:policy/${name}-api-task-permissions-boundary`,
      false,
      {},
      grantAll,
    );
  for (const action of [
    "iam:CreatePolicy",
    "iam:DeletePolicy",
    "iam:CreateOpenIDConnectProvider",
    "iam:AttachRolePolicy",
  ])
    expect(action, "*", false);
  for (const [service, suffix] of [
    ["ecs.amazonaws.com", "AWSServiceRoleForECS"],
    ["elasticloadbalancing.amazonaws.com", "AWSServiceRoleForElasticLoadBalancing"],
    [
      "ecs.application-autoscaling.amazonaws.com",
      "AWSServiceRoleForApplicationAutoScaling_ECSService",
    ],
  ]) {
    const resource = `arn:aws:iam::${account}:role/aws-service-role/${service}/${suffix}`;
    expect("iam:CreateServiceLinkedRole", resource, true, { "iam:AWSServiceName": service });
    expect("iam:CreateServiceLinkedRole", resource, false, {
      "iam:AWSServiceName": "other.amazonaws.com",
    });
    expect("iam:CreateServiceLinkedRole", resource.replace(account, "999999999999"), false, {
      "iam:AWSServiceName": service,
    });
  }
  for (const family of ["api", "web", "media", "migration"]) {
    const task = `arn:aws:ecs:${region}:${account}:task-definition/${name}-${family}:42`;
    expect("ecs:RegisterTaskDefinition", task, true, requested);
    expect("ecs:RegisterTaskDefinition", task, false, {});
    expect("ecs:RegisterTaskDefinition", task.replace(name, foreign), false, requested);
    for (const action of [
      "ecs:RunTask",
      "ecs:DeregisterTaskDefinition",
      "ecs:DeleteTaskDefinitions",
    ])
      expect(action, task, false, {}, grantAll);
  }
  for (const service of ["api", "web"]) {
    const resource = `arn:aws:ecs:${region}:${account}:service/${name}/${name}-${service}`;
    const task = `arn:aws:ecs:${region}:${account}:task-definition/${name}-${service}:42`;
    const context = {
      ...requested,
      "ecs:task-definition": task,
      "ecs:enable-execute-command": false,
    };
    expect("ecs:CreateService", resource, true, context);
    expect("ecs:CreateService", resource, false, requested);
    expect("ecs:UpdateService", resource, true, {});
    for (const action of ["ecs:CreateService", "ecs:UpdateService"]) {
      expect(action, resource, true, context);
      expect(action, resource, false, { ...context, "ecs:enable-execute-command": true });
      expect(action, resource, false, {
        ...context,
        "ecs:task-definition": task.replace(name, foreign),
      });
      expect(action, resource.replaceAll(name, foreign), false, context);
    }
    expect("ecs:ExecuteCommand", resource, false, {}, grantAll);
  }
  const network = [
    ["vpc", "ec2:CreateVpc", "ec2:ModifyVpcAttribute"],
    ["subnet", "ec2:CreateSubnet", "ec2:ModifySubnetAttribute"],
    ["internet-gateway", "ec2:CreateInternetGateway", "ec2:AttachInternetGateway"],
    ["route-table", "ec2:CreateRouteTable", "ec2:CreateRoute"],
    ["elastic-ip", "ec2:AllocateAddress", "ec2:ReleaseAddress"],
    ["natgateway", "ec2:CreateNatGateway", "ec2:DeleteNatGateway"],
    ["vpc-endpoint", "ec2:CreateVpcEndpoint", "ec2:ModifyVpcEndpoint"],
    ["security-group", "ec2:CreateSecurityGroup", "ec2:DeleteSecurityGroup"],
    ["security-group-rule", "ec2:AuthorizeSecurityGroupIngress", "ec2:ModifySecurityGroupRules"],
  ];
  for (const [type, create, update] of network) {
    const resource = `arn:aws:ec2:${region}:${account}:${type}/example`;
    expect(create, resource, true, requested);
    expect(create, resource, false, {});
    expect(update, resource, true);
    expect(update, resource, false, foreignTags);
    expect(update, resource, false, {});
    expect(update, resource.replace(account, "999999999999"), false);
    expect("ec2:CreateTags", resource, true, {
      ...requested,
      "ec2:CreateAction": create.split(":")[1],
    });
    expect("ec2:CreateTags", resource, false, requested);
  }
  const tagged = [
    ["cloudfront:UpdateDistribution", `arn:aws:cloudfront::${account}:distribution/123`],
    [
      "cognito-idp:UpdateUserPool",
      `arn:aws:cognito-idp:${region}:${account}:userpool/${region}_123`,
    ],
    ["acm:DeleteCertificate", `arn:aws:acm:${region}:${account}:certificate/123`],
    ["kms:PutKeyPolicy", `arn:aws:kms:${region}:${account}:key/123`],
    [
      "servicediscovery:UpdateService",
      `arn:aws:servicediscovery:${region}:${account}:service/srv-123`,
    ],
  ];
  for (const [action, resource] of tagged) {
    expect(action, resource, true);
    expect(action, resource, false, {});
    expect(action, resource, false, foreignTags);
    expect(action, resource.replace(account, "999999999999"), false);
  }
  const alias = `arn:aws:kms:${region}:${account}:alias/${name}-queues`;
  expect("kms:CreateAlias", alias, true);
  expect("kms:CreateAlias", alias.replace(name, foreign), false);
  const namespace = `arn:aws:servicediscovery:${region}:${account}:namespace/ns-123`;
  const discovered = namespace.replace("namespace/ns", "service/srv");
  expect("servicediscovery:CreateService", discovered, true, requested);
  expect("servicediscovery:CreateService", namespace, true);
  expect("servicediscovery:CreateService", namespace, false, requested);
  expect("servicediscovery:CreateService", namespace, false, foreignTags);
  const target = `arn:aws:application-autoscaling:${region}:${account}:scalable-target/123`;
  const scaling = {
    "application-autoscaling:service-namespace": "ecs",
    "application-autoscaling:scalable-dimension": "ecs:service:DesiredCount",
  };
  for (const action of [
    "application-autoscaling:RegisterScalableTarget",
    "application-autoscaling:PutScalingPolicy",
    "application-autoscaling:DeleteScalingPolicy",
    "application-autoscaling:DeregisterScalableTarget",
  ]) {
    expect(action, target, true, { ...owned, ...scaling });
    expect(action, target, false, { ...foreignTags, ...scaling });
    expect(action, target, false, {
      ...owned,
      ...scaling,
      "application-autoscaling:service-namespace": "dynamodb",
    });
    expect(action, target, false, {
      ...owned,
      ...scaling,
      "application-autoscaling:scalable-dimension": "dynamodb:table:ReadCapacityUnits",
    });
  }
  expect("application-autoscaling:RegisterScalableTarget", target, true, {
    ...requested,
    ...scaling,
  });
  expect("application-autoscaling:TagResource", target, true, { ...requested, ...owned });
  expect("application-autoscaling:TagResource", target, true, requested);
  expect("application-autoscaling:TagResource", target, false, { ...requested, ...foreignTags });
  expect("application-autoscaling:UntagResource", target, true, {
    ...owned,
    "aws:TagKeys": ["Name"],
  });
  expect("application-autoscaling:UntagResource", target, false, { "aws:TagKeys": ["Name"] });
  const dns = policies
    .flatMap((value) => value.Statement)
    .find((value) => array(value.Action).includes("route53:ChangeResourceRecordSets"));
  const domain =
    dns.Condition["ForAllValues:StringLike"][
      "route53:ChangeResourceRecordSetsNormalizedRecordNames"
    ][0];
  const dnsContext = {
    "route53:ChangeResourceRecordSetsNormalizedRecordNames": [domain, `_validation.${domain}`],
    "route53:ChangeResourceRecordSetsRecordTypes": ["A", "CNAME"],
    "route53:ChangeResourceRecordSetsActions": ["UPSERT"],
  };
  expect("route53:ChangeResourceRecordSets", dns.Resource, true, dnsContext);
  expect(
    "route53:ChangeResourceRecordSets",
    dns.Resource.replace(/[^/]+$/, "ZFOREIGN"),
    false,
    dnsContext,
  );
  for (const [key, bad] of Object.entries({
    "route53:ChangeResourceRecordSetsNormalizedRecordNames": [domain, "foreign.example.test"],
    "route53:ChangeResourceRecordSetsRecordTypes": ["A", "TXT"],
    "route53:ChangeResourceRecordSetsActions": ["UPSERT", "INVALID"],
  })) {
    expect("route53:ChangeResourceRecordSets", dns.Resource, false, { ...dnsContext, [key]: bad });
    const missing = { ...dnsContext };
    delete missing[key];
    expect("route53:ChangeResourceRecordSets", dns.Resource, false, missing);
  }
  expect("acm:RequestCertificate", "*", true, { ...requested, "acm:DomainNames": [domain] });
  for (const domains of [undefined, [], [domain, "foreign.example.test"], [`*.${domain}`]])
    expect("acm:RequestCertificate", "*", false, { ...requested, "acm:DomainNames": domains });
  const vpc = at("data.aws_vpc.infrastructure[0]").id;
  expect("route53:CreateHostedZone", "*", true, {
    "route53:VPCs": [`VPCId=${vpc},VPCRegion=${region}`],
  });
  for (const context of [
    {},
    { "route53:VPCs": [] },
    { "route53:VPCs": [`VPCId=vpc-foreign,VPCRegion=${region}`] },
    { "route53:VPCs": [`VPCId=${vpc},VPCRegion=us-west-1`] },
  ])
    expect("route53:CreateHostedZone", "*", false, context);
  for (const action of [
    "route53:DeleteHostedZone",
    "cloudfront:UpdatePublicKey",
    "cloudfront:UpdateKeyGroup",
    "cognito-idp:ListUsers",
    "cognito-idp:AdminGetUser",
    "cognito-idp:AdminInitiateAuth",
  ])
    expect(action, "*", false);
  for (const [action, resource] of [
    [
      "secretsmanager:GetSecretValue",
      `arn:aws:secretsmanager:${region}:${account}:secret:${name}/database-app-ABC123`,
    ],
    ["secretsmanager:PutSecretValue", "*"],
    ["secretsmanager:UpdateSecret", "*"],
    ["secretsmanager:PutResourcePolicy", "*"],
    ["kms:Decrypt", `arn:aws:kms:${region}:${account}:key/123`],
    ["kms:Encrypt", "*"],
    ["sqs:ReceiveMessage", `arn:aws:sqs:${region}:${account}:${name}-media-results`],
    ["sqs:SendMessage", "*"],
    ["sqs:PurgeQueue", "*"],
    ["states:StartExecution", `arn:aws:states:${region}:${account}:stateMachine:${name}-media`],
    ["sts:AssumeRole", "*"],
  ])
    expect(action, resource, false, owned, grantAll);
  const media = `arn:aws:s3:::${name}-${account}-media`;
  expect("s3:PutBucketPolicy", media, true);
  expect("s3:PutBucketPolicy", media.replace(name, foreign), false);
  for (const [action, resource] of [
    ["rds:ModifyDBInstance", `arn:aws:rds:${region}:${account}:db:${name}`],
    ["sqs:SetQueueAttributes", `arn:aws:sqs:${region}:${account}:${name}-media-ingest`],
    ["ecr:PutLifecyclePolicy", `arn:aws:ecr:${region}:${account}:repository/${name}/api`],
  ]) {
    expect(action, resource, true);
    expect(action, resource.replace(name, foreign), false);
  }
  const guard = JSON.parse(inline.policy).Statement;
  const retag = guard.find((entry) => entry.Condition?.StringNotEquals);
  const untag = guard.find((entry) => entry.Condition?.["ForAnyValue:StringEquals"]);
  const appRole = `arn:aws:iam::${account}:role/${name}-api-task`;
  expect("iam:TagRole", appRole, true, { "aws:RequestTag/Name": "api" });
  for (const action of retag.Action) {
    expect(action, "*", false, { "aws:RequestTag/Application": "foreign" }, grantAll);
    expect(
      action,
      "*",
      false,
      { "aws:RequestTag/Environment": environment === "dev" ? "prod" : "dev" },
      grantAll,
    );
  }
  for (const action of untag.Action)
    for (const key of ["Application", "Environment"])
      expect(action, "*", false, { "aws:TagKeys": ["Name", key] }, grantAll);
  expect("iam:UntagRole", appRole, true, { "aws:TagKeys": ["Name"] });
  return checks;
}
