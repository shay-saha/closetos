variable "github_migrations_enabled" {
  type        = bool
  default     = false
  description = "Enable the separate GitHub migration role after preparing the application's private ECS migration task. Production uses the approval-protected production GitHub Environment."
  validation {
    condition     = !var.github_migrations_enabled || var.github_repository != null
    error_message = "Configure the immutable GitHub repository identity before enabling migrations."
  }
}

locals {
  migration_name    = "${var.application_name}-${var.environment}"
  migration_cluster = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:cluster/${local.migration_name}"
  migration_tasks   = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task/${local.migration_name}/*"
  migration_subject = local.github_enabled ? (var.environment == "prod" ? replace(local.github_subject, ":ref:refs/heads/main", ":environment:production") : local.github_subject) : null
}

resource "aws_iam_role" "github_migrator" {
  count                = var.github_migrations_enabled ? 1 : 0
  name                 = "${local.migration_name}-github-migrator"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.migration_subject
      } }
    }]
  })
}

resource "aws_iam_role_policy" "github_migrator" {
  count = var.github_migrations_enabled ? 1 : 0
  name  = "run-private-database-migrations"
  role  = aws_iam_role.github_migrator[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "InspectMigrationPrerequisites"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTaskDefinition", "ec2:DescribeSubnets", "ec2:DescribeSecurityGroups"]
        Resource = "*"
      },
      {
        Sid      = "RunOnlyStandaloneMigration"
        Effect   = "Allow"
        Action   = ["ecs:RunTask"]
        Resource = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task-definition/${local.migration_name}-migration:*"
        Condition = {
          ArnEquals = { "ecs:cluster" = local.migration_cluster }
          Bool      = { "ecs:enable-execute-command" = "false" }
          StringEquals = {
            "aws:RequestTag/Application" = var.application_name
            "aws:RequestTag/Environment" = var.environment
            "aws:RequestTag/Workload"    = "database-migration"
          }
        }
      },
      {
        Sid       = "TagNewMigrationTasks"
        Effect    = "Allow"
        Action    = ["ecs:TagResource"]
        Resource  = local.migration_tasks
        Condition = { StringEquals = { "ecs:CreateAction" = "RunTask" } }
      },
      {
        Sid       = "ObserveTasksInOwnCluster"
        Effect    = "Allow"
        Action    = ["ecs:DescribeTasks"]
        Resource  = local.migration_tasks
        Condition = { ArnEquals = { "ecs:cluster" = local.migration_cluster } }
      },
      {
        Sid      = "StopOnlyOwnMigrationTasks"
        Effect   = "Allow"
        Action   = ["ecs:StopTask"]
        Resource = local.migration_tasks
        Condition = {
          ArnEquals = { "ecs:cluster" = local.migration_cluster }
          StringEquals = {
            "aws:ResourceTag/Application" = var.application_name
            "aws:ResourceTag/Environment" = var.environment
            "aws:ResourceTag/Workload"    = "database-migration"
          }
        }
      },
      {
        Sid    = "PassOnlyMigrationRolesToEcs"
        Effect = "Allow"
        Action = ["iam:PassRole"]
        Resource = [for suffix in ["task", "execution"] :
          "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/${local.migration_name}-migration-${suffix}"
        ]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      }
    ]
  })
}

output "github_migrator_role_arn" { value = one(aws_iam_role.github_migrator[*].arn) }
output "github_migrator_subject" { value = var.github_migrations_enabled ? local.migration_subject : null }
