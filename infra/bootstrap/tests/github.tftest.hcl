mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_resource "aws_iam_openid_connect_provider" {
    defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" }
  }
}

run "publishing_is_disabled_without_a_repository" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_publisher) == 0 && length(aws_iam_openid_connect_provider.github) == 0 && output.github_publisher_subject == null
    error_message = "Do not create federated publishing access until a repository is configured."
  }
}

run "main_branch_publishes_only_environment_images" {
  command = apply
  variables {
    github_repository = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
  }
  assert {
    condition     = aws_iam_openid_connect_provider.github[0].url == "https://token.actions.githubusercontent.com" && aws_iam_openid_connect_provider.github[0].client_id_list == toset(["sts.amazonaws.com"])
    error_message = "Trust only the GitHub issuer with the AWS STS audience."
  }
  assert {
    condition     = jsondecode(aws_iam_role.github_publisher[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && jsondecode(aws_iam_role.github_publisher[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com" && jsondecode(aws_iam_role.github_publisher[0].assume_role_policy).Statement[0].Principal.Federated == aws_iam_openid_connect_provider.github[0].arn && aws_iam_role.github_publisher[0].max_session_duration == 3600
    error_message = "Bind short-lived publishing credentials to the immutable owner/repository IDs and main branch."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement) == 2 && jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement[0].Action == ["ecr:GetAuthorizationToken"] && jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement[0].Resource == "*" && toset(jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement[1].Resource) == toset([for service in ["api", "web", "media-worker"] : "arn:aws:ecr:eu-west-2:123456789012:repository/closetos-dev/${service}"]) && toset(jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement[1].Action) == toset(["ecr:DescribeRepositories", "ecr:DescribeImages", "ecr:BatchGetImage", "ecr:BatchCheckLayerAvailability", "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"])
    error_message = "Publishing must not administer infrastructure, delete images, pass roles, or read secrets/media/state."
  }
}

run "reuse_github_provider_in_production" {
  command   = apply
  state_key = "production"
  variables {
    environment              = "prod"
    github_repository        = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
  }
  assert {
    condition     = length(aws_iam_openid_connect_provider.github) == 0 && output.github_oidc_provider_arn == var.github_oidc_provider_arn && toset(jsondecode(aws_iam_role_policy.github_publisher[0].policy).Statement[1].Resource) == toset([for service in ["api", "web", "media-worker"] : "arn:aws:ecr:eu-west-2:123456789012:repository/closetos-prod/${service}"])
    error_message = "Reuse the account's issuer while isolating each environment's repositories."
  }
}

run "reject_repository_subject_injection" {
  command = plan
  variables {
    github_repository = { name = "wardrobe-owner/closetos:pull_request", owner_id = 12345, repository_id = 67890 }
  }
  expect_failures = [var.github_repository]
}

run "reject_noninteger_repository_identity" {
  command = plan
  variables {
    github_repository = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 0.5 }
  }
  expect_failures = [var.github_repository]
}

run "reject_another_accounts_provider" {
  command = plan
  variables {
    github_repository        = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn = "arn:aws:iam::999999999999:oidc-provider/token.actions.githubusercontent.com"
  }
  expect_failures = [aws_iam_role.github_publisher]
}
