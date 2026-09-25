mock_provider "aws" {
  mock_data "aws_caller_identity" {
    defaults = { account_id = "123456789012" }
  }
  mock_resource "aws_s3_bucket" {
    defaults = {
      id  = "closetos-dev-123456789012-state"
      arn = "arn:aws:s3:::closetos-dev-123456789012-state"
    }
  }
}

run "recoverable_private_locked_state" {
  command = apply
  assert {
    condition     = aws_s3_bucket.state.bucket == "closetos-dev-123456789012-state" && !aws_s3_bucket.state.force_destroy
    error_message = "State storage must be account/environment specific and retain its contents."
  }
  assert {
    condition     = aws_s3_bucket_public_access_block.state.block_public_acls && aws_s3_bucket_public_access_block.state.block_public_policy && aws_s3_bucket_public_access_block.state.ignore_public_acls && aws_s3_bucket_public_access_block.state.restrict_public_buckets && one(aws_s3_bucket_ownership_controls.state.rule).object_ownership == "BucketOwnerEnforced"
    error_message = "State must stay private with ACLs disabled."
  }
  assert {
    condition     = aws_s3_bucket_versioning.state.versioning_configuration[0].status == "Enabled" && one(one(aws_s3_bucket_server_side_encryption_configuration.state.rule).apply_server_side_encryption_by_default).sse_algorithm == "AES256"
    error_message = "Encrypt state and retain old versions for recovery."
  }
  assert {
    condition     = jsondecode(aws_s3_bucket_policy.state.policy).Statement[0].Effect == "Deny" && jsondecode(aws_s3_bucket_policy.state.policy).Statement[0].Condition.Bool["aws:SecureTransport"] == "false"
    error_message = "Reject state access over plain HTTP."
  }
  assert {
    condition     = output.backend_config.bucket == aws_s3_bucket.state.id && output.backend_config.key == "dev/closetos.tfstate" && output.backend_config.encrypt && output.backend_config.use_lockfile && output.backend_config.region == "eu-west-2"
    error_message = "Use encrypted state with S3-native locking and an environment-specific key."
  }
}
run "reject_invalid_environment" {
  command = plan
  variables { environment = "temporary" }
  expect_failures = [var.environment]
}
