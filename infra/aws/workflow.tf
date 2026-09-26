resource "aws_cloudwatch_log_group" "workflow" {
  name              = "/aws/vendedlogs/states/${local.name}-media"
  retention_in_days = var.log_retention_days
}

locals {
  workflow_retry = [{
    ErrorEquals     = ["ECS.ThrottlingException", "ECS.ServerException", "ECS.ServiceUnavailableException", "AmazonECS.Unknown"]
    IntervalSeconds = 10
    BackoffRate     = 2
    MaxAttempts     = 2
    MaxDelaySeconds = 30
    JitterStrategy  = "FULL"
  }]
  workflow_catch = [{ ErrorEquals = ["States.ALL"], ResultPath = "$.failure", Next = "PrepareProcessingFailure" }]
  workflow_network = {
    AwsvpcConfiguration = {
      Subnets        = [for subnet in aws_subnet.application : subnet.id]
      SecurityGroups = [aws_security_group.service["worker"].id]
      AssignPublicIp = "DISABLED"
    }
  }
  workflow_required_strings = ["jobId", "imageId", "garmentId", "wardrobeId", "sourceKey", "outputPrefix", "mimeType", "checksumSha256", "pipelineVersion", "requestId"]
}

resource "aws_sfn_state_machine" "media" {
  count      = local.runtime_enabled ? 1 : 0
  name       = "${local.name}-media"
  role_arn   = aws_iam_role.workflow.arn
  type       = "STANDARD"
  depends_on = [aws_iam_role_policy.workflow, aws_sqs_queue_policy.media, aws_route.application_egress]
  logging_configuration {
    log_destination        = "${aws_cloudwatch_log_group.workflow.arn}:*"
    include_execution_data = false
    level                  = "ALL"
  }
  tracing_configuration { enabled = true }
  definition = jsonencode({
    StartAt        = "PrepareInput"
    TimeoutSeconds = 3600
    States = {
      PrepareInput = {
        Type       = "Pass"
        Parameters = { "job.$" = "$", "eventId.$" = "States.UUID()" }
        Next       = "ValidateInput"
      }
      ValidateInput = {
        Type = "Choice"
        Choices = [{
          And = concat(
            flatten([for field in local.workflow_required_strings : [
              { Variable = "$.job.${field}", IsPresent = true },
              { Variable = "$.job.${field}", IsString = true },
              { Not = { Variable = "$.job.${field}", StringEquals = "" } }
            ]]),
            [
              { Variable = "$.job.expectedSize", IsPresent = true },
              { Variable = "$.job.expectedSize", IsNumeric = true },
              { Variable = "$.job.expectedSize", NumericGreaterThan = 0 },
              { Variable = "$.job.expectedSize", NumericLessThanEquals = 26214400 },
              { Variable = "$.job.sourceKey", StringMatches = "users/*/garments/*/images/*/original.*" },
              { Variable = "$.job.outputPrefix", StringMatches = "users/*/garments/*/images/*/pipelines/*/" },
              { Or = [for mime in ["image/jpeg", "image/png", "image/webp", "image/heic", "image/heif"] : { Variable = "$.job.mimeType", StringEquals = mime }] }
            ]
          )
          Next = "RunMediaTransform"
        }]
        Default = "RejectInvalidInput"
      }
      RejectInvalidInput = {
        Type  = "Fail"
        Error = "InvalidProcessingInput"
        Cause = "Processing input is missing or outside the upload policy."
      }
      RunMediaTransform = {
        Type           = "Task"
        Resource       = "arn:${local.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = 900
        Parameters = {
          Cluster              = aws_ecs_cluster.application.arn
          TaskDefinition       = aws_ecs_task_definition.media[0].arn
          LaunchType           = "FARGATE"
          PlatformVersion      = "1.4.0"
          NetworkConfiguration = local.workflow_network
          Overrides = {
            ContainerOverrides = [{
              Name        = "media-worker"
              Command     = ["transform"]
              Environment = [{ Name = "WORKFLOW_INPUT", "Value.$" = "States.JsonToString($.job)" }]
            }]
          }
        }
        ResultPath = null
        Retry      = local.workflow_retry
        Catch      = local.workflow_catch
        Next       = "RunAIEnrichment"
      }
      RunAIEnrichment = {
        Type           = "Task"
        Resource       = "arn:${local.partition}:states:::ecs:runTask.sync"
        TimeoutSeconds = 600
        Parameters = {
          Cluster              = aws_ecs_cluster.application.arn
          TaskDefinition       = aws_ecs_task_definition.media[0].arn
          LaunchType           = "FARGATE"
          PlatformVersion      = "1.4.0"
          NetworkConfiguration = local.workflow_network
          Overrides = {
            ContainerOverrides = [{
              Name        = "media-worker"
              Command     = ["enrich"]
              Environment = [{ Name = "WORKFLOW_INPUT", "Value.$" = "States.JsonToString($.job)" }]
            }]
          }
        }
        ResultPath = null
        Retry      = local.workflow_retry
        Catch      = local.workflow_catch
        Next       = "PrepareProcessingResult"
      }
      PrepareProcessingResult = {
        Type = "Pass"
        Parameters = {
          "eventId.$"     = "$.eventId"
          "jobId.$"       = "$.job.jobId"
          status          = "SUCCEEDED"
          "manifestKey.$" = "States.Format('{}manifest.json', $.job.outputPrefix)"
        }
        ResultPath = "$.message"
        Next       = "PublishProcessingResult"
      }
      PrepareProcessingFailure = {
        Type       = "Pass"
        Parameters = { "eventId.$" = "$.eventId", "jobId.$" = "$.job.jobId", status = "FAILED" }
        ResultPath = "$.message"
        Next       = "PublishProcessingFailure"
      }
      PublishProcessingResult = {
        Type           = "Task"
        Resource       = "arn:${local.partition}:states:::sqs:sendMessage"
        TimeoutSeconds = 30
        Parameters     = { QueueUrl = aws_sqs_queue.media["media-results"].url, "MessageBody.$" = "States.JsonToString($.message)" }
        ResultPath     = null
        Retry          = [{ ErrorEquals = ["States.TaskFailed", "States.Timeout"], IntervalSeconds = 2, BackoffRate = 2, MaxAttempts = 5, MaxDelaySeconds = 30, JitterStrategy = "FULL" }]
        Next           = "Success"
      }
      PublishProcessingFailure = {
        Type           = "Task"
        Resource       = "arn:${local.partition}:states:::sqs:sendMessage"
        TimeoutSeconds = 30
        Parameters     = { QueueUrl = aws_sqs_queue.media["media-results"].url, "MessageBody.$" = "States.JsonToString($.message)" }
        ResultPath     = null
        Retry          = [{ ErrorEquals = ["States.TaskFailed", "States.Timeout"], IntervalSeconds = 2, BackoffRate = 2, MaxAttempts = 5, MaxDelaySeconds = 30, JitterStrategy = "FULL" }]
        Next           = "ProcessingFailed"
      }
      ProcessingFailed = { Type = "Fail", Error = "MediaProcessingFailed", Cause = "The processing failure was published for recovery." }
      Success          = { Type = "Succeed" }
    }
  })
}
