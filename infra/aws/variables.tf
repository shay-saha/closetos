variable "application_name" {
  type    = string
  default = "closetos"
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{2,19}$", var.application_name))
    error_message = "Use a 3–20 character lowercase application name."
  }
}
variable "environment" {
  type    = string
  default = "dev"
  validation {
    condition     = contains(["dev", "prod"], var.environment)
    error_message = "Choose dev or prod."
  }
}
variable "region" {
  type    = string
  default = "eu-west-2"
}
variable "vpc_cidr" {
  type    = string
  default = "10.42.0.0/16"
  validation {
    condition     = can(cidrsubnet(var.vpc_cidr, 8, 21)) && endswith(var.vpc_cidr, "/16")
    error_message = "Provide an IPv4 /16 network for the application."
  }
}
variable "app_domain" {
  type = string
  validation {
    condition     = can(regex("^[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}$", var.app_domain)) && !strcontains(var.app_domain, "..")
    error_message = "Provide the application's lowercase fully qualified domain name."
  }
}
variable "cloudfront_public_key" {
  type        = string
  description = "PEM RSA-2048 public signing key; keep its private key in Secrets Manager."
  validation {
    condition     = startswith(trimspace(var.cloudfront_public_key), "-----BEGIN PUBLIC KEY-----")
    error_message = "Provide the public PEM signing key, never a private key."
  }
}
variable "database_instance_class" {
  type    = string
  default = "db.t4g.micro"
}
variable "database_engine_version" {
  type    = string
  default = "18.3"
  validation {
    condition     = can(regex("^18\\.[0-9]+", var.database_engine_version))
    error_message = "Use a supported PostgreSQL 18 engine version."
  }
}
variable "database_maximum_storage_gib" {
  type    = number
  default = 100
  validation {
    condition     = var.database_maximum_storage_gib >= 20 && var.database_maximum_storage_gib <= 1000
    error_message = "Automatic storage growth must be bounded between 20 and 1,000 GiB."
  }
}
variable "log_retention_days" {
  type    = number
  default = 30
  validation {
    condition     = contains([7, 14, 30, 60, 90, 180, 365], var.log_retention_days)
    error_message = "Choose a supported retention period of 7–365 days."
  }
}
variable "embedding_model_arn" {
  type        = string
  description = "Regional Titan multimodal embedding model ARN; model access must be enabled in that region."
  validation {
    condition     = can(regex("^arn:aws(-[a-z]+)?:bedrock:[a-z0-9-]+::foundation-model/amazon\\.titan-embed-image-v1$", var.embedding_model_arn))
    error_message = "Provide the regional Amazon Titan multimodal embedding foundation-model ARN."
  }
}
variable "analysis_model_arns" {
  type        = set(string)
  default     = []
  description = "Exact Bedrock analysis/profile and underlying regional model ARNs to permit."
  validation {
    condition     = length(var.analysis_model_arns) <= 16 && alltrue([for arn in var.analysis_model_arns : can(regex("^arn:aws(-[a-z]+)?:bedrock:[a-z0-9-]+:([0-9]{12})?:(foundation-model|inference-profile|application-inference-profile)/[A-Za-z0-9._:/-]+$", arn))])
    error_message = "Provide at most sixteen exact Bedrock model/profile ARNs without wildcards."
  }
}
variable "image_digests" {
  type        = map(string)
  default     = {}
  description = "SHA-256 image digests in this environment's API, web, and media-worker ECR repositories. Leave empty to provision the foundation before building images."
  validation {
    condition     = length(var.image_digests) == 0 || (toset(keys(var.image_digests)) == toset(["api", "web", "media-worker"]) && alltrue([for digest in var.image_digests : can(regex("^sha256:[0-9a-f]{64}$", digest))]))
    error_message = "Provide all three immutable sha256 image digests, or an empty map for initial provisioning."
  }
  validation {
    condition     = length(var.image_digests) == 0 || (var.route53_zone_id != null && var.analysis_model_id != null)
    error_message = "Runtime deployment requires the application's public Route 53 zone and a configured analysis model."
  }
}
variable "services_enabled" {
  type        = bool
  default     = false
  description = "Start API/web services only after secrets are initialized and database migrations succeed."
}
variable "route53_zone_id" {
  type    = string
  default = null
  validation {
    condition     = var.route53_zone_id == null ? true : can(regex("^Z[A-Z0-9]+$", var.route53_zone_id))
    error_message = "Provide a public Route 53 hosted zone ID."
  }
}
variable "analysis_model_id" {
  type        = string
  default     = null
  description = "Bedrock model/profile ARN supporting Converse with a strict tool schema. Include it and underlying cross-region models in analysis_model_arns."
  validation {
    condition     = var.analysis_model_id == null ? true : contains(var.analysis_model_arns, var.analysis_model_id)
    error_message = "The configured analysis model/profile must be explicitly permitted in analysis_model_arns."
  }
}
variable "maximum_service_tasks" {
  type    = number
  default = 4
  validation {
    condition     = var.maximum_service_tasks >= 2 && var.maximum_service_tasks <= 8 && floor(var.maximum_service_tasks) == var.maximum_service_tasks
    error_message = "Limit application scaling to 2–8 tasks per service."
  }
}
