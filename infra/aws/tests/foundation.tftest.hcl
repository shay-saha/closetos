mock_provider "aws" { source = "./tests" }

override_resource {
  target = aws_security_group.service["api"]
  values = { id = "sg-00000000000000001" }
}
override_resource {
  target = aws_security_group.service["database"]
  values = { id = "sg-00000000000000002" }
}

variables {
  app_domain            = "closet.example.test"
  cloudfront_public_key = file("tests/media-signing.pub")
  embedding_model_arn   = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  budget_alert_emails   = ["operator@example.test"]
}

run "development_security_and_cost_boundaries" {
  command = apply
  assert {
    condition     = length(aws_subnet.application) == 2 && length(aws_subnet.database) == 2 && length(aws_nat_gateway.main) == 1
    error_message = "Development needs two application/database zones and one NAT gateway."
  }
  assert {
    condition     = alltrue([for subnet in aws_subnet.application : subnet.map_public_ip_on_launch == false]) && alltrue([for subnet in aws_subnet.database : subnet.map_public_ip_on_launch == false])
    error_message = "Application tasks and the database must have private addresses."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.service["api_db"].referenced_security_group_id == aws_security_group.service["api"].id && aws_vpc_security_group_ingress_rule.service["api_db"].security_group_id == aws_security_group.service["database"].id && aws_vpc_security_group_ingress_rule.service["api_db"].from_port == 5432 && length([for connection in local.connections : connection if connection.destination == "database"]) == 1
    error_message = "Only the API security group may access PostgreSQL."
  }
  assert {
    condition     = !aws_db_instance.main.publicly_accessible && aws_db_instance.main.storage_encrypted && aws_db_instance.main.manage_master_user_password && aws_db_instance.main.deletion_protection && !aws_db_instance.main.skip_final_snapshot && aws_db_instance.main.backup_retention_period == 7
    error_message = "RDS must be encrypted, private, protected, backed up, and use an AWS-managed password."
  }
  assert {
    condition     = { for parameter in aws_db_parameter_group.main.parameter : parameter.name => parameter.value } == { "rds.force_ssl" = "1", "log_min_duration_statement" = "250" }
    error_message = "Require TLS and log slow statements without logging all personal data."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.media.block_public_acls && aws_s3_bucket_public_access_block.media.block_public_policy && aws_s3_bucket_public_access_block.media.ignore_public_acls && aws_s3_bucket_public_access_block.media.restrict_public_buckets
    error_message = "Every public media access mechanism must be blocked."
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.media.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Media objects must be encrypted at rest."
  }
  assert {
    condition     = one(aws_s3_bucket_cors_configuration.media.cors_rule).allowed_origins == toset(["https://closet.example.test"])
    error_message = "Direct uploads must use the application's HTTPS origin."
  }
  assert {
    condition     = aws_cloudfront_distribution.media.default_cache_behavior[0].viewer_protocol_policy == "https-only" && length(aws_cloudfront_distribution.media.default_cache_behavior[0].trusted_key_groups) == 1 && aws_cloudfront_origin_access_control.media.signing_behavior == "always"
    error_message = "CloudFront must require HTTPS, signed viewer requests, and signed origin requests."
  }
  assert {
    condition     = jsondecode(aws_s3_bucket_policy.media.policy).Statement[1].Resource == "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/pipelines/*/*.webp" && jsondecode(aws_s3_bucket_policy.media.policy).Statement[1].Condition.StringEquals["AWS:SourceArn"] == aws_cloudfront_distribution.media.arn
    error_message = "Only this distribution may serve derived wardrobe images; originals and manifests stay outside CDN access."
  }
  assert {
    condition     = aws_cognito_user_pool_client.web.allowed_oauth_flows == toset(["code"]) && !aws_cognito_user_pool_client.web.generate_secret && aws_cognito_user_pool_client.web.enable_token_revocation && aws_cognito_user_pool_client.web.prevent_user_existence_errors == "ENABLED"
    error_message = "Use authorization code flow, revoke sessions, and avoid account enumeration."
  }
  assert {
    condition     = aws_cognito_user_pool_client.web.callback_urls == toset(["https://closet.example.test/api/auth/callback/wardrobe"]) && aws_cognito_user_group.administrators.name == "closetos-admin"
    error_message = "OAuth callbacks and administrator claims must match the application."
  }
  assert {
    condition     = alltrue([for queue in aws_sqs_queue.media : queue.kms_master_key_id == aws_kms_key.queues.arn && queue.receive_wait_time_seconds == 20 && queue.visibility_timeout_seconds == 600 && jsondecode(queue.redrive_policy).maxReceiveCount == 5]) && alltrue([for queue in aws_sqs_queue.dead_letter : queue.message_retention_seconds == 1209600])
    error_message = "Processing delivery must be encrypted, bounded, long polled, and recoverable through retained DLQs."
  }
  assert {
    condition     = jsondecode(aws_cloudwatch_event_rule.uploads.event_pattern).detail.object.key[0].wildcard == "users/*/garments/*/images/*/original.*" && aws_cloudwatch_event_target.uploads.retry_policy[0].maximum_retry_attempts == 10
    error_message = "Only original uploads may start processing; generated derivatives must not create a loop."
  }
  assert {
    condition     = alltrue([for repo in aws_ecr_repository.application : repo.image_tag_mutability == "IMMUTABLE" && repo.image_scanning_configuration[0].scan_on_push && !repo.force_delete])
    error_message = "Released images must remain immutable, scanned, and available for rollback."
  }
  assert {
    condition     = toset(keys(aws_secretsmanager_secret.application)) == toset(["web-session", "media-signing", "database-app"]) && alltrue([for secret in aws_secretsmanager_secret.application : secret.recovery_window_in_days == 30])
    error_message = "Keep runtime database, session, and signing secrets separate and recoverable; supply values outside Terraform."
  }
}

