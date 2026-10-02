locals {
  permissions_boundary_prefix = "arn:${local.partition}:iam::${local.account_id}:policy/${local.name}"
  workflow_arn                = "arn:${local.partition}:states:${var.region}:${local.account_id}:stateMachine:${local.name}-media"
  cluster_arn                 = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:cluster/${local.name}"
  task_trust = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "ecs-tasks.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnLike      = { "aws:SourceArn" = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:*" }
      }
    }]
  })
  service_secrets = {
    api          = [aws_secretsmanager_secret.application["database-app"].arn, aws_secretsmanager_secret.application["media-signing"].arn]
    web          = [aws_secretsmanager_secret.application["web-session"].arn]
    media-worker = []
  }
}

resource "aws_iam_role" "task" {
  for_each             = aws_ecr_repository.application
  name                 = "${local.name}-${each.key}-task"
  assume_role_policy   = local.task_trust
  permissions_boundary = var.application_permissions_boundaries_enabled ? "${local.permissions_boundary_prefix}-${each.key}-task-permissions-boundary" : null
}
resource "aws_iam_role" "execution" {
  for_each             = aws_ecr_repository.application
  name                 = "${local.name}-${each.key}-execution"
  assume_role_policy   = local.task_trust
  permissions_boundary = var.application_permissions_boundaries_enabled ? "${local.permissions_boundary_prefix}-${each.key}-execution-permissions-boundary" : null
}
resource "aws_iam_role_policy" "execution" {
  for_each = aws_iam_role.execution
  name     = "container-startup"
  role     = each.value.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "RegistryAuthentication"
        Effect   = "Allow"
        Action   = ["ecr:GetAuthorizationToken"]
        Resource = "*"
      },
      {
        Sid      = "ReadOwnContainerImage"
        Effect   = "Allow"
        Action   = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"]
        Resource = aws_ecr_repository.application[each.key].arn
      },
      {
        Sid      = "WriteOwnContainerLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogStream", "logs:PutLogEvents"]
        Resource = "arn:${local.partition}:logs:${var.region}:${local.account_id}:log-group:/ecs/${local.name}/${each.key}:log-stream:*"
      }
      ], length(local.service_secrets[each.key]) > 0 ? [{
        Sid      = "ReadStartupSecrets"
        Effect   = "Allow"
        Action   = ["secretsmanager:GetSecretValue"]
        Resource = local.service_secrets[each.key]
    }] : [])
  })
}
resource "aws_iam_role_policy" "api" {
  name = "wardrobe-storage-and-processing"
  role = aws_iam_role.task["api"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "OwnedWardrobeMedia"
        Effect   = "Allow"
        Action   = ["s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:DeleteObjectVersion"]
        Resource = "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/*"
      },
      {
        Sid      = "ListOwnedMediaForDeletion"
        Effect   = "Allow"
        Action   = ["s3:ListBucket", "s3:ListBucketVersions"]
        Resource = aws_s3_bucket.media.arn
        Condition = { StringLike = { "s3:prefix" = [
          "users/????????-????-????-????-????????????/",
          "users/*/garments/*/images/*/",
          "users/*/garments/*/images/*/original.*"
        ] } }
      },
      {
        Sid      = "StartMediaWorkflow"
        Effect   = "Allow"
        Action   = ["states:StartExecution"]
        Resource = local.workflow_arn
      },
      {
        Sid      = "ObserveMediaWorkflow"
        Effect   = "Allow"
        Action   = ["states:DescribeExecution", "states:GetExecutionHistory", "states:StopExecution"]
        Resource = "arn:${local.partition}:states:${var.region}:${local.account_id}:execution:${local.name}-media:*"
      },
      {
        Sid       = "FindMediaTasks"
        Effect    = "Allow"
        Action    = ["ecs:ListTasks"]
        Resource  = "*"
        Condition = { ArnEquals = { "ecs:cluster" = local.cluster_arn } }
      },
      {
        Sid      = "ObserveMediaTasks"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks"]
        Resource = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task/${local.name}/*"
      },
      {
        Sid      = "StopMediaWorkersOnly"
        Effect   = "Allow"
        Action   = ["ecs:StopTask"]
        Resource = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task/${local.name}/*"
        Condition = { StringEquals = {
          "aws:ResourceTag/Application"    = var.application_name
          "aws:ResourceTag/Environment"    = var.environment
          "aws:ResourceTag/ClosetosWorker" = "media"
        } }
      },
      {
        Sid      = "ConsumeMediaEvents"
        Effect   = "Allow"
        Action   = ["sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"]
        Resource = [for queue in aws_sqs_queue.media : queue.arn]
      },
      {
        Sid      = "DecryptMediaEvents"
        Effect   = "Allow"
        Action   = ["kms:Decrypt"]
        Resource = aws_kms_key.queues.arn
      },
      {
        Sid      = "InvokeEmbeddingModel"
        Effect   = "Allow"
        Action   = ["bedrock:InvokeModel"]
        Resource = var.embedding_model_arn
      }
    ]
  })
}
resource "aws_iam_role_policy" "worker" {
  name = "transform-and-enrich-media"
  role = aws_iam_role.task["media-worker"].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid      = "ReadProcessingInput"
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = ["${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/original.*", "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/pipelines/*/*"]
      },
      {
        Sid      = "WriteVersionedProcessingOutput"
        Effect   = "Allow"
        Action   = ["s3:PutObject"]
        Resource = "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/pipelines/*/*"
      }
      ], length(var.analysis_model_arns) > 0 ? [{
        Sid      = "InvokeConfiguredAnalysisModels"
        Effect   = "Allow"
        Action   = ["bedrock:InvokeModel"]
        Resource = var.analysis_model_arns
    }] : [])
  })
}
resource "aws_iam_role" "workflow" {
  name                 = "${local.name}-media-workflow"
  permissions_boundary = var.application_permissions_boundaries_enabled ? "${local.permissions_boundary_prefix}-media-workflow-permissions-boundary" : null
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Service = "states.amazonaws.com" }
      Action    = "sts:AssumeRole"
      Condition = {
        StringEquals = { "aws:SourceAccount" = local.account_id }
        ArnEquals    = { "aws:SourceArn" = local.workflow_arn }
      }
    }]
  })
}
resource "aws_iam_role_policy" "workflow" {
  name = "run-media-tasks-and-publish-results"
  role = aws_iam_role.workflow.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "RunMediaTask"
        Effect    = "Allow"
        Action    = ["ecs:RunTask"]
        Resource  = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task-definition/${local.name}-media:*"
        Condition = { ArnEquals = { "ecs:cluster" = local.cluster_arn } }
      },
      {
        Sid      = "MonitorMediaTasks"
        Effect   = "Allow"
        Action   = ["ecs:DescribeTasks"]
        Resource = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task/${local.name}/*"
      },
      {
        Sid      = "StopMediaWorkersOnly"
        Effect   = "Allow"
        Action   = ["ecs:StopTask"]
        Resource = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task/${local.name}/*"
        Condition = { StringEquals = {
          "aws:ResourceTag/Application"    = var.application_name
          "aws:ResourceTag/Environment"    = var.environment
          "aws:ResourceTag/ClosetosWorker" = "media"
        } }
      },
      {
        Sid      = "TagNewMediaWorkers"
        Effect   = "Allow"
        Action   = ["ecs:TagResource"]
        Resource = "arn:${local.partition}:ecs:${var.region}:${local.account_id}:task/${local.name}/*"
        Condition = { StringEquals = {
          "ecs:CreateAction"              = "RunTask"
          "aws:RequestTag/Application"    = var.application_name
          "aws:RequestTag/Environment"    = var.environment
          "aws:RequestTag/ClosetosWorker" = "media"
        } }
      },
      {
        Sid       = "PassWorkerRolesOnly"
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [aws_iam_role.task["media-worker"].arn, aws_iam_role.execution["media-worker"].arn]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
      {
        Sid      = "ObserveTaskCompletion"
        Effect   = "Allow"
        Action   = ["events:PutTargets", "events:PutRule", "events:DescribeRule"]
        Resource = "arn:${local.partition}:events:${var.region}:${local.account_id}:rule/StepFunctionsGetEventsForECSTaskRule"
      },
      {
        Sid      = "PublishProcessingResults"
        Effect   = "Allow"
        Action   = ["sqs:SendMessage"]
        Resource = aws_sqs_queue.media["media-results"].arn
      },
      {
        Sid      = "EncryptProcessingResults"
        Effect   = "Allow"
        Action   = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource = aws_kms_key.queues.arn
      },
      {
        Sid      = "DeliverWorkflowLogs"
        Effect   = "Allow"
        Action   = ["logs:CreateLogDelivery", "logs:GetLogDelivery", "logs:UpdateLogDelivery", "logs:DeleteLogDelivery", "logs:ListLogDeliveries", "logs:PutResourcePolicy", "logs:DescribeResourcePolicies", "logs:DescribeLogGroups"]
        Resource = "*"
      },
      {
        Sid      = "TraceMediaWorkflow"
        Effect   = "Allow"
        Action   = ["xray:PutTraceSegments", "xray:PutTelemetryRecords", "xray:GetSamplingRules", "xray:GetSamplingTargets"]
        Resource = "*"
      }
    ]
  })
}
