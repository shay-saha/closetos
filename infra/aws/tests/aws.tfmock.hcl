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
mock_resource "aws_iam_role" {
  defaults = { arn = "arn:aws:iam::123456789012:role/example" }
}
mock_resource "aws_ecs_cluster" {
  defaults = {
    id  = "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev"
    arn = "arn:aws:ecs:eu-west-2:123456789012:cluster/closetos-dev"
  }
}
mock_resource "aws_ecs_task_definition" {
  defaults = { arn = "arn:aws:ecs:eu-west-2:123456789012:task-definition/example:1" }
}
mock_resource "aws_acm_certificate" {
  defaults = {
    arn = "arn:aws:acm:eu-west-2:123456789012:certificate/00000000-0000-4000-8000-000000000001"
    domain_validation_options = [{
      domain_name           = "closet.example.test"
      resource_record_name  = "_example.closet.example.test"
      resource_record_type  = "CNAME"
      resource_record_value = "_example.acm-validations.aws."
    }]
  }
}
mock_resource "aws_cloudwatch_log_group" {
  defaults = { arn = "arn:aws:logs:eu-west-2:123456789012:log-group:example" }
}
mock_resource "aws_service_discovery_service" {
  defaults = { arn = "arn:aws:servicediscovery:eu-west-2:123456789012:service/srv-example123" }
}
mock_resource "aws_lb" {
  defaults = {
    arn      = "arn:aws:elasticloadbalancing:eu-west-2:123456789012:loadbalancer/app/example/1234567890abcdef"
    dns_name = "example.eu-west-2.elb.amazonaws.com"
    zone_id  = "ZEXAMPLE123"
  }
}
mock_resource "aws_lb_target_group" {
  defaults = { arn = "arn:aws:elasticloadbalancing:eu-west-2:123456789012:targetgroup/example/1234567890abcdef" }
}
mock_resource "aws_lb_listener" {
  defaults = { arn = "arn:aws:elasticloadbalancing:eu-west-2:123456789012:listener/app/example/1234567890abcdef/1234567890abcdef" }
}
