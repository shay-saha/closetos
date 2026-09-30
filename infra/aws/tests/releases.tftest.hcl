mock_provider "aws" { source = "./tests" }

variables {
  app_domain            = "closet.example.test"
  cloudfront_public_key = file("tests/media-signing.pub")
  embedding_model_arn   = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  analysis_model_id     = "arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"
  analysis_model_arns   = ["arn:aws:bedrock:us-east-1::foundation-model/anthropic.claude-sonnet-4-5-20250929-v1:0"]
  budget_alert_emails   = ["operator@example.test"]
  route53_zone_id       = "ZEXAMPLE123"
  image_digests = {
    api          = "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    web          = "sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    media-worker = "sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
  }
}

run "prepare_initial_release_with_no_running_tasks" {
  command = apply
  override_resource {
    target = aws_ecs_task_definition.service["api"]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:7" }
  }
  override_resource {
    target = aws_ecs_task_definition.service["web"]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-web:7" }
  }
  override_resource {
    target = aws_ecs_task_definition.media[0]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-media:7" }
  }
  assert {
    condition     = alltrue([for service in aws_ecs_service.application : service.desired_count == 0]) && aws_ecs_service.application["api"].task_definition == output.service_task_definitions.api && output.processing_workflow_definition == jsondecode(aws_sfn_state_machine.media[0].definition)
    error_message = "The first preparation creates stopped services and an initial processing workflow."
  }
}

run "prepare_new_images_without_releasing_them" {
  command = apply
  plan_options {
    replace = [aws_ecs_task_definition.service["api"], aws_ecs_task_definition.service["web"], aws_ecs_task_definition.media[0]]
  }
  variables {
    image_digests = {
      api          = "sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
      web          = "sha256:eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
      media-worker = "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
  }
  override_resource {
    target = aws_ecs_task_definition.service["api"]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:8" }
  }
  override_resource {
    target = aws_ecs_task_definition.service["web"]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-web:8" }
  }
  override_resource {
    target = aws_ecs_task_definition.media[0]
    values = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-media:8" }
  }
  assert {
    condition     = aws_ecs_service.application["api"].task_definition == run.prepare_initial_release_with_no_running_tasks.service_task_definitions.api && aws_ecs_service.application["web"].task_definition == run.prepare_initial_release_with_no_running_tasks.service_task_definitions.web && output.service_task_definitions.api == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:8" && output.service_task_definitions.web == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-web:8"
    error_message = "New definitions must be available to the migration-gated runner while services keep their previous release revisions."
  }
  assert {
    condition     = jsondecode(aws_sfn_state_machine.media[0].definition) == run.prepare_initial_release_with_no_running_tasks.processing_workflow_definition && output.processing_workflow_definition.States.RunMediaTransform.Parameters.TaskDefinition == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-media:8" && output.processing_workflow_definition.States.RunAIEnrichment.Parameters.TaskDefinition == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-media:8"
    error_message = "Expose the desired workflow separately while the active workflow keeps the previous worker until the release gate."
  }
  assert {
    condition     = alltrue([for task in aws_ecs_task_definition.service : task.skip_destroy]) && aws_ecs_task_definition.media[0].skip_destroy && aws_ecs_task_definition.migration[0].skip_destroy && alltrue([for service in aws_ecs_service.application : service.desired_count == 0])
    error_message = "Keep rollback task definitions registered and never start tasks during release preparation."
  }
}
