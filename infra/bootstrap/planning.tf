variable "github_planning_enabled" {
  type        = bool
  default     = false
  description = "Enable main-branch infrastructure planning after the foundation and remote state have been initialized. Planning never changes application resources or state."
  validation {
    condition     = !var.github_planning_enabled || var.github_repository != null
    error_message = "Configure the immutable GitHub repository identity before enabling infrastructure planning."
  }
}

variable "github_route53_zone_id" {
  type        = string
  default     = null
  description = "Existing public hosted zone used by the application's infrastructure plans."
  validation {
    condition     = var.github_route53_zone_id == null ? !var.github_planning_enabled : can(regex("^Z[A-Z0-9]+$", var.github_route53_zone_id))
    error_message = "Provide the exact public hosted zone ID when infrastructure planning is enabled."
  }
}

locals {
  planning_name = "${var.application_name}-${var.environment}"
  planning_tags = {
    "aws:ResourceTag/Application" = var.application_name
    "aws:ResourceTag/Environment" = var.environment
  }
  application_roles = [for suffix in [
    "api-task", "api-execution", "web-task", "web-execution", "media-worker-task", "media-worker-execution",
    "migration-task", "migration-execution", "media-workflow"
  ] : "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.planning_name}-${suffix}"]
  planning_state_bucket = "arn:aws:s3:::${var.application_name}-${var.environment}-${data.aws_caller_identity.current.account_id}-state"
  planning_state_key    = "${var.environment}/closetos.tfstate"
  planning_media        = "arn:aws:s3:::${local.planning_name}-${data.aws_caller_identity.current.account_id}-media"
  configuration_read_policies = {
    foundation = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid    = "InspectRegionalNetworkConfiguration"
          Effect = "Allow"
          Action = [
            "ec2:DescribeAvailabilityZones", "ec2:DescribeVpcs", "ec2:DescribeInternetGateways", "ec2:DescribeSubnets",
            "ec2:DescribeRouteTables", "ec2:DescribeAddresses", "ec2:DescribeNatGateways", "ec2:DescribeVpcEndpoints",
            "ec2:DescribeSecurityGroups", "ec2:DescribeSecurityGroupRules", "ec2:DescribeNetworkAcls", "ec2:DescribeTags"
          ]
          Resource  = "*"
          Condition = { StringEquals = { "aws:RequestedRegion" = var.region } }
        },
        {
          Sid       = "InspectOwnVpcAttributes"
          Effect    = "Allow"
          Action    = ["ec2:DescribeVpcAttribute"]
          Resource  = "arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:vpc/*"
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid    = "InspectApplicationRoleConfiguration"
          Effect = "Allow"
          Action = [
            "iam:GetRole", "iam:GetRolePolicy", "iam:ListRolePolicies", "iam:ListAttachedRolePolicies", "iam:ListRoleTags"
          ]
          Resource = local.application_roles
        },
        {
          Sid    = "InspectMediaBucketConfiguration"
          Effect = "Allow"
          Action = [
            "s3:ListBucket", "s3:GetBucketLocation", "s3:GetBucketTagging", "s3:GetBucketAcl", "s3:GetBucketPolicy",
            "s3:GetBucketVersioning", "s3:GetBucketPublicAccessBlock", "s3:GetBucketOwnershipControls",
            "s3:GetEncryptionConfiguration", "s3:GetBucketCORS", "s3:GetLifecycleConfiguration", "s3:GetBucketNotification",
            "s3:GetBucketWebsite", "s3:GetAccelerateConfiguration", "s3:GetBucketRequestPayment", "s3:GetBucketLogging",
            "s3:GetReplicationConfiguration", "s3:GetBucketObjectLockConfiguration"
          ]
          Resource = local.planning_media
        },
        {
          Sid      = "InspectApplicationRegistries"
          Effect   = "Allow"
          Action   = ["ecr:DescribeRepositories", "ecr:GetLifecyclePolicy", "ecr:ListTagsForResource"]
          Resource = local.release_repositories
        },
        {
          Sid      = "InspectApplicationSecretMetadata"
          Effect   = "Allow"
          Action   = ["secretsmanager:DescribeSecret"]
          Resource = [for secret in ["database-app", "media-signing", "web-session"] : "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${local.planning_name}/${secret}-??????"]
        },
        {
          Sid      = "InspectApplicationDatabase"
          Effect   = "Allow"
          Action   = ["rds:DescribeDBInstances", "rds:DescribeDBSubnetGroups", "rds:DescribeDBParameterGroups", "rds:DescribeDBParameters", "rds:ListTagsForResource"]
          Resource = [for resource in ["db:${local.planning_name}", "subgrp:${local.planning_name}", "pg:${local.planning_name}-postgres18"] : "arn:aws:rds:${var.region}:${data.aws_caller_identity.current.account_id}:${resource}"]
        },
        {
          Sid       = "InspectApplicationEncryptionConfiguration"
          Effect    = "Allow"
          Action    = ["kms:DescribeKey", "kms:GetKeyPolicy", "kms:GetKeyRotationStatus", "kms:ListResourceTags"]
          Resource  = "arn:aws:kms:${var.region}:${data.aws_caller_identity.current.account_id}:key/*"
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid       = "InspectRegionalEncryptionAliasMetadata"
          Effect    = "Allow"
          Action    = ["kms:ListAliases"]
          Resource  = "*"
          Condition = { StringEquals = { "aws:RequestedRegion" = var.region } }
        },
        {
          Sid      = "InspectApplicationQueueConfiguration"
          Effect   = "Allow"
          Action   = ["sqs:GetQueueAttributes", "sqs:GetQueueUrl", "sqs:ListQueueTags"]
          Resource = [for queue in ["media-ingest", "media-ingest-dlq", "media-results", "media-results-dlq"] : "arn:aws:sqs:${var.region}:${data.aws_caller_identity.current.account_id}:${local.planning_name}-${queue}"]
        }
      ]
    })
    runtime = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Sid    = "InspectRegionalServiceConfiguration"
          Effect = "Allow"
          Action = [
            "ecs:DescribeTaskDefinition", "elasticloadbalancing:DescribeLoadBalancers", "elasticloadbalancing:DescribeLoadBalancerAttributes",
            "elasticloadbalancing:DescribeTargetGroups", "elasticloadbalancing:DescribeTargetGroupAttributes",
            "elasticloadbalancing:DescribeListeners", "elasticloadbalancing:DescribeRules", "elasticloadbalancing:DescribeTags",
            "application-autoscaling:DescribeScalableTargets", "application-autoscaling:DescribeScalingPolicies",
            "logs:DescribeLogGroups", "servicediscovery:ListTagsForResource", "cognito-idp:DescribeUserPoolDomain"
          ]
          Resource  = "*"
          Condition = { StringEquals = { "aws:RequestedRegion" = var.region } }
        },
        {
          Sid      = "InspectApplicationCluster"
          Effect   = "Allow"
          Action   = ["ecs:DescribeClusters", "ecs:ListTagsForResource"]
          Resource = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:cluster/${local.planning_name}"
        },
        {
          Sid      = "InspectApplicationServices"
          Effect   = "Allow"
          Action   = ["ecs:DescribeServices", "ecs:ListTagsForResource"]
          Resource = [for service in ["api", "web"] : "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${local.planning_name}/${local.planning_name}-${service}"]
        },
        {
          Sid      = "InspectApplicationTaskTags"
          Effect   = "Allow"
          Action   = ["ecs:ListTagsForResource"]
          Resource = [for family in ["api", "web", "media", "migration"] : "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.planning_name}-${family}:*"]
        },
        {
          Sid       = "InspectApplicationServiceDiscovery"
          Effect    = "Allow"
          Action    = ["servicediscovery:GetNamespace", "servicediscovery:GetService"]
          Resource  = [for type in ["namespace", "service"] : "arn:aws:servicediscovery:${var.region}:${data.aws_caller_identity.current.account_id}:${type}/*"]
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid    = "InspectApplicationLogTags"
          Effect = "Allow"
          Action = ["logs:ListTagsLogGroup", "logs:ListTagsForResource"]
          Resource = flatten([for group in ["/ecs/${local.planning_name}/*", "/aws/vendedlogs/states/${local.planning_name}-media"] : [
            "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:${group}",
            "arn:aws:logs:${var.region}:${data.aws_caller_identity.current.account_id}:log-group:${group}:*"
          ]])
        },
        {
          Sid       = "InspectApplicationIdentityConfiguration"
          Effect    = "Allow"
          Action    = ["cognito-idp:DescribeUserPool", "cognito-idp:DescribeUserPoolClient", "cognito-idp:GetUserPoolMfaConfig", "cognito-idp:GetGroup", "cognito-idp:ListTagsForResource"]
          Resource  = "arn:aws:cognito-idp:${var.region}:${data.aws_caller_identity.current.account_id}:userpool/*"
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid       = "InspectApplicationCertificate"
          Effect    = "Allow"
          Action    = ["acm:DescribeCertificate", "acm:ListTagsForCertificate"]
          Resource  = "arn:aws:acm:${var.region}:${data.aws_caller_identity.current.account_id}:certificate/*"
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid      = "InspectApplicationUploadRule"
          Effect   = "Allow"
          Action   = ["events:DescribeRule", "events:ListTargetsByRule", "events:ListTagsForResource"]
          Resource = "arn:aws:events:${var.region}:${data.aws_caller_identity.current.account_id}:rule/${local.planning_name}-uploaded-originals"
        },
        {
          Sid      = "InspectApplicationMediaWorkflow"
          Effect   = "Allow"
          Action   = ["states:DescribeStateMachine", "states:ListTagsForResource"]
          Resource = "arn:aws:states:${var.region}:${data.aws_caller_identity.current.account_id}:stateMachine:${local.planning_name}-media"
        },
        {
          Sid       = "InspectApplicationMediaDistribution"
          Effect    = "Allow"
          Action    = ["cloudfront:GetDistribution", "cloudfront:GetDistributionConfig", "cloudfront:ListTagsForResource"]
          Resource  = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:distribution/*"
          Condition = { StringEquals = local.planning_tags }
        },
        {
          Sid      = "InspectAccountOriginAccessControl"
          Effect   = "Allow"
          Action   = ["cloudfront:GetOriginAccessControl"]
          Resource = "arn:aws:cloudfront::${data.aws_caller_identity.current.account_id}:origin-access-control/*"
        },
        {
          Sid      = "InspectPublicSigningConfiguration"
          Effect   = "Allow"
          Action   = ["cloudfront:GetPublicKey", "cloudfront:GetKeyGroup"]
          Resource = "*"
        },
        {
          Sid      = "InspectConfiguredPublicDnsZone"
          Effect   = "Allow"
          Action   = ["route53:GetHostedZone", "route53:ListResourceRecordSets"]
          Resource = "arn:aws:route53:::hostedzone/${coalesce(var.github_route53_zone_id, "UNCONFIGURED")}"
        },
        {
          Sid      = "InspectApplicationBudget"
          Effect   = "Allow"
          Action   = ["budgets:ViewBudget"]
          Resource = "arn:aws:budgets::${data.aws_caller_identity.current.account_id}:budget/${local.planning_name}-monthly-account-spend"
        }
      ]
    })
  }
}

resource "aws_iam_policy" "github_configuration_read" {
  for_each = var.github_planning_enabled ? local.configuration_read_policies : {}
  name     = "${local.planning_name}-infrastructure-read-${each.key}"
  policy   = each.value
}

resource "aws_iam_role" "github_planner" {
  count                = var.github_planning_enabled ? 1 : 0
  name                 = "${local.planning_name}-github-planner"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.github_subject
      } }
    }]
  })
}

resource "aws_iam_role_policy_attachment" "github_planner_read" {
  for_each   = aws_iam_policy.github_configuration_read
  role       = aws_iam_role.github_planner[0].name
  policy_arn = each.value.arn
}

resource "aws_iam_role_policy" "github_planner_state" {
  count = var.github_planning_enabled ? 1 : 0
  name  = "read-state-and-lock-plans"
  role  = aws_iam_role.github_planner[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "LocateEnvironmentState"
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = local.planning_state_bucket
        Condition = { StringEquals = { "s3:prefix" = [local.planning_state_key, "${local.planning_state_key}.tflock"] } }
      },
      {
        Sid      = "ReadExistingEnvironmentState"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = "${local.planning_state_bucket}/${local.planning_state_key}"
      },
      {
        Sid      = "HoldOnlyTheEnvironmentPlanLock"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"]
        Resource = "${local.planning_state_bucket}/${local.planning_state_key}.tflock"
      }
    ]
  })
}

output "github_planner_role_arn" { value = one(aws_iam_role.github_planner[*].arn) }
output "github_planner_subject" { value = var.github_planning_enabled ? local.github_subject : null }
