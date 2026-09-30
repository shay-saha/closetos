mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_resource "aws_iam_openid_connect_provider" { defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" } }
}

run "secret_initialization_access_is_opt_in" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_secret_initializer) == 0 && output.github_secret_initializer_role_arn == null
    error_message = "Do not grant secret value access without explicit configuration."
  }
}
run "reject_secret_initialization_without_a_repository" {
  command = plan
  variables { github_secret_initialization_enabled = true }
  expect_failures = [var.github_secret_initialization_enabled]
}
run "initialize_only_the_three_environment_application_secrets" {
  command = apply
  variables {
    github_repository                    = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_secret_initialization_enabled = true
  }
  assert {
    condition     = output.github_secret_initializer_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_secret_initializer[0].name == "closetos-dev-github-secret-initializer" && jsondecode(aws_iam_role.github_secret_initializer[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Restrict initialization to the immutable main-branch identity and the AWS audience."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.github_secret_initializer[0].policy).Statement) == 1 && toset(jsondecode(aws_iam_role_policy.github_secret_initializer[0].policy).Statement[0].Action) == toset(["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]) && toset(jsondecode(aws_iam_role_policy.github_secret_initializer[0].policy).Statement[0].Resource) == toset([for key in ["database-app", "web-session", "media-signing"] : "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-dev/${key}-??????"])
    error_message = "Initialize exactly three application secrets without master database credentials, secret deletion, ECS, Terraform state, or IAM administration."
  }
}
run "production_initialization_requires_the_github_environment" {
  command   = apply
  state_key = "production"
  variables {
    environment                          = "prod"
    github_repository                    = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn             = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    github_secret_initialization_enabled = true
  }
  assert {
    condition     = output.github_secret_initializer_subject == "repo:wardrobe-owner@12345/closetos@67890:environment:production" && aws_iam_role.github_secret_initializer[0].name == "closetos-prod-github-secret-initializer" && alltrue([for resource in jsondecode(aws_iam_role_policy.github_secret_initializer[0].policy).Statement[0].Resource : startswith(resource, "arn:aws:secretsmanager:eu-west-2:123456789012:secret:closetos-prod/")])
    error_message = "Production value access requires the protected environment and cannot access development secrets."
  }
}
