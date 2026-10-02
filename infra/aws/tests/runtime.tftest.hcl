mock_provider "aws" { source = "./tests" }

override_resource {
  target = aws_iam_role.migration_task
  values = { arn = "arn:aws:iam::123456789012:role/migration-task" }
}
override_resource {
  target = aws_iam_role.migration_execution
  values = { arn = "arn:aws:iam::123456789012:role/migration-execution" }
}

override_resource {
  target = aws_iam_role.task["api"]
  values = { arn = "arn:aws:iam::123456789012:role/api-task" }
}
override_resource {
  target = aws_iam_role.task["web"]
  values = { arn = "arn:aws:iam::123456789012:role/web-task" }
}
override_resource {
  target = aws_iam_role.task["media-worker"]
  values = { arn = "arn:aws:iam::123456789012:role/media-task" }
}
override_resource {
  target = aws_iam_role.execution["api"]
  values = { arn = "arn:aws:iam::123456789012:role/api-execution" }
}
override_resource {
  target = aws_iam_role.execution["web"]
  values = { arn = "arn:aws:iam::123456789012:role/web-execution" }
}
override_resource {
  target = aws_iam_role.execution["media-worker"]
  values = { arn = "arn:aws:iam::123456789012:role/media-execution" }
}
override_resource {
  target = aws_iam_role.workflow
  values = { arn = "arn:aws:iam::123456789012:role/media-workflow" }
}

variables {
  app_domain            = "closet.example.test"
  cloudfront_public_key = file("tests/media-signing.pub")
  embedding_model_arn   = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  budget_alert_emails   = ["operator@example.test"]
  analysis_model_id     = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"
  analysis_model_arns   = ["arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"]
  route53_zone_id       = "ZEXAMPLE123"
  image_digests = {
    api          = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    web          = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    media-worker = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
}

run "runtime_preparation_keeps_services_stopped" {
  command = apply
  assert {
    condition     = length(aws_ecs_task_definition.migration) == 1 && aws_ecs_task_definition.migration[0].task_role_arn == aws_iam_role.migration_task.arn && aws_ecs_task_definition.migration[0].execution_role_arn == aws_iam_role.migration_execution.arn && jsondecode(aws_ecs_task_definition.migration[0].container_definitions)[0].image == "${aws_ecr_repository.application["api"].repository_url}@${var.image_digests["api"]}" && jsondecode(aws_ecs_task_definition.migration[0].container_definitions)[0].entryPoint == ["java"] && contains(jsondecode(aws_ecs_task_definition.migration[0].container_definitions)[0].command, "-Dloader.main=com.closetos.platform.infrastructure.DatabaseMigration") && jsondecode(aws_ecs_task_definition.migration[0].container_definitions)[0].readonlyRootFilesystem && length(jsondecode(aws_ecs_task_definition.migration[0].container_definitions)[0].secrets) == 2
    error_message = "Prepare a standalone migration task from the exact API image, with private startup credentials and separate roles."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.migration_execution.policy).Statement) == 4 && toset(jsondecode(aws_iam_role_policy.migration_execution.policy).Statement[3].Resource) == toset([aws_db_instance.main.master_user_secret[0].secret_arn, aws_secretsmanager_secret.application["database-app"].arn]) && alltrue([for statement in jsondecode(aws_iam_role_policy.migration_execution.policy).Statement : !contains(statement.Action, "s3:GetObject") && !contains(statement.Action, "bedrock:InvokeModel")])
    error_message = "Migration startup must not read application session/signing secrets, garment media, or invoke models."
  }
  assert {
    condition     = toset(keys(aws_ecs_service.application)) == toset(["api", "web"]) && alltrue([for service in aws_ecs_service.application : service.desired_count == 0]) && length(aws_appautoscaling_target.application) == 0
    error_message = "Prepare real task definitions without starting services or scaling before migrations and secret initialization."
  }
  assert {
    condition     = length(aws_ecs_task_definition.media) == 1 && length(aws_ecs_service.application) == 2 && jsondecode(aws_ecs_task_definition.media[0].container_definitions)[0].command == ["process"]
    error_message = "The media worker must be a RunTask definition, without a permanently running worker service."
  }
}