run "production_redundancy_and_recovery" {
  command   = apply
  state_key = "production"
  variables { environment = "prod" }
  assert {
    condition     = length(aws_nat_gateway.main) == 2 && aws_db_instance.main.multi_az && aws_db_instance.main.backup_retention_period == 14
    error_message = "Production must keep independent egress and database recovery across two zones."
  }
  assert {
    condition     = aws_db_instance.main.max_allocated_storage == 100 && !aws_db_instance.main.apply_immediately && !aws_db_instance.main.allow_major_version_upgrade
    error_message = "Bound storage costs and apply database maintenance without surprise major upgrades."
  }
}

run "least_privilege_service_and_workflow_roles" {
  command   = apply
  state_key = "permissions"
  variables {
    analysis_model_arns = [
      "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0",
      "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.amazon.nova-lite-v1:0"
    ]
  }
  assert {
    condition     = toset(keys(aws_iam_role.task)) == toset(["api", "web", "media-worker"]) && alltrue([for role in aws_iam_role.task : jsondecode(role.assume_role_policy).Statement[0].Principal.Service == "ecs-tasks.amazonaws.com" && jsondecode(role.assume_role_policy).Statement[0].Condition.StringEquals["aws:SourceAccount"] == "123456789012" && jsondecode(role.assume_role_policy).Statement[0].Condition.ArnLike["aws:SourceArn"] == "arn:aws:ecs:eu-west-2:123456789012:*"])
    error_message = "Use distinct application task roles with account-scoped ECS trust."
  }
  assert {
    condition = toset(flatten([for statement in jsondecode(aws_iam_role_policy.api.policy).Statement : statement.Action])) == toset([
      "s3:GetObject", "s3:PutObject", "s3:DeleteObject", "s3:ListBucket", "states:StartExecution", "states:DescribeExecution", "states:GetExecutionHistory", "states:StopExecution",
      "ecs:ListTasks", "ecs:DescribeTasks", "ecs:StopTask",
      "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes", "kms:Decrypt", "bedrock:InvokeModel"
    ]) && { for statement in jsondecode(aws_iam_role_policy.api.policy).Statement : statement.Sid => statement.Resource }["InvokeEmbeddingModel"] == var.embedding_model_arn
    error_message = "The API may manage wardrobe objects, inspect and stop media workers, consume processing events, and invoke its embedding model without administrator or queue publishing permissions."
  }
  assert {
    condition     = { for statement in jsondecode(aws_iam_role_policy.api.policy).Statement : statement.Sid => statement.Resource }["ObserveMediaWorkflow"] == "arn:aws:states:eu-west-2:123456789012:execution:closetos-dev-media:*"
    error_message = "Workflow recovery may observe only this environment's media executions."
  }
  assert {
    condition     = { for statement in jsondecode(aws_iam_role_policy.api.policy).Statement : statement.Sid => statement }["ListOwnedMediaForDeletion"].Condition.StringLike["s3:prefix"] == "users/*/garments/*/images/*/" && { for statement in jsondecode(aws_iam_role_policy.api.policy).Statement : statement.Sid => statement.Resource }["OwnedWardrobeMedia"] == "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/*"
    error_message = "Media listing and access must stay inside wardrobe image prefixes."
  }
  assert {
    condition     = toset(flatten([for statement in jsondecode(aws_iam_role_policy.worker.policy).Statement : statement.Action])) == toset(["s3:GetObject", "s3:PutObject", "bedrock:InvokeModel"]) && { for statement in jsondecode(aws_iam_role_policy.worker.policy).Statement : statement.Sid => statement.Resource }["WriteVersionedProcessingOutput"] == "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/pipelines/*/*" && toset({ for statement in jsondecode(aws_iam_role_policy.worker.policy).Statement : statement.Sid => statement.Resource }["InvokeConfiguredAnalysisModels"]) == var.analysis_model_arns
    error_message = "Workers may write only versioned results and invoke configured analysis models; they may not delete media or access domain storage and secrets."
  }
  assert {
    condition     = toset({ for statement in jsondecode(aws_iam_role_policy.execution["api"].policy).Statement : statement.Sid => statement.Resource }["ReadStartupSecrets"]) == toset([aws_secretsmanager_secret.application["database-app"].arn, aws_secretsmanager_secret.application["media-signing"].arn]) && toset({ for statement in jsondecode(aws_iam_role_policy.execution["web"].policy).Statement : statement.Sid => statement.Resource }["ReadStartupSecrets"]) == toset([aws_secretsmanager_secret.application["web-session"].arn]) && !contains([for statement in jsondecode(aws_iam_role_policy.execution["media-worker"].policy).Statement : statement.Sid], "ReadStartupSecrets")
    error_message = "Container execution roles may load only their own startup secrets; the media worker needs none."
  }
  assert {
    condition     = alltrue([for name, policy in aws_iam_role_policy.execution : { for statement in jsondecode(policy.policy).Statement : statement.Sid => statement.Resource }["ReadOwnContainerImage"] == aws_ecr_repository.application[name].arn && { for statement in jsondecode(policy.policy).Statement : statement.Sid => statement.Resource }["WriteOwnContainerLogs"] == "arn:aws:logs:eu-west-2:123456789012:log-group:/ecs/closetos-dev/${name}:log-stream:*"])
    error_message = "Each execution role may pull and log only its own container."
  }
  assert {
    condition     = toset({ for statement in jsondecode(aws_iam_role_policy.workflow.policy).Statement : statement.Sid => statement.Resource }["PassWorkerRolesOnly"]) == toset([aws_iam_role.task["media-worker"].arn, aws_iam_role.execution["media-worker"].arn]) && { for statement in jsondecode(aws_iam_role_policy.workflow.policy).Statement : statement.Sid => statement }["PassWorkerRolesOnly"].Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com" && { for statement in jsondecode(aws_iam_role_policy.workflow.policy).Statement : statement.Sid => statement }["RunMediaTask"].Condition.ArnEquals["ecs:cluster"] == local.cluster_arn && { for statement in jsondecode(aws_iam_role_policy.workflow.policy).Statement : statement.Sid => statement.Resource }["PublishProcessingResults"] == aws_sqs_queue.media["media-results"].arn
    error_message = "The workflow must use only media worker roles, this ECS cluster, and its results queue."
  }
  assert {
    condition     = jsondecode(aws_iam_role.workflow.assume_role_policy).Statement[0].Principal.Service == "states.amazonaws.com" && jsondecode(aws_iam_role.workflow.assume_role_policy).Statement[0].Condition.ArnEquals["aws:SourceArn"] == local.workflow_arn
    error_message = "Only this account's media state machine may assume the workflow role."
  }
}

