mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
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
  embedding_model_arn                        = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  analysis_model_arns = [
    "arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0",
    "arn:aws:bedrock:us-east-1:123456789012:inference-profile/us.amazon.nova-lite-v1:0"
  ]
}

run "initial_foundation_can_precede_boundaries" {
  command = plan
  variables { application_permissions_boundaries_enabled = false }
  assert {
    condition     = length(aws_iam_policy.application_permissions_boundary) == 0 && length(data.aws_db_instance.boundary_database) == 0 && output.application_permissions_boundary_arns == {}
    error_message = "The first bootstrap must be possible before the foundation database exists."
  }
}

run "development_application_boundaries" {
  command = apply
  assert {
    condition     = length(aws_iam_policy.application_permissions_boundary) == 9 && length(output.application_permissions_boundary_arns) == 9 && alltrue([for policy in aws_iam_policy.application_permissions_boundary : length(policy.policy) <= 6144])
    error_message = "Every application role needs a separate, validly sized maximum-permission policy."
  }
  assert {
    condition     = data.aws_db_instance.boundary_database[0].db_instance_identifier == "closetos-dev" && jsondecode(aws_iam_policy.application_permissions_boundary["migration-execution"].policy).Statement[3].Resource[0] == data.aws_db_instance.boundary_database[0].master_user_secret[0].secret_arn
    error_message = "Migration startup may read only the environment database's actual managed master secret."
  }
  assert {
    condition     = jsondecode(aws_iam_policy.application_permissions_boundary["web-task"].policy).Statement[0].Effect == "Deny" && jsondecode(aws_iam_policy.application_permissions_boundary["migration-task"].policy).Statement[0].Effect == "Deny"
    error_message = "Roles that need no AWS API access must remain unable to acquire it through identity-policy changes."
  }
}

run "production_application_boundaries" {
  command   = apply
  state_key = "production"
  variables { environment = "prod" }
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = {
      db_instance_arn = "arn:aws:rds:eu-west-2:123456789012:db:closetos-prod"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000002-ABC123"
        secret_status = "active"
      }]
    }
  }
  assert {
    condition     = alltrue([for key, policy in aws_iam_policy.application_permissions_boundary : policy.name == "closetos-prod-${key}-permissions-boundary"])
    error_message = "Production must use its own policies and database secret, independently of development."
  }
}

run "reject_missing_embedding_permission" {
  command = plan
  variables { embedding_model_arn = null }
  expect_failures = [var.embedding_model_arn]
}

run "reject_empty_analysis_permissions" {
  command = plan
  variables { analysis_model_arns = [] }
  expect_failures = [var.analysis_model_arns]
}

run "reject_wildcard_analysis_permissions" {
  command = plan
  variables { analysis_model_arns = ["arn:aws:bedrock:us-east-1::foundation-model/*"] }
  expect_failures = [var.analysis_model_arns]
}

run "reject_foreign_inference_profile" {
  command = plan
  variables { analysis_model_arns = ["arn:aws:bedrock:us-east-1:999999999999:inference-profile/us.amazon.nova-lite-v1:0"] }
  expect_failures = [aws_iam_policy.application_permissions_boundary]
}

run "reject_another_environments_database_metadata" {
  command = plan
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = { db_instance_arn = "arn:aws:rds:eu-west-2:123456789012:db:closetos-prod" }
  }
  expect_failures = [data.aws_db_instance.boundary_database]
}

run "reject_foreign_master_secret_metadata" {
  command = plan
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = {
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:999999999999:secret:rds!db-00000000-0000-4000-8000-000000000001-ABC123"
        secret_status = "active"
      }]
    }
  }
  expect_failures = [data.aws_db_instance.boundary_database]
}

run "reject_an_impaired_master_secret" {
  command = plan
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = {
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000001-ABC123"
        secret_status = "impaired"
      }]
    }
  }
  expect_failures = [data.aws_db_instance.boundary_database]
}
