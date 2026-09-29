mock_provider "aws" {
  mock_data "aws_caller_identity" { defaults = { account_id = "123456789012" } }
  mock_resource "aws_iam_openid_connect_provider" { defaults = { arn = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com" } }
}

run "migration_access_is_opt_in" {
  command = plan
  assert {
    condition     = length(aws_iam_role.github_migrator) == 0 && output.github_migrator_role_arn == null
    error_message = "Do not grant migration execution without explicit configuration."
  }
}
run "reject_migration_access_without_a_repository" {
  command = plan
  variables { github_migrations_enabled = true }
  expect_failures = [var.github_migrations_enabled]
}
run "development_migration_scope" {
  command = apply
  variables {
    github_repository         = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_migrations_enabled = true
  }
  assert {
    condition     = jsondecode(aws_iam_role.github_migrator[0].assume_role_policy).Statement[0].Condition.StringEquals["token.actions.githubusercontent.com:sub"] == "repo:wardrobe-owner@12345/closetos@67890:ref:refs/heads/main" && aws_iam_role.github_migrator[0].name == "closetos-dev-github-migrator"
    error_message = "Restrict development migration access to this repository's main branch."
  }
  assert {
    condition     = length(jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement) == 6 && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[1].Resource == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-dev-migration:*" && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[1].Condition.ArnEquals["ecs:cluster"] == "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev" && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[1].Condition.Bool["ecs:enable-execute-command"] == "false" && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[1].Condition.StringEquals["aws:RequestTag/Workload"] == "database-migration"
    error_message = "Launch only the environment's migration family, in its cluster, with tagging and remote exec disabled."
  }
  assert {
    condition     = jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[4].Condition.StringEquals["aws:ResourceTag/Workload"] == "database-migration" && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[4].Condition.StringEquals["aws:ResourceTag/Environment"] == "dev" && toset(jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[5].Resource) == toset(["arn:aws:iam::123456789012:role/closetos-dev-migration-task", "arn:aws:iam::123456789012:role/closetos-dev-migration-execution"]) && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[5].Condition.StringEquals["iam:PassedToService"] == "ecs-tasks.amazonaws.com" && alltrue([for s in jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement : !contains(s.Action, "secretsmanager:GetSecretValue") && !contains(s.Action, "ecs:UpdateService") && !contains(s.Action, "ecs:RegisterTaskDefinition") && !contains(s.Action, "s3:GetObject")])
    error_message = "Pass only the migration roles, stop only migration tasks, and never read credential values or mutate deployments."
  }
}
run "production_migration_requires_the_github_environment" {
  command   = apply
  state_key = "production"
  variables {
    environment               = "prod"
    github_repository         = { name = "wardrobe-owner/closetos", owner_id = 12345, repository_id = 67890 }
    github_oidc_provider_arn  = "arn:aws:iam::123456789012:oidc-provider/token.actions.githubusercontent.com"
    github_migrations_enabled = true
  }
  assert {
    condition     = output.github_migrator_subject == "repo:wardrobe-owner@12345/closetos@67890:environment:production" && aws_iam_role.github_migrator[0].name == "closetos-prod-github-migrator" && jsondecode(aws_iam_role_policy.github_migrator[0].policy).Statement[1].Resource == "arn:aws:ecs:eu-west-2:123456789012:task-definition/closetos-prod-migration:*"
    error_message = "Production migration credentials must come through the approval-protected production environment and cannot launch dev tasks."
  }
}
