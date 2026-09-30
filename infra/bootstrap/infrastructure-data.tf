locals {
  infrastructure_data_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:PutBucketPolicy", "s3:PutBucketPublicAccessBlock", "s3:PutBucketOwnershipControls", "s3:PutEncryptionConfiguration", "s3:PutBucketCORS", "s3:PutLifecycleConfiguration", "s3:PutBucketNotification", "s3:PutBucketTagging"]
        Resource = local.planning_media
      },
      {
        Effect   = "Allow"
        Action   = ["ecr:PutImageTagMutability", "ecr:PutImageScanningConfiguration", "ecr:PutLifecyclePolicy", "ecr:DeleteLifecyclePolicy", "ecr:TagResource", "ecr:UntagResource"]
        Resource = local.release_repositories
      },
      {
        Effect   = "Allow"
        Action   = ["rds:ModifyDBInstance", "rds:ModifyDBSubnetGroup", "rds:ModifyDBParameterGroup", "rds:ResetDBParameterGroup", "rds:AddTagsToResource", "rds:RemoveTagsFromResource"]
        Resource = [for resource in ["db:${local.infrastructure_name}", "subgrp:${local.infrastructure_name}", "pg:${local.infrastructure_name}-postgres18"] : "arn:aws:rds:${var.region}:${local.infrastructure_account}:${resource}"]
      },
      {
        Effect   = "Allow"
        Action   = ["secretsmanager:TagResource", "secretsmanager:UntagResource"]
        Resource = values(local.boundary_secrets)
      },
      {
        Effect   = "Allow"
        Action   = ["sqs:SetQueueAttributes", "sqs:TagQueue", "sqs:UntagQueue"]
        Resource = [for queue in ["media-ingest", "media-results", "media-ingest-dlq", "media-results-dlq"] : "arn:aws:sqs:${var.region}:${local.infrastructure_account}:${local.infrastructure_name}-${queue}"]
      },
      {
        Effect    = "Allow"
        Action    = ["kms:UpdateKeyDescription", "kms:EnableKeyRotation", "kms:DisableKeyRotation", "kms:PutKeyPolicy", "kms:TagResource", "kms:UntagResource", "kms:CreateAlias", "kms:UpdateAlias", "kms:DeleteAlias"]
        Resource  = "arn:aws:kms:${var.region}:${local.infrastructure_account}:key/*"
        Condition = { StringEquals = local.planning_tags }
      },
      {
        Effect   = "Allow"
        Action   = ["kms:CreateAlias", "kms:UpdateAlias", "kms:DeleteAlias"]
        Resource = "arn:aws:kms:${var.region}:${local.infrastructure_account}:alias/${local.infrastructure_name}-queues"
      },
      {
        Effect   = "Allow"
        Action   = ["budgets:ModifyBudget"]
        Resource = "arn:aws:budgets::${local.infrastructure_account}:budget/${local.infrastructure_name}-monthly-account-spend"
      }
    ]
  })
}