run "development_runtime_contract" {
  command = apply
  variables { services_enabled = true }
  assert {
    condition     = { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["CLOUDFRONT_DISTRIBUTION_ID"] == aws_cloudfront_distribution.media.id
    error_message = "Cache erasure must use the distribution serving the application's private media."
  }
  assert {
    condition     = { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["EMBEDDING_PROVIDER"] == "bedrock" && { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["BEDROCK_EMBEDDING_MODEL_ID"] == var.embedding_model_arn && { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["BEDROCK_EMBEDDING_REGION"] == "us-east-1"
    error_message = "Use direct Bedrock embeddings with the permitted model and its region, without a permanent worker service."
  }
  assert {
    condition     = alltrue([for service in aws_ecs_service.application : service.launch_type == "FARGATE" && !one(service.network_configuration).assign_public_ip && toset(one(service.network_configuration).subnets) == toset([for subnet in aws_subnet.application : subnet.id]) && service.desired_count == 0 && one(service.deployment_circuit_breaker).enable && one(service.deployment_circuit_breaker).rollback && service.deployment_minimum_healthy_percent == 100 && service.wait_for_steady_state])
    error_message = "Deploy only private Fargate services with health checks, uninterrupted rolling updates, and automatic failure rollback."
  }
  assert {
    condition     = alltrue([for name, task in aws_ecs_task_definition.service : jsondecode(task.container_definitions)[0].image == "${aws_ecr_repository.application[name].repository_url}@${var.image_digests[name]}" && jsondecode(task.container_definitions)[0].readonlyRootFilesystem && task.network_mode == "awsvpc" && task.task_role_arn == aws_iam_role.task[name].arn && task.execution_role_arn == aws_iam_role.execution[name].arn && jsondecode(task.container_definitions)[0].mountPoints[0].containerPath == "/tmp"])
    error_message = "Pin service images by digest, use separate roles, and keep root filesystems read-only with writable temporary storage."
  }
  assert {
    condition     = { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["DATABASE_URL"] == "jdbc:postgresql://${aws_db_instance.main.address}:5432/closetos?sslmode=verify-full&sslrootcert=/app/rds-global-bundle.pem" && { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["SPRING_FLYWAY_ENABLED"] == "false" && { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].secrets : item.name => item.valueFrom }["DATABASE_PASSWORD"] == aws_secretsmanager_secret.application["database-app"].arn && { for item in jsondecode(aws_ecs_task_definition.service["api"].container_definitions)[0].environment : item.name => item.value }["DATABASE_USERNAME"] == "closetos_app"
    error_message = "Verify RDS's server identity, load separate runtime credentials from Secrets Manager, and migrate before serving requests."
  }
  assert {
    condition     = { for item in jsondecode(aws_ecs_task_definition.service["web"].container_definitions)[0].environment : item.name => item.value }["API_URL"] == "http://api.closetos-dev.internal:8080" && { for item in jsondecode(aws_ecs_task_definition.service["web"].container_definitions)[0].environment : item.name => item.value }["NEXTAUTH_URL"] == "https://closet.example.test" && one(aws_ecs_service.application["api"].service_registries).registry_arn == aws_service_discovery_service.api[0].arn
    error_message = "The web BFF must use private API discovery and secure cookies on the public HTTPS origin."
  }
  assert {
    condition     = aws_lb_listener.http[0].default_action[0].redirect[0].protocol == "HTTPS" && aws_lb_listener.https[0].protocol == "HTTPS" && aws_lb_listener.https[0].ssl_policy == "ELBSecurityPolicy-TLS13-1-2-2021-06" && one(aws_lb_listener_rule.api[0].condition).path_pattern[0].values == toset(["/api/v1", "/api/v1/*"])
    error_message = "Redirect HTTP, enforce current TLS, and expose authenticated API paths without routing private actuator endpoints."
  }
  assert {
    condition     = alltrue([for target in aws_appautoscaling_target.application : target.min_capacity == 1 && target.max_capacity == 4]) && alltrue([for policy in aws_appautoscaling_policy.cpu : one(policy.target_tracking_scaling_policy_configuration).target_value == 65])
    error_message = "Keep development scaling bounded and driven by measured utilization."
  }
  assert {
    condition     = jsondecode(aws_ecs_task_definition.media[0].container_definitions)[0].readonlyRootFilesystem && aws_ecs_task_definition.media[0].task_role_arn == aws_iam_role.task["media-worker"].arn && { for item in jsondecode(aws_ecs_task_definition.media[0].container_definitions)[0].environment : item.name => item.value }["BEDROCK_ANALYSIS_REGION"] == "us-east-1"
    error_message = "Isolate worker permissions and choose the model's region independently of S3/RDS."
  }
  assert {
    condition     = aws_ecs_task_definition.media[0].cpu == "2048" && aws_ecs_task_definition.media[0].memory == "8192"
    error_message = "The CPU segmentation worker must use the two CPUs and 8 GiB verified by the native container gate."
  }
  assert {
    condition     = jsondecode(aws_sfn_state_machine.media[0].definition).States.RunMediaTransform.Resource == "arn:aws:states:::ecs:runTask.sync" && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunMediaTransform.Parameters.Overrides.ContainerOverrides[0].Command == ["transform"] && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunAIEnrichment.Parameters.Overrides.ContainerOverrides[0].Command == ["enrich"] && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunMediaTransform.ResultPath == null && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunAIEnrichment.ResultPath == null
    error_message = "Run transform and enrichment separately and retain the original job across ECS completion responses."
  }
  assert {
    condition = alltrue([for stage in ["RunMediaTransform", "RunAIEnrichment"] :
      jsondecode(aws_sfn_state_machine.media[0].definition).States[stage].Parameters["StartedBy.$"] == "$.job.jobId" &&
      { for tag in jsondecode(aws_sfn_state_machine.media[0].definition).States[stage].Parameters.Tags : tag.Key => try(tag.Value, tag["Value.$"]) } == {
        Application = "closetos", Environment = "dev", ClosetosWorker = "media", ClosetosJob = "$.job.jobId"
      }
    ])
    error_message = "Both worker stages and retries must carry their job identity and environment-scoped cancellation tags."
  }
  assert {
    condition     = jsondecode(aws_sfn_state_machine.media[0].definition).States.RunMediaTransform.Parameters.NetworkConfiguration.AwsvpcConfiguration.AssignPublicIp == "DISABLED" && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunMediaTransform.Parameters.Overrides.ContainerOverrides[0].Environment[0]["Value.$"] == "States.JsonToString($.job)" && jsondecode(aws_sfn_state_machine.media[0].definition).States.RunAIEnrichment.Catch[0].Next == "PrepareProcessingFailure" && !contains(jsondecode(aws_sfn_state_machine.media[0].definition).States.RunAIEnrichment.Retry[0].ErrorEquals, "States.TaskFailed")
    error_message = "Pass only the validated job to private tasks and publish failures without repeatedly running invalid media or inference."
  }
  assert {
    condition     = jsondecode(aws_sfn_state_machine.media[0].definition).States.PrepareProcessingResult.Parameters["manifestKey.$"] == "States.Format('{}manifest.json', $.job.outputPrefix)" && jsondecode(aws_sfn_state_machine.media[0].definition).States.PrepareProcessingFailure.Parameters.status == "FAILED" && jsondecode(aws_sfn_state_machine.media[0].definition).States.PublishProcessingResult.Parameters.QueueUrl == aws_sqs_queue.media["media-results"].url && jsondecode(aws_sfn_state_machine.media[0].definition).States.PublishProcessingFailure.Parameters.QueueUrl == aws_sqs_queue.media["media-results"].url && jsondecode(aws_sfn_state_machine.media[0].definition).States.PrepareInput.Parameters["eventId.$"] == "States.UUID()"
    error_message = "Publish result messages compatible with the consumer and retain one event ID across delivery retries."
  }
  assert {
    condition     = !one(aws_sfn_state_machine.media[0].logging_configuration).include_execution_data && one(aws_sfn_state_machine.media[0].tracing_configuration).enabled && jsondecode(aws_sfn_state_machine.media[0].definition).TimeoutSeconds == 3600
    error_message = "Bound processing time and trace operations without logging private input/output payloads."
  }
}

run "production_runtime_redundancy" {
  command   = apply
  state_key = "production"
  variables {
    environment      = "prod"
    services_enabled = true
  }
  assert {
    condition     = alltrue([for service in aws_ecs_service.application : service.desired_count == 0]) && alltrue([for target in aws_appautoscaling_target.application : target.min_capacity == 2]) && aws_lb.application[0].enable_deletion_protection && aws_ecs_task_definition.service["api"].memory == "2048"
    error_message = "Prepare stopped services, require two healthy instances when released, and protect production ingress."
  }
}

run "reject_mutable_image_tags" {
  command = plan
  variables { image_digests = { api = "latest", web = "latest", media-worker = "latest" } }
  expect_failures = [var.image_digests]
}
run "reject_partial_release" {
  command = plan
  variables { image_digests = { api = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" } }
  expect_failures = [var.image_digests]
}
run "reject_unpermitted_analysis_model" {
  command = plan
  variables { analysis_model_id = "arn:aws:bedrock:us-east-1::foundation-model/not-permitted" }
  expect_failures = [var.analysis_model_id]
}
