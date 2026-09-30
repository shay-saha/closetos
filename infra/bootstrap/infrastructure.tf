variable "github_infrastructure_enabled" {
  type        = bool
  default     = false
  description = "Enable the separate infrastructure apply role after initializing the foundation, planning access, and application permissions boundaries. Production uses the protected production GitHub Environment."
  validation {
    condition     = !var.github_infrastructure_enabled || (var.github_planning_enabled && var.application_permissions_boundaries_enabled && var.github_repository != null)
    error_message = "Configure immutable GitHub planning and application permissions boundaries before enabling infrastructure changes."
  }
}

variable "github_app_domain" {
  type        = string
  default     = null
  description = "Exact application hostname whose public DNS and ACM validation records deployment may manage."
  validation {
    condition = var.github_app_domain == null ? !var.github_infrastructure_enabled : (
      length(var.github_app_domain) <= 253 && can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.github_app_domain)) && alltrue([for label in split(".", var.github_app_domain) : can(regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$", label))])
    )
    error_message = "Provide the application's exact lowercase hostname when enabling infrastructure changes."
  }
}

data "aws_vpc" "infrastructure" {
  count = var.github_infrastructure_enabled ? 1 : 0
  filter {
    name   = "tag:Application"
    values = [var.application_name]
  }
  filter {
    name   = "tag:Environment"
    values = [var.environment]
  }
  filter {
    name   = "tag:Name"
    values = [local.planning_name]
  }
  lifecycle {
    postcondition {
      condition     = self.arn == "arn:aws:ec2:${var.region}:${data.aws_caller_identity.current.account_id}:vpc/${self.id}"
      error_message = "Use only the foundation VPC in this environment's AWS account and region."
    }
  }
}

locals {
  infrastructure_subject = local.github_enabled ? (var.environment == "prod" ? replace(local.github_subject, ":ref:refs/heads/main", ":environment:production") : local.github_subject) : null
  infrastructure_account = data.aws_caller_identity.current.account_id
  infrastructure_name    = local.planning_name
  infrastructure_domain  = coalesce(var.github_app_domain, "UNCONFIGURED")
  infrastructure_request_tags = {
    "aws:RequestTag/Application" = var.application_name
    "aws:RequestTag/Environment" = var.environment
  }
  infrastructure_roles = { for arn in local.application_roles : arn => "${replace(arn, ":role/", ":policy/")}-permissions-boundary" }
  infrastructure_tag_actions = [
    "ec2:CreateTags", "iam:TagRole", "ecr:TagResource", "rds:AddTagsToResource", "sqs:TagQueue", "kms:TagResource",
    "secretsmanager:TagResource", "ecs:TagResource", "states:TagResource", "events:TagResource", "acm:AddTagsToCertificate",
    "elasticloadbalancing:AddTags", "cloudfront:TagResource", "cognito-idp:TagResource", "logs:TagResource", "logs:TagLogGroup",
    "application-autoscaling:TagResource"
  ]
  infrastructure_untag_actions = [
    "ec2:DeleteTags", "iam:UntagRole", "ecr:UntagResource", "rds:RemoveTagsFromResource", "sqs:UntagQueue", "kms:UntagResource",
    "secretsmanager:UntagResource", "ecs:UntagResource", "states:UntagResource", "events:UntagResource", "acm:RemoveTagsFromCertificate",
    "elasticloadbalancing:RemoveTags", "cloudfront:UntagResource", "cognito-idp:UntagResource", "logs:UntagResource", "logs:UntagLogGroup",
    "application-autoscaling:UntagResource"
  ]
  infrastructure_policy_documents = {
    iam-create = jsonencode({
      Version = "2012-10-17"
      Statement = [for role, boundary in local.infrastructure_roles : {
        Effect   = "Allow"
        Action   = ["iam:CreateRole"]
        Resource = role
        Condition = {
          ArnEquals    = { "iam:PermissionsBoundary" = boundary }
          StringEquals = local.infrastructure_request_tags
        }
      }]
    })
    iam-update = jsonencode({
      Version = "2012-10-17"
      Statement = [for role, boundary in local.infrastructure_roles : {
        Effect    = "Allow"
        Action    = ["iam:PutRolePermissionsBoundary", "iam:PutRolePolicy", "iam:DeleteRolePolicy", "iam:UpdateAssumeRolePolicy", "iam:UpdateRole"]
        Resource  = role
        Condition = { ArnEquals = { "iam:PermissionsBoundary" = boundary } }
      }]
    })
    network-create  = local.infrastructure_network_create_policy
    network-update  = local.infrastructure_network_update_policy
    data            = local.infrastructure_data_policy
    runtime         = local.infrastructure_runtime_policy
    runtime-support = local.infrastructure_runtime_support_policy
    edge            = local.infrastructure_edge_policy
  }
}

resource "aws_iam_role" "github_infrastructure" {
  count                = var.github_infrastructure_enabled ? 1 : 0
  name                 = "${local.infrastructure_name}-github-infrastructure"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.infrastructure_subject
      } }
    }]
  })
}