run "reject_wildcard_embedding_models" {
  command = plan
  variables { embedding_model_arn = "arn:aws:bedrock:us-east-1::foundation-model/*" }
  expect_failures = [var.embedding_model_arn]
}
run "reject_wildcard_analysis_models" {
  command = plan
  variables { analysis_model_arns = ["arn:aws:bedrock:us-east-1::foundation-model/*"] }
  expect_failures = [var.analysis_model_arns]
}

run "reject_private_signing_material" {
  command = plan
  variables { cloudfront_public_key = "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----" }
  expect_failures = [var.cloudfront_public_key]
}
run "reject_unbounded_storage" {
  command = plan
  variables { database_maximum_storage_gib = 1001 }
  expect_failures = [var.database_maximum_storage_gib]
}
run "reject_invalid_environment" {
  command = plan
  variables { environment = "experimental" }
  expect_failures = [var.environment]
}

run "account_spend_alerts_cover_every_region_and_service" {
  command = apply
  variables {
    monthly_budget_usd  = 125.50
    budget_alert_emails = ["owner@example.test", "operator@example.test"]
  }
  assert {
    condition     = aws_budgets_budget.account_spend.account_id == "123456789012" && aws_budgets_budget.account_spend.budget_type == "COST" && aws_budgets_budget.account_spend.limit_amount == "125.5" && aws_budgets_budget.account_spend.limit_unit == "USD" && aws_budgets_budget.account_spend.time_unit == "MONTHLY"
    error_message = "Configure the requested monthly USD spend alert for this account."
  }
  assert {
    condition     = length(aws_budgets_budget.account_spend.cost_filter) == 0 && !one(aws_budgets_budget.account_spend.cost_types).include_credit && !one(aws_budgets_budget.account_spend.cost_types).include_refund && one(aws_budgets_budget.account_spend.cost_types).include_tax && one(aws_budgets_budget.account_spend.cost_types).include_support && one(aws_budgets_budget.account_spend.cost_types).use_amortized
    error_message = "Count spend across all services and regions without depending on cost allocation tags or hiding costs behind credits/refunds."
  }
  assert {
    condition     = toset([for notification in aws_budgets_budget.account_spend.notification : "${notification.notification_type}:${notification.threshold}"]) == toset(["ACTUAL:80", "ACTUAL:100", "FORECASTED:100"]) && alltrue([for notification in aws_budgets_budget.account_spend.notification : notification.comparison_operator == "GREATER_THAN" && notification.threshold_type == "PERCENTAGE" && notification.subscriber_email_addresses == var.budget_alert_emails])
    error_message = "Alert both operators when actual spend passes 80/100 percent or forecasted spend passes the limit."
  }
}

run "reject_missing_budget_recipients" {
  command = plan
  variables { budget_alert_emails = [] }
  expect_failures = [var.budget_alert_emails]
}

run "reject_invalid_budget_recipients" {
  command = plan
  variables { budget_alert_emails = ["invalid-address"] }
  expect_failures = [var.budget_alert_emails]
}

run "reject_unbounded_budget" {
  command = plan
  variables { monthly_budget_usd = 10001 }
  expect_failures = [var.monthly_budget_usd]
}

run "reject_invalid_budget_amount" {
  command = plan
  variables { monthly_budget_usd = 0 }
  expect_failures = [var.monthly_budget_usd]
}
