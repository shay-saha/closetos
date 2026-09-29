variable "github_rollouts_enabled" {
  type        = bool
  default     = false
  description = "Enable the separate GitHub release role for migration-gated ECS rollouts and workflow updates. Production requires the protected production GitHub Environment."
  validation {
    condition     = !var.github_rollouts_enabled || var.github_repository != null
    error_message = "Configure the immutable GitHub repository identity before enabling release rollouts."
  }
}

locals {
  rollout_name    = "${var.application_name}-${var.environment}"
  rollout_cluster = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:cluster/${local.rollout_name}"
  rollout_subject = local.github_enabled ? (var.environment == "prod" ? replace(local.github_subject, ":ref:refs/heads/main", ":environment:production") : local.github_subject) : null
  rollout_machine = "arn:aws:states:${var.region}:${data.aws_caller_identity.current.account_id}:stateMachine:${local.rollout_name}-media"
}

resource "aws_iam_role" "github_deployer" {
  count                = var.github_rollouts_enabled ? 1 : 0
  name                 = "${local.rollout_name}-github-deployer"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.rollout_subject
      } }
    }]
  })
}

resource "aws_iam_role_policy" "github_deployer" {
  count = var.github_rollouts_enabled ? 1 : 0
  name  = "roll-out-verified-release-revisions"
  role  = aws_iam_role.github_deployer[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "InspectReleaseTaskDefinitions"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition"]
        Resource = "*"
      },
      {
        Sid       = "ReconfirmOwnMigrationCompletion"
        Effect    = "Allow"
        Action    = ["ecs:DescribeTasks"]
        Resource  = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task/${local.rollout_name}/*"
        Condition = { ArnEquals = { "ecs:cluster" = local.rollout_cluster } }
      },
      {
        Sid      = "ObserveOnlyOwnServices"
        Effect   = "Allow"
        Action   = ["ecs:DescribeServices"]
        Resource = [for service in ["api", "web"] : "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${local.rollout_name}/${local.rollout_name}-${service}"]
      },
      {
        Sid      = "UpdateOnlyOwnMediaWorkflow"
        Effect   = "Allow"
        Action   = ["states:DescribeStateMachine", "states:UpdateStateMachine"]
        Resource = local.rollout_machine
      },
      {
        Sid    = "PassOnlyServiceRolesToEcs"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = flatten([for service in ["api", "web"] : [for suffix in ["task", "execution"] :
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.rollout_name}-${service}-${suffix}"
        ]])
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
      ], [for service in ["api", "web"] : {
        Sid      = service == "api" ? "RollOutOnlyApiRevisions" : "RollOutOnlyWebRevisions"
        Effect   = "Allow"
        Action   = ["ecs:UpdateService"]
        Resource = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:service/${local.rollout_name}/${local.rollout_name}-${service}"
        Condition = {
          ArnEquals = { "ecs:cluster" = local.rollout_cluster }
          ArnLike   = { "ecs:task-definition" = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.rollout_name}-${service}:*" }
          Bool      = { "ecs:enable-execute-command" = "false" }
        }
    }])
  })
}

output "github_deployer_role_arn" { value = one(aws_iam_role.github_deployer[*].arn) }
output "github_deployer_subject" { value = var.github_rollouts_enabled ? local.rollout_subject : null }
