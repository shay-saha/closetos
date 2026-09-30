variable "github_secret_initialization_enabled" {
  type        = bool
  default     = false
  description = "Enable the separate GitHub role that initializes and verifies only application password, session, and media-signing secrets. Production uses the protected production GitHub Environment."
  validation {
    condition     = !var.github_secret_initialization_enabled || var.github_repository != null
    error_message = "Configure the immutable GitHub repository identity before enabling application secret initialization."
  }
}

locals {
  initializer_name    = "${var.application_name}-${var.environment}"
  initializer_subject = local.github_enabled ? (var.environment == "prod" ? replace(local.github_subject, ":ref:refs/heads/main", ":environment:production") : local.github_subject) : null
  initializer_secrets = [for secret in ["database-app", "web-session", "media-signing"] :
    "arn:aws:secretsmanager:${var.region}:${data.aws_caller_identity.current.account_id}:secret:${local.initializer_name}/${secret}-??????"
  ]
}

resource "aws_iam_role" "github_secret_initializer" {
  count                = var.github_secret_initialization_enabled ? 1 : 0
  name                 = "${local.initializer_name}-github-secret-initializer"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = { StringEquals = {
        "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
        "token.actions.githubusercontent.com:sub" = local.initializer_subject
      } }
    }]
  })
}

resource "aws_iam_role_policy" "github_secret_initializer" {
  count = var.github_secret_initialization_enabled ? 1 : 0
  name  = "initialize-only-application-secrets"
  role  = aws_iam_role.github_secret_initializer[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["secretsmanager:DescribeSecret", "secretsmanager:GetSecretValue", "secretsmanager:PutSecretValue"]
      Resource = local.initializer_secrets
    }]
  })
}

output "github_secret_initializer_role_arn" { value = one(aws_iam_role.github_secret_initializer[*].arn) }
output "github_secret_initializer_subject" { value = var.github_secret_initialization_enabled ? local.initializer_subject : null }
