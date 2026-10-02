variable "application_permissions_boundaries_enabled" {
  type        = bool
  default     = false
  description = "Create separate maximum-permission policies for application roles before delegating IAM changes to deployment automation. Apply after the environment's foundation database exists."
}

variable "embedding_model_arn" {
  type        = string
  default     = null
  description = "Exact Titan multimodal embedding model permitted by the API's boundary; must match the application module."
  validation {
    condition     = var.embedding_model_arn == null ? !var.application_permissions_boundaries_enabled : can(regex("^arn:aws:bedrock:[a-z0-9-]+::foundation-model/amazon\\.titan-embed-image-v1$", var.embedding_model_arn))
    error_message = "Provide the exact regional Titan multimodal embedding model ARN when application boundaries are enabled."
  }
}

variable "analysis_model_arns" {
  type        = set(string)
  default     = []
  description = "Exact analysis/profile and underlying regional model ARNs permitted by the worker's boundary; must match the application module."
  validation {
    condition = (!var.application_permissions_boundaries_enabled || length(var.analysis_model_arns) > 0) && length(var.analysis_model_arns) <= 16 && alltrue([
      for arn in var.analysis_model_arns : can(regex("^arn:aws:bedrock:[a-z0-9-]+:([0-9]{12})?:(foundation-model|inference-profile|application-inference-profile)/[A-Za-z0-9._:/-]+$", arn))
    ])
    error_message = "Provide one to sixteen exact analysis model/profile ARNs without wildcards when application boundaries are enabled."
  }
}

data "aws_db_instance" "boundary_database" {
  count                  = var.application_permissions_boundaries_enabled ? 1 : 0
  db_instance_identifier = "${var.application_name}-${var.environment}"
  lifecycle {
    postcondition {
      condition     = self.db_instance_arn == "arn:aws:rds:${var.region}:${data.aws_caller_identity.current.account_id}:db:${var.application_name}-${var.environment}"
      error_message = "Read master secret metadata only from this account's environment database."
    }
    postcondition {
      condition = try(
        length(self.master_user_secret) == 1 && self.master_user_secret[0].secret_status == "active" &&
        startswith(self.master_user_secret[0].secret_arn, "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:rds!db-"),
        false
      )
      error_message = "Application boundaries require the environment database's active, AWS-managed master secret metadata."
    }
  }
}

