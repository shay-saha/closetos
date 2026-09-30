mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_resource "aws_iam_openid_connect_provider" { defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" } }
  mock_resource "aws_iam_policy" { defaults = { arn = "arn:aws:iam::123456789012:policy/mock-configuration-read" } }
}

run "planning_access_is_opt_in" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_planner) == 0 && length(aws_iam_policy.github_configuration_read) == 0 && output.github_planner_role_arn == null && output.github_planner_subject == null
    error_message = "Do not create planning access without explicit configuration."
  }
}

run "reject_planning_without_a_repository" {
  command = plan
  variables {
    github_planning_enabled = true
    github_route53_zone_id  = "Z123PUBLIC"
  }
  expect_failures = [var.github_planning_enabled]
}

run "reject_planning_without_a_hosted_zone" {
  command = plan
  variables {
    github_repository       = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_planning_enabled = true
  }
  expect_failures = [var.github_route53_zone_id]
}

run "reject_hosted_zone_resource_injection" {
  command = plan
  variables { github_route53_zone_id = "Z123PUBLIC/*" }
  expect_failures = [var.github_route53_zone_id]
}

run "development_infrastructure_planning" {
  command = apply
  variables {
    github_repository       = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_planning_enabled = true
    github_route53_zone_id  = "Z123PUBLIC"
  }
  assert {
    condition     = aws_iam_role.github_planner[0].name == "closetos-dev-github-planner" && output.github_planner_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_planner[0].max_session_duration == 3600 && jsondecode(aws_iam_role.github_planner[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == output.github_planner_subject && jsondecode(aws_iam_role.github_planner[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Only the immutable repository's main branch may obtain short-lived planning credentials."
  }
  assert {
    condition     = length(aws_iam_role_policy_attachment.github_planner_read) == 2 && alltrue([for policy in aws_iam_policy.github_configuration_read : length(policy.policy) <= 6144]) && length(aws_iam_role_policy.github_planner_state[0].policy) <= 10240
    error_message = "Attach the application's configuration reads within AWS managed and inline policy size limits."
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[1].Action == ["s3:GetObject"] && jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[1].Resource == "arn:aws:s3:::closetos-dev-123456789012-state/dev/closetos.tfstate" && jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[2].Resource == "arn:aws:s3:::closetos-dev-123456789012-state/dev/closetos.tfstate.tflock" && toset(jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[2].Action) == toset(["s3:GetObject", "s3:PutObject", "s3:DeleteObject"])
    error_message = "The planner may read existing environment state and acquire its lock, but must never write or delete state."
  }
}

run "production_planning_precedes_environment_approval" {
  command   = apply
  state_key = "production"
  variables {
    environment              = "prod"
    github_repository        = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    github_planning_enabled  = true
    github_route53_zone_id   = "Z123PUBLIC"
  }
  assert {
    condition     = output.github_planner_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_planner[0].name == "closetos-prod-github-planner" && jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[1].Resource == "arn:aws:s3:::closetos-prod-123456789012-state/prod/closetos.tfstate"
    error_message = "The production plan must be available for review before approval, using only production state."
  }
}

run "planning_supports_custom_application_account_and_region" {
  command   = apply
  state_key = "custom"
  variables {
    application_name        = "abcdefghijklmnopqrst"
    environment             = "prod"
    region                  = "us-east-2"
    github_repository       = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_planning_enabled = true
    github_route53_zone_id  = "ZCUSTOMPUBLIC"
  }
  override_data {
    target = data.aws_caller_identity.current
    values = { account_id = "111122223333" }
  }
  override_resource {
    target = aws_iam_openid_connect_provider.github[0]
    values = { arn = "arn:aws:iam::111122223333:oidc-provider/token.actions.githubusercontent.com" }
  }
  assert {
    condition     = alltrue([for policy in aws_iam_policy.github_configuration_read : length(policy.policy) <= 6144]) && jsondecode(aws_iam_role_policy.github_planner_state[0].policy).Statement[1].Resource == "arn:aws:s3:::abcdefghijklmnopqrst-prod-111122223333-state/prod/closetos.tfstate"
    error_message = "Planning must use the configured application, account, and region while respecting policy size limits even at the longest application name."
  }
}
