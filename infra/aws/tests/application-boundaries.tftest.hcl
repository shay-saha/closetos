mock_provider "aws" {
  source = "./tests"
  mock_data "aws_db_instance" {
    defaults = {
      db_instance_arn = "arn:aws:rds:eu-west-2:123456789012:db:closetos-dev"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000001-ABC123"
        secret_status = "active"
      }]
    }
  }
}

variables {
  application_permissions_boundaries_enabled = true
  app_domain                                 = "closet.example.test"
  cloudfront_public_key                      = file("tests/media-signing.pub")
  embedding_model_arn                        = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  analysis_model_arns                        = ["arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0"]
  budget_alert_emails                        = ["operator@example.test"]
}

run "bootstrap_runtime_permissions_boundaries" {
  command   = apply
  state_key = "bootstrap"
  module { source = "../bootstrap" }
}

run "bounded_application_roles_preserve_runtime_permissions" {
  command = apply
  override_resource {
    target = aws_db_instance.main
    values = {
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000001-ABC123"
        secret_status = "active"
      }]
    }
  }
  override_resource {
    target = aws_ecr_repository.application["api"]
    values = { arn = "arn:aws:ecr:eu-west-2:123456789012:repository/closetos-dev/api" }
  }
  override_resource {
    target = aws_ecr_repository.application["web"]
    values = { arn = "arn:aws:ecr:eu-west-2:123456789012:repository/closetos-dev/web" }
  }
  override_resource {
    target = aws_ecr_repository.application["media-worker"]
    values = { arn = "arn:aws:ecr:eu-west-2:123456789012:repository/closetos-dev/media-worker" }
  }
  override_resource {
    target = aws_secretsmanager_secret.application["database-app"]
    values = { arn = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/database-app-ABC123" }
  }
  override_resource {
    target = aws_secretsmanager_secret.application["web-session"]
    values = { arn = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/web-session-ABC123" }
  }
  override_resource {
    target = aws_secretsmanager_secret.application["media-signing"]
    values = { arn = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/media-signing-ABC123" }
  }
  override_resource {
    target = aws_iam_role.task["media-worker"]
    values = { arn = "arn:aws:iam::123456789012:role/closetos-dev-media-worker-task" }
  }
  override_resource {
    target = aws_iam_role.execution["media-worker"]
    values = { arn = "arn:aws:iam::123456789012:role/closetos-dev-media-worker-execution" }
  }
  override_resource {
    target = aws_sqs_queue.media["media-ingest"]
    values = { arn = "arn:aws:sqs:eu-west-2:123456789012:closetos-dev-media-ingest" }
  }
  override_resource {
    target = aws_sqs_queue.media["media-results"]
    values = { arn = "arn:aws:sqs:eu-west-2:123456789012:closetos-dev-media-results" }
  }
  override_resource {
    target = aws_cloudwatch_log_group.migration
    values = { arn = "arn:aws:logs:eu-west-2:123456789012:log-group:/ecs/closetos-dev/migration" }
  }
  assert {
    condition = alltrue([
      for role in concat(values(aws_iam_role.task), values(aws_iam_role.execution), [aws_iam_role.migration_task, aws_iam_role.migration_execution, aws_iam_role.workflow]) :
      role.permissions_boundary == "arn:aws:iam::123456789012:policy/${role.name}-permissions-boundary"
    ])
    error_message = "Every application role must attach its own bootstrap boundary without fallback to another role or environment."
  }
}
