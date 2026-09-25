locals {
  queues = toset(["media-ingest", "media-results"])
}
resource "aws_kms_key" "queues" {
  description             = "Processing messages for ${local.name}"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AccountKeyAdministration"
        Effect    = "Allow"
        Principal = { AWS = "arn:${local.partition}:iam::${local.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "StorageNotificationEncryption"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = ["kms:Decrypt", "kms:GenerateDataKey"]
        Resource  = "*"
        Condition = {
          StringEquals = { "aws:SourceAccount" = local.account_id }
          ArnEquals    = { "aws:SourceArn" = "arn:${local.partition}:events:${var.region}:${local.account_id}:rule/${local.name}-uploaded-originals" }
        }
      }
    ]
  })
}
resource "aws_kms_alias" "queues" {
  name          = "alias/${local.name}-queues"
  target_key_id = aws_kms_key.queues.key_id
}
resource "aws_sqs_queue" "dead_letter" {
  for_each                  = local.queues
  name                      = "${local.name}-${each.key}-dlq"
  message_retention_seconds = 1209600
  kms_master_key_id         = aws_kms_key.queues.arn
}
resource "aws_sqs_queue" "media" {
  for_each                   = local.queues
  name                       = "${local.name}-${each.key}"
  message_retention_seconds  = 345600
  receive_wait_time_seconds  = 20
  visibility_timeout_seconds = 600
  kms_master_key_id          = aws_kms_key.queues.arn
  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dead_letter[each.key].arn
    maxReceiveCount     = 5
  })
}
resource "aws_sqs_queue_redrive_allow_policy" "media" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.dead_letter[each.key].url
  redrive_allow_policy = jsonencode({
    redrivePermission = "byQueue"
    sourceQueueArns   = [aws_sqs_queue.media[each.key].arn]
  })
}
resource "aws_sqs_queue_policy" "media" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.media[each.key].url
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Sid       = "RequireTLS"
        Effect    = "Deny"
        Principal = "*"
        Action    = "sqs:*"
        Resource  = aws_sqs_queue.media[each.key].arn
        Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }
      ], each.key == "media-ingest" ? [
      {
        Sid       = "StorageNotifications"
        Effect    = "Allow"
        Principal = { Service = "events.amazonaws.com" }
        Action    = "sqs:SendMessage"
        Resource  = aws_sqs_queue.media[each.key].arn
        Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.uploads.arn }, StringEquals = { "aws:SourceAccount" = local.account_id } }
      }
    ] : [])
  })
}
resource "aws_cloudwatch_event_rule" "uploads" {
  name = "${local.name}-uploaded-originals"
  event_pattern = jsonencode({
    source        = ["aws.s3"]
    "detail-type" = ["Object Created"]
    detail = {
      bucket = { name = [aws_s3_bucket.media.id] }
      object = { key = [{ wildcard = "users/*/garments/*/images/*/original.*" }] }
    }
  })
}
resource "aws_cloudwatch_event_target" "uploads" {
  depends_on = [aws_sqs_queue_policy.media, aws_sqs_queue_policy.dead_letter]
  rule       = aws_cloudwatch_event_rule.uploads.name
  arn        = aws_sqs_queue.media["media-ingest"].arn
  dead_letter_config { arn = aws_sqs_queue.dead_letter["media-ingest"].arn }
  retry_policy {
    maximum_event_age_in_seconds = 86400
    maximum_retry_attempts       = 10
  }
}
resource "aws_sqs_queue_policy" "dead_letter" {
  for_each  = local.queues
  queue_url = aws_sqs_queue.dead_letter[each.key].url
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([{
      Effect    = "Deny"
      Principal = "*"
      Action    = "sqs:*"
      Resource  = aws_sqs_queue.dead_letter[each.key].arn
      Condition = { Bool = { "aws:SecureTransport" = "false" } }
      }], each.key == "media-ingest" ? [{
      Effect    = "Allow"
      Principal = { Service = "events.amazonaws.com" }
      Action    = "sqs:SendMessage"
      Resource  = aws_sqs_queue.dead_letter[each.key].arn
      Condition = { ArnEquals = { "aws:SourceArn" = aws_cloudwatch_event_rule.uploads.arn }, StringEquals = { "aws:SourceAccount" = local.account_id } }
    }] : [])
  })
}
