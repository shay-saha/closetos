variable "github_repository" {
  type = object({
    name          = string
    owner_id      = number
    repository_id = number
  })
  default     = null
  description = "Optional owner/repository and immutable numeric GitHub IDs for main-branch publishing. Enable GitHub's immutable OIDC subject format for repositories created before July 2026."
  validation {
    condition = var.github_repository == null ? true : (
      can(regex("^[A-Za-z0-9-]+/[A-Za-z0-9_.-]+$", var.github_repository.name)) &&
      var.github_repository.owner_id > 0 && floor(var.github_repository.owner_id) == var.github_repository.owner_id &&
      var.github_repository.repository_id > 0 && floor(var.github_repository.repository_id) == var.github_repository.repository_id
    )
    error_message = "Provide an exact owner/repository and positive integer owner/repository IDs."
  }
}

variable "github_oidc_provider_arn" {
  type        = string
  default     = null
  description = "Reuse the account's existing GitHub OIDC provider when bootstrapping another environment. Leave null to create it."
  validation {
    condition     = var.github_oidc_provider_arn == null ? true : can(regex("^arn:aws:iam::[0-9]{12}:oidc-provider/token\\.actions\\.githubusercontent\\.com$", var.github_oidc_provider_arn))
    error_message = "Provide an exact AWS GitHub OIDC provider ARN."
  }
}

locals {
  github_enabled = var.github_repository != null
  github_subject = local.github_enabled ? format(
    "repo:%s@%.0f/%s@%.0f:ref:refs/heads/main",
    split("/", var.github_repository.name)[0], var.github_repository.owner_id,
    split("/", var.github_repository.name)[1], var.github_repository.repository_id
  ) : null
  github_provider = local.github_enabled ? (
    var.github_oidc_provider_arn == null ? one(aws_iam_openid_connect_provider.github[*].arn) : var.github_oidc_provider_arn
  ) : null
  release_repositories = [for service in ["api", "web", "media-worker"] :
    "arn:aws:ecr:${var.region}:${data.aws_caller_identity.current.account_id}:repository/${var.application_name}-${var.environment}/${service}"
  ]
}

resource "aws_iam_openid_connect_provider" "github" {
  count          = local.github_enabled && var.github_oidc_provider_arn == null ? 1 : 0
  url            = "https://token.actions.githubusercontent.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "github_publisher" {
  count                = local.github_enabled ? 1 : 0
  name                 = "${var.application_name}-${var.environment}-github-publisher"
  max_session_duration = 3600
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = local.github_provider }
      Action    = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "token.actions.githubusercontent.com:aud" = "sts.amazonaws.com"
          "token.actions.githubusercontent.com:sub" = local.github_subject
        }
      }
    }]
  })
  lifecycle {
    precondition {
      condition     = local.github_provider == "arn:aws:iam::${data.aws_caller_identity.current.account_id}:oidc-provider/token.actions.githubusercontent.com"
      error_message = "The GitHub OIDC provider must belong to this AWS account."
    }
  }
}

resource "aws_iam_role_policy" "github_publisher" {
  count = local.github_enabled ? 1 : 0
  name  = "publish-scanned-release-images"
  role  = aws_iam_role.github_publisher[0].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      {
        Effect = "Allow"
        Action = [
          "ecr:DescribeRepositories", "ecr:DescribeImages", "ecr:BatchGetImage", "ecr:BatchCheckLayerAvailability",
          "ecr:InitiateLayerUpload", "ecr:UploadLayerPart", "ecr:CompleteLayerUpload", "ecr:PutImage"
        ]
        Resource = local.release_repositories
      }
    ]
  })
}

output "github_oidc_provider_arn" { value = local.github_provider }
output "github_publisher_role_arn" { value = one(aws_iam_role.github_publisher[*].arn) }
output "github_publisher_subject" { value = local.github_subject }
