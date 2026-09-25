mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_data "aws_partition" {
    defaults = { partition = "aws" }
  }
  mock_data "aws_availability_zones" {
    defaults = { names = ["eu-west-2a", "eu-west-2b"] }
  }
  mock_resource "aws_s3_bucket" {
    defaults = {
      arn                         = "arn:aws:s3:::closetos-dev-123456789012-media"
      bucket_regional_domain_name = "closetos-dev-123456789012-media.s3.eu-west-2.amazonaws.com"
    }
  }
  mock_resource "aws_cloudfront_distribution" {
    defaults = {
      arn         = "arn:aws:cloudfront::123456789012:distribution/EXAMPLE"
      domain_name = "example.cloudfront.net"
    }
  }
  mock_resource "aws_db_instance" {
    defaults = {
      address = "example.eu-west-2.rds.amazonaws.com"
      master_user_secret = [{
        secret_arn    = "arn:aws:secretsmanager:eu-west-2:123456789012:secret:database-example"
        secret_status = "active"
        kms_key_id    = "arn:aws:kms:eu-west-2:123456789012:key/00000000-0000-4000-8000-000000000001"
      }]
    }
  }
  mock_resource "aws_sqs_queue" {
    defaults = {
      arn = "arn:aws:sqs:eu-west-2:123456789012:queue"
      url = "https://sqs.eu-west-2.amazonaws.com/123456789012/queue"
    }
  }
  mock_resource "aws_kms_key" {
    defaults = {
      arn    = "arn:aws:kms:eu-west-2:123456789012:key/00000000-0000-4000-8000-000000000001"
      key_id = "00000000-0000-4000-8000-000000000001"
    }
  }
  mock_resource "aws_cloudwatch_event_rule" {
    defaults = { arn = "arn:aws:events:eu-west-2:123456789012:rule/closetos-dev-uploaded-originals" }
  }
  mock_resource "aws_cognito_user_pool" {
    defaults = { id = "eu-west-2_Example123" }
  }
}

override_resource {
  target = aws_security_group.service["api"]
  values = { id = "sg-00000000000000001" }
}
override_resource {
  target = aws_security_group.service["database"]
  values = { id = "sg-00000000000000002" }
}

variables {
  app_domain            = "closet.example.test"
  cloudfront_public_key = file("tests/media-signing.pub")
}