resource "aws_iam_policy" "github_infrastructure" {
  for_each = var.github_infrastructure_enabled ? local.infrastructure_policy_documents : {}
  name     = "${local.infrastructure_name}-infrastructure-${each.key}"
  policy   = each.value
}

resource "aws_iam_role_policy_attachment" "github_infrastructure_write" {
  for_each   = aws_iam_policy.github_infrastructure
  role       = aws_iam_role.github_infrastructure[0].name
  policy_arn = each.value.arn
}
resource "aws_iam_role_policy_attachment" "github_infrastructure_read" {
  for_each   = var.github_infrastructure_enabled ? aws_iam_policy.github_configuration_read : {}
  role       = aws_iam_role.github_infrastructure[0].name
  policy_arn = each.value.arn
}

resource "aws_iam_role_policy" "github_infrastructure_guardrails" {
  count = var.github_infrastructure_enabled ? 1 : 0
  name  = "write-environment-state-and-preserve-security-boundaries"
  role  = aws_iam_role.github_infrastructure[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Effect    = "Allow"
        Action    = ["s3:ListBucket"]
        Resource  = local.planning_state_bucket
        Condition = { StringEquals = { "s3:prefix" = [local.planning_state_key, "${local.planning_state_key}.tflock"] } }
      },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject"], Resource = "${local.planning_state_bucket}/${local.planning_state_key}" },
      { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject"], Resource = "${local.planning_state_bucket}/${local.planning_state_key}.tflock" },
      {
        Effect      = "Deny"
        Action      = ["s3:GetObject", "s3:GetObjectVersion", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"]
        NotResource = ["${local.planning_state_bucket}/${local.planning_state_key}", "${local.planning_state_bucket}/${local.planning_state_key}.tflock"]
      },
      {
        Effect = "Deny"
        Action = [
          "secretsmanager:GetSecretValue", "secretsmanager:BatchGetSecretValue", "secretsmanager:PutSecretValue", "secretsmanager:UpdateSecret",
          "secretsmanager:CreateSecret", "secretsmanager:PutResourcePolicy", "secretsmanager:DeleteSecret", "secretsmanager:RotateSecret",
          "kms:Decrypt", "kms:Encrypt", "kms:GenerateDataKey", "kms:GenerateDataKeyWithoutPlaintext", "kms:ReEncryptFrom", "kms:ReEncryptTo",
          "sts:AssumeRole", "iam:DeleteRolePermissionsBoundary", "iam:CreatePolicyVersion", "iam:SetDefaultPolicyVersion",
          "ecs:RunTask", "ecs:ExecuteCommand", "ecs:DeregisterTaskDefinition", "ecs:DeleteTaskDefinitions", "states:StartExecution"
          , "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:PurgeQueue", "sqs:SendMessage"
        ]
        Resource = "*"
      },
      {
        Effect    = "Allow"
        Action    = ["iam:TagRole", "iam:UntagRole"]
        Resource  = local.application_roles
        Condition = { StringEqualsIfExists = local.infrastructure_request_tags }
      },
      {
        Effect    = "Deny"
        Action    = local.infrastructure_untag_actions
        Resource  = "*"
        Condition = { "ForAnyValue:StringEquals" = { "aws:TagKeys" = ["Application", "Environment"] } }
      }
      ], [for key, value in { Application = var.application_name, Environment = var.environment } : {
        Effect                            = "Deny"
        Action                            = local.infrastructure_tag_actions
        Resource                          = "*"
        Condition = {
          Null            = { "aws:RequestTag/${key}" = "false" }
          StringNotEquals = { "aws:RequestTag/${key}" = value }
        }
    }])
  })
}

output "github_infrastructure_role_arn" { value = one(aws_iam_role.github_infrastructure[*].arn) }
output "github_infrastructure_subject" { value = var.github_infrastructure_enabled ? local.infrastructure_subject : null }
