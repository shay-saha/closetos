mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_resource "aws_iam_openid_connect_provider" { defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" } }
  mock_resource "aws_iam_policy" { defaults = { arn = "arn:aws:iam::123456789012:policy/mock-infrastructure" } }
  mock_data "aws_db_instance" {
    defaults = {
      db_instance_arn    = "arn:aws:rds:eu-west-2:123456789012:db:closetos-dev"
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000001-ABC123", secret_status = "active" }]
    }
  }
  mock_data "aws_vpc" {
    defaults = { id = "vpc-00000000000000001", arn = "arn:aws:ec2:eu-west-2:123456789012:vpc/vpc-00000000000000001" }
  }
}

variables {
  github_repository                          = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
  github_planning_enabled                    = true
  github_route53_zone_id                     = "Z123PUBLIC"
  github_app_domain                          = "closet.example.test"
  application_permissions_boundaries_enabled = true
  embedding_model_arn                        = "arn:aws:bedrock:us-east-1::foundation-model/amazon.titan-embed-image-v1"
  analysis_model_arns                        = ["arn:aws:bedrock:us-east-1::foundation-model/amazon.nova-lite-v1:0"]
  github_infrastructure_enabled              = true
}

run "infrastructure_changes_are_opt_in" {
  command = plan
  variables { github_infrastructure_enabled = false }
  assert {
    condition     = length(aws_iam_role.github_infrastructure) == 0 && length(aws_iam_policy.github_infrastructure) == 0 && length(data.aws_vpc.infrastructure) == 0 && output.github_infrastructure_role_arn == null
    error_message = "Do not grant infrastructure mutation privileges without explicit configuration."
  }
}

run "reject_apply_access_without_planning" {
  command = plan
  variables { github_planning_enabled = false }
  expect_failures = [var.github_infrastructure_enabled]
}
run "reject_apply_access_without_application_boundaries" {
  command = plan
  variables { application_permissions_boundaries_enabled = false }
  expect_failures = [var.github_infrastructure_enabled]
}
run "reject_apply_access_without_a_domain" {
  command = plan
  variables { github_app_domain = null }
  expect_failures = [var.github_app_domain]
}
run "reject_domain_permission_injection" {
  command = plan
  variables { github_app_domain = "*.example.test" }
  expect_failures = [var.github_app_domain]
}

run "development_infrastructure_changes" {
  command = apply
  assert {
    condition     = output.github_infrastructure_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_infrastructure[0].max_session_duration == 3600 && jsondecode(aws_iam_role.github_infrastructure[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Bind short-lived development infrastructure credentials to the immutable main-branch identity."
  }
  assert {
    condition     = alltrue([for policy in aws_iam_policy.github_infrastructure : length(policy.policy) <= 6144]) && length(aws_iam_role_policy.github_infrastructure_guardrails[0].policy) <= 10240 && length(aws_iam_role_policy_attachment.github_infrastructure_write) + length(aws_iam_role_policy_attachment.github_infrastructure_read) <= 10
    error_message = "Infrastructure access must respect AWS managed-policy size, role attachment, and inline-policy limits."
  }
}

run "production_infrastructure_requires_environment_approval" {
  command   = apply
  state_key = "production"
  variables { environment = "prod" }
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = {
      db_instance_arn    = "arn:aws:rds:eu-west-2:123456789012:db:closetos-prod"
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:rds!db-00000000-0000-4000-8000-000000000002-ABC123", secret_status = "active" }]
    }
  }
  override_data {
    target = data.aws_vpc.infrastructure[0]
    values = { id = "vpc-00000000000000002", arn = "arn:aws:ec2:eu-west-2:123456789012:vpc/vpc-00000000000000002" }
  }
  assert {
    condition     = output.github_infrastructure_subject == "repo:wardrobe-owner@12345/closetos@67890:environment:production" && aws_iam_role.github_infrastructure[0].name == "closetos-prod-github-infrastructure" && output.github_planner_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main"
    error_message = "Require production approval for applying while retaining a main-branch plan for review before approval."
  }
  assert {
    condition     = alltrue([for policy in aws_iam_policy.github_infrastructure : length(policy.policy) <= 6144]) && length(aws_iam_role_policy.github_infrastructure_guardrails[0].policy) <= 10240 && length(aws_iam_role_policy_attachment.github_infrastructure_write) + length(aws_iam_role_policy_attachment.github_infrastructure_read) <= 10
    error_message = "Infrastructure access must respect AWS managed-policy size, role attachment, and inline-policy limits."
  }
}

run "infrastructure_supports_custom_application_account_and_region" {
  command   = apply
  state_key = "custom"
  variables {
    application_name  = "abcdefghijklmnopqrst"
    environment       = "prod"
    region            = "ap-southeast-2"
    github_app_domain = "wardrobe.example.test"
  }
  override_data {
    target = data.aws_caller_identity.current
    values = { account_id = "987654321098" }
  }
  override_resource {
    target = aws_iam_openid_connect_provider.github[0]
    values = { arn = "arn:aws:iam::987654321098:oidc-provider/token.actions.githubusercontent.com" }
  }
  override_data {
    target = data.aws_db_instance.boundary_database[0]
    values = {
      db_instance_arn    = "arn:aws:rds:ap-southeast-2:987654321098:db:abcdefghijklmnopqrst-prod"
      master_user_secret = [{ secret_arn = "arn:aws:secretsmanager:ap-southeast-2:987654321098:secret:rds!db-00000000-0000-4000-8000-000000000003-ABC123", secret_status = "active" }]
    }
  }
  override_data {
    target = data.aws_vpc.infrastructure[0]
    values = { id = "vpc-00000000000000003", arn = "arn:aws:ec2:ap-southeast-2:987654321098:vpc/vpc-00000000000000003" }
  }
  assert {
    condition     = alltrue([for policy in aws_iam_policy.github_infrastructure : length(policy.policy) <= 6144]) && length(aws_iam_role_policy.github_infrastructure_guardrails[0].policy) <= 10240 && length(aws_iam_role_policy_attachment.github_infrastructure_write) + length(aws_iam_role_policy_attachment.github_infrastructure_read) <= 10
    error_message = "Infrastructure access must respect AWS managed-policy size, role attachment, and inline-policy limits."
  }
}

run "reject_overlong_domain_labels" {
  command = plan
  variables { github_app_domain = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijkl.example.test" }
  expect_failures = [var.github_app_domain]
}
run "reject_malformed_domain_labels" {
  command = plan
  variables { github_app_domain = "closet-.example.test" }
  expect_failures = [var.github_app_domain]
}