run "development_security_and_cost_boundaries" {
  command = apply
  assert {
    condition     = length(aws_subnet.application) == 2 && length(aws_subnet.database) == 2 && length(aws_nat_gateway.main) == 1
    error_message = "Development needs two application/database zones and one NAT gateway."
  }
  assert {
    condition     = alltrue([for subnet in aws_subnet.application : subnet.map_public_ip_on_launch == false]) && alltrue([for subnet in aws_subnet.database : subnet.map_public_ip_on_launch == false])
    error_message = "Application tasks and the database must have private addresses."
  }
  assert {
    condition     = aws_vpc_security_group_ingress_rule.service["api_db"].referenced_security_group_id == aws_security_group.service["api"].id && aws_vpc_security_group_ingress_rule.service["api_db"].security_group_id == aws_security_group.service["database"].id && aws_vpc_security_group_ingress_rule.service["api_db"].from_port == 5432 && length([for connection in local.connections : connection if connection.destination == "database"]) == 1
    error_message = "Only the API security group may access PostgreSQL."
  }
  assert {
    condition     = !aws_db_instance.main.publicly_accessible && aws_db_instance.main.storage_encrypted && aws_db_instance.main.manage_master_user_password && aws_db_instance.main.deletion_protection && !aws_db_instance.main.skip_final_snapshot && aws_db_instance.main.backup_retention_period == 7
    error_message = "RDS must be encrypted, private, protected, backed up, and use an AWS-managed password."
  }
  assert {
    condition     = { for parameter in aws_db_parameter_group.main.parameter : parameter.name => parameter.value } == { "rds.force_ssl" = "1", "log_min_duration_statement" = "250" }
    error_message = "Require TLS and log slow statements without logging all personal data."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.media.block_public_acls && aws_s3_bucket_public_access_block.media.block_public_policy && aws_s3_bucket_public_access_block.media.ignore_public_acls && aws_s3_bucket_public_access_block.media.restrict_public_buckets
    error_message = "Every public media access mechanism must be blocked."
  }
  assert {
    condition     = one(one(aws_s3_bucket_server_side_encryption_configuration.media.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Media objects must be encrypted at rest."
  }
  assert {
    condition     = one(aws_s3_bucket_cors_configuration.media.cors_rule).allowed_origins == toset(["https://closet.example.test"])
    error_message = "Direct uploads must use the application's HTTPS origin."
  }
  assert {
    condition     = aws_cloudfront_distribution.media.default_cache_behavior[0].viewer_protocol_policy == "https-only" && length(aws_cloudfront_distribution.media.default_cache_behavior[0].trusted_key_groups) == 1 && aws_cloudfront_origin_access_control.media.signing_behavior == "always"
    error_message = "CloudFront must require HTTPS, signed viewer requests, and signed origin requests."
  }
  assert {
    condition     = jsondecode(aws_s3_bucket_policy.media.policy).Statement[1].Resource == "${aws_s3_bucket.media.arn}/users/*/garments/*/images/*/pipelines/*/*.webp" && jsondecode(aws_s3_bucket_policy.media.policy).Statement[1].Condition.StringEquals["AWS:SourceArn"] == aws_cloudfront_distribution.media.arn
    error_message = "Only this distribution may serve derived wardrobe images; originals and manifests stay outside CDN access."
  }
  assert {
    condition     = aws_cognito_user_pool_client.web.allowed_oauth_flows == toset(["code"]) && !aws_cognito_user_pool_client.web.generate_secret && aws_cognito_user_pool_client.web.enable_token_revocation && aws_cognito_user_pool_client.web.prevent_user_existence_errors == "ENABLED"
    error_message = "Use authorization code flow, revoke sessions, and avoid account enumeration."
  }
  assert {
    condition     = aws_cognito_user_pool_client.web.callback_urls == toset(["https://closet.example.test/api/auth/callback/wardrobe"]) && aws_cognito_user_group.administrators.name == "closetos-admin"
    error_message = "OAuth callbacks and administrator claims must match the application."
  }
  assert {
    condition     = alltrue([for queue in aws_sqs_queue.media : queue.kms_master_key_id == aws_kms_key.queues.arn && queue.receive_wait_time_seconds == 20 && queue.visibility_timeout_seconds == 600 && jsondecode(queue.redrive_policy).maxReceiveCount == 5]) && alltrue([for queue in aws_sqs_queue.dead_letter : queue.message_retention_seconds == 1209600])
    error_message = "Processing delivery must be encrypted, bounded, long polled, and recoverable through retained DLQs."
  }
  assert {
    condition     = jsondecode(aws_cloudwatch_event_rule.uploads.event_pattern).detail.object.key[0].wildcard == "users/*/garments/*/images/*/original.*" && aws_cloudwatch_event_target.uploads.retry_policy[0].maximum_retry_attempts == 10
    error_message = "Only original uploads may start processing; generated derivatives must not create a loop."
  }
  assert {
    condition     = alltrue([for repo in aws_ecr_repository.application : repo.image_tag_mutability == "IMMUTABLE" && repo.image_scanning_configuration[0].scan_on_push && !repo.force_delete])
    error_message = "Released images must remain immutable, scanned, and available for rollback."
  }
}

run "production_redundancy_and_recovery" {
  command = apply
  variables { environment = "prod" }
  assert {
    condition     = length(aws_nat_gateway.main) == 2 && aws_db_instance.main.multi_az && aws_db_instance.main.backup_retention_period == 14
    error_message = "Production must keep independent egress and database recovery across two zones."
  }
  assert {
    condition     = aws_db_instance.main.max_allocated_storage == 100 && !aws_db_instance.main.apply_immediately && !aws_db_instance.main.allow_major_version_upgrade
    error_message = "Bound storage costs and apply database maintenance without surprise major upgrades."
  }
}

run "reject_private_signing_material" {
  command = plan
  variables { cloudfront_public_key = "-----BEGIN PRIVATE KEY-----\nsecret\n-----END PRIVATE KEY-----" }
  expect_failures = [var.cloudfront_public_key]
}
run "reject_unbounded_storage" {
  command = plan
  variables { database_maximum_storage_gib = 1001 }
  expect_failures = [var.database_maximum_storage_gib]
}
run "reject_invalid_environment" {
  command = plan
  variables { environment = "experimental" }
  expect_failures = [var.environment]
}