locals {
  boundary_name          = "${var.application_name}-${var.environment}"
  boundary_account       = data.aws_caller_identity.current.account_id
  boundary_master_secret = var.application_permissions_boundaries_enabled ? try(data.aws_db_instance.boundary_database[0].master_user_secret[0].secret_arn, null) : null
  boundary_media         = "arn:aws:s3:::${local.boundary_name}-${local.boundary_account}-media"
  boundary_cluster       = "arn:aws:ecs:${var.region}:${local.boundary_account}:cluster/${local.boundary_name}"
  boundary_queues        = [for queue in ["media-ingest", "media-results"] : "arn:aws:sqs:${var.region}:${local.boundary_account}:${local.boundary_name}-${queue}"]
  boundary_secrets       = { for key in ["database-app", "web-session", "media-signing"] : key => "arn:aws:secretsmanager:${var.region}:${local.boundary_account}:secret:${local.boundary_name}/${key}-??????" }
  boundary_encryption = {
    Effect   = "Allow"
    Action   = ["kms:Decrypt"]
    Resource = "arn:aws:kms:${var.region}:${local.boundary_account}:key/*"
    Condition = { StringEquals = {
      "aws:ResourceTag/Application" = var.application_name
      "aws:ResourceTag/Environment" = var.environment
    } }
  }
  boundary_empty = [{ Effect = "Deny", Action = "*", Resource = "*" }]
  execution_boundaries = { for service in ["api", "web", "media-worker", "migration"] : "${service}-execution" => jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = "arn:aws:ecr:${var.region}:${local.boundary_account}:repository/${local.boundary_name}/${service == "migration" ? "api" : service}"
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:aws:logs:${var.region}:${local.boundary_account}:log-group:/ecs/${local.boundary_name}/${service}:log-stream:*"
      }
      ], service == "media-worker" ? [] : [{
        Effect = "Allow"
        Action = ["secretsmanager:GetSecretValue"]
        Resource = service == "migration" ? [local.boundary_master_secret, local.boundary_secrets["database-app"]] : (
          service == "api" ? [local.boundary_secrets["database-app"], local.boundary_secrets["media-signing"]] : [local.boundary_secrets["web-session"]]
        )
    }])
  }) }
  application_boundaries = merge(local.execution_boundaries, {
    api-task = jsonencode({
      Version = "2012-10-17"
      Statement = [
        { Effect = "Allow", Action = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"], Resource = "${local.boundary_media}/users/*/garments/*/images/*/*" },
        {
          Effect   = "Allow"
          Action   = ["s3:ListBucket", "s3:ListBucketVersions"]
          Resource = local.boundary_media
          Condition = { StringLike = { "s3:prefix" = [
            "users/????????-????-????-????-????????????/",
            "users/*/garments/*/images/*/",
            "users/*/garments/*/images/*/original.*"
          ] } }
        },
        {
          Effect   = "Allow"
          Action   = ["cloudfront:CreateInvalidation", "cloudfront:GetInvalidation"]
          Resource = "arn:aws:cloudfront::${local.boundary_account}:distribution/*"
          Condition = { StringEquals = {
            "aws:ResourceTag/Application" = var.application_name
            "aws:ResourceTag/Environment" = var.environment
          } }
        },
        { Effect = "Allow", Action = ["states:StartExecution"], Resource = "arn:aws:states:${var.region}:${local.boundary_account}:stateMachine:${local.boundary_name}-media" },
        { Effect = "Allow", Action = ["states:DescribeExecution", "states:GetExecutionHistory", "states:StopExecution"], Resource = "arn:aws:states:${var.region}:${local.boundary_account}:execution:${local.boundary_name}-media:*" },
        {
          Effect    = "Allow", Action = ["ecs:ListTasks"], Resource = "*"
          Condition = { ArnEquals = { "ecs:cluster" = local.boundary_cluster } }
        },
        { Effect = "Allow", Action = ["ecs:DescribeTasks"], Resource = "arn:aws:ecs:${var.region}:${local.boundary_account}:task/${local.boundary_name}/*" },
        {
          Effect = "Allow", Action = ["ecs:StopTask"], Resource = "arn:aws:ecs:${var.region}:${local.boundary_account}:task/${local.boundary_name}/*"
          Condition = { StringEquals = {
            "aws:ResourceTag/Application"    = var.application_name
            "aws:ResourceTag/Environment"    = var.environment
            "aws:ResourceTag/ClosetosWorker" = "media"
          } }
        },
        { Effect = "Allow", Action = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"], Resource = local.boundary_queues },
        local.boundary_encryption,
        { Effect = "Allow", Action = ["bedrock:InvokeModel"], Resource = var.embedding_model_arn }
      ]
    })
    media-worker-task = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Effect   = "Allow"
          Action   = ["s3:GetObject"]
          Resource = ["${local.boundary_media}/users/*/garments/*/images/*/original.*", "${local.boundary_media}/users/*/garments/*/images/*/pipelines/*/*"]
        },
        { Effect = "Allow", Action = ["s3:PutObject"], Resource = "${local.boundary_media}/users/*/garments/*/images/*/pipelines/*/*" },
        { Effect = "Allow", Action = ["bedrock:InvokeModel"], Resource = var.analysis_model_arns }
      ]
    })
    web-task       = jsonencode({ Version = "2012-10-17", Statement = local.boundary_empty })
    migration-task = jsonencode({ Version = "2012-10-17", Statement = local.boundary_empty })
    media-workflow = jsonencode({
      Version = "2012-10-17"
      Statement = [
        {
          Effect    = "Allow"
          Action    = ["ecs:RunTask"]
          Resource  = "arn:aws:ecs:${var.region}:${local.boundary_account}:task-definition/${local.boundary_name}-media:*"
          Condition = { ArnEquals = { "ecs:cluster" = local.boundary_cluster } }
        },
        { Effect = "Allow", Action = ["ecs:DescribeTasks"], Resource = "arn:aws:ecs:${var.region}:${local.boundary_account}:task/${local.boundary_name}/*" },
        {
          Effect = "Allow", Action = ["ecs:StopTask"], Resource = "arn:aws:ecs:${var.region}:${local.boundary_account}:task/${local.boundary_name}/*"
          Condition = { StringEquals = {
            "aws:ResourceTag/Application"    = var.application_name
            "aws:ResourceTag/Environment"    = var.environment
            "aws:ResourceTag/ClosetosWorker" = "media"
          } }
        },
        {
          Effect = "Allow", Action = ["ecs:TagResource"], Resource = "arn:aws:ecs:${var.region}:${local.boundary_account}:task/${local.boundary_name}/*"
          Condition = { StringEquals = {
            "ecs:CreateAction"              = "RunTask"
            "aws:RequestTag/Application"    = var.application_name
            "aws:RequestTag/Environment"    = var.environment
            "aws:RequestTag/ClosetosWorker" = "media"
          } }
        },
        {
          Effect    = "Allow"
          Action    = ["iam:PassRole"]
          Resource  = [for suffix in ["task", "execution"] : "arn:aws:iam::${local.boundary_account}:role/${local.boundary_name}-media-worker-${suffix}"]
          Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
        },
        {
          Effect   = "Allow"
          Action   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
          Resource = "arn:aws:events:${var.region}:${local.boundary_account}:rule/StepFunctionsGetEventsForECSTaskRule"
        },
        { Effect = "Allow", Action = ["sqs:SendMessage"], Resource = "arn:aws:sqs:${var.region}:${local.boundary_account}:${local.boundary_name}-media-results" },
        merge(local.boundary_encryption, { Action = ["kms:Decrypt", "kms:GenerateDataKey"] }),
        {
          Effect   = "Allow"
          Action   = ["logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies", "logs:DescribeLogGroups"]
          Resource = "*"
        },
        { Effect = "Allow", Action = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"], Resource = "*" }
      ]
    })
  })
}

resource "aws_iam_policy" "application_permissions_boundary" {
  for_each = var.application_permissions_boundaries_enabled ? local.application_boundaries : {}
  name     = "${local.boundary_name}-${each.key}-permissions-boundary"
  policy   = each.value
  lifecycle {
    prevent_destroy = true
    precondition {
      condition     = alltrue([for arn in var.analysis_model_arns : split(":", arn)[4] == "" || split(":", arn)[4] == local.boundary_account])
      error_message = "Analysis inference profiles must belong to this AWS account."
    }
  }
}

output "application_permissions_boundary_arns" {
  value = { for role, policy in aws_iam_policy.application_permissions_boundary : role => policy.arn }
}
