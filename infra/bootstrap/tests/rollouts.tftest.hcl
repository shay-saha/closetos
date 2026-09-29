mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_resource "aws_iam_openid_connect_provider" { defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" } }
}

run "rollout_access_is_opt_in" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_deployer) == 0 && output.github_deployer_role_arn == null
    error_message = "Do not grant release mutation privileges without explicit configuration."
  }
}
run "reject_rollout_access_without_a_repository" {
  command = plan
  variables { github_rollouts_enabled = true }
  expect_failures = [var.github_rollouts_enabled]
}
run "development_rollout_scope" {
  command = apply
  variables {
    github_repository       = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_rollouts_enabled = true
  }
  assert {
    condition     = output.github_deployer_subject == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_deployer[0].name == "closetos-dev-github-deployer" && jsondecode(aws_iam_role.github_deployer[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:aud"] == "sts.amazonaws.com"
    error_message = "Restrict development release access to this immutable repository's main branch and the AWS audience."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement) == 7 && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[5].Resource == "arn:aws:ecs:eu-west-2:123456789012:service/closetos-dev/closetos-dev-api" && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[5].Condition.ArnLike["ecs:task-definition"] == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-api:*" && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[6].Resource == "arn:aws:ecs:eu-west-2:123456789012:service/closetos-dev/closetos-dev-web" && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[6].Condition.ArnLike["ecs:task-definition"] == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-web:*" && alltrue([for statement in slice(jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement, 5, 7) : statement.Condition.ArnEquals["ecs:cluster"] == "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev" && statement.Condition.Bool["ecs:enable-execute-command"] == "false"])
    error_message = "Each service may receive only its own task family, with remote exec disabled and the exact cluster required."
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[3].Resource == "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-dev-media" && toset(jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[4].Resource) == toset([for role in ["api-task", "api-execution", "web-task", "web-execution"] : "arn:aws:iam::123456789012:role/closetos-dev-${role}"]) && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[4].Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com" && alltrue([for statement in jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement : alltrue([for forbidden in ["ecs:RunTask", "ecs:RegisterTaskDefinition", "states:StartExecution", "secretsmanager:GetSecretValue", "s3:GetObject", "iam:CreateRole"] : !contains(statement.Action, forbidden)])])
    error_message = "Allow only the own media workflow and service role passing, without task creation, secrets, state access, or IAM administration."
  }
}
run "production_rollout_requires_the_github_environment" {
  command   = apply
  state_key = "production"
  variables {
    environment              = "prod"
    github_repository        = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    github_rollouts_enabled  = true
  }
  assert {
    condition     = output.github_deployer_subject == "repo:wardrobe-owner@12345/closetos@67890:environment:production" && aws_iam_role.github_deployer[0].name == "closetos-prod-github-deployer" && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[5].Resource == "arn:aws:ecs:eu-west-2:123456789012:service/closetos-prod/closetos-prod-api" && jsondecode(aws_iam_role_policy.github_deployer[0].policy).Statement[3].Resource == "arn:aws:states:eu-west-2:123456789012:stateMachine:closetos-prod-media"
    error_message = "Production release credentials must use the protected environment and cannot mutate development resources."
  }
}
