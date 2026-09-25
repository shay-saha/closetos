terraform {
  required_version = "~> 1.16.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.67.0"
    }
  }
  backend "s3" {}
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Application = var.application_name
      Environment = var.environment
      ManagedBy   = "terraform"
    }
  }
}

data "aws_caller_identity" "current" {}
data "aws_partition" "current" {}
data "aws_availability_zones" "available" {
  state = "available"
  filter {
    name   = "opt-in-status"
    values = ["opt-in-not-required", "opted-in"]
  }
}

locals {
  name       = "${var.application_name}-${var.environment}"
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition
  zones      = slice(data.aws_availability_zones.available.names, 0, 2)
  production = var.environment == "prod"
  nat_zones  = local.production ? local.zones : [local.zones[0]]
  origin     = "https://${var.app_domain}"
}
