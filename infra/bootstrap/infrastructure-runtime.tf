locals {
  infrastructure_cluster   = "arn:aws:ecs:${var.region}:${local.infrastructure_account}:cluster/${local.infrastructure_name}"
  infrastructure_tasks     = [for family in ["api", "web", "media", "migration"] : "arn:aws:ecs:${var.region}:${local.infrastructure_account}:task-definition/${local.infrastructure_name}-${family}:*"]
  infrastructure_discovery = [for type in ["namespace", "service"] : "arn:aws:servicediscovery:${var.region}:${local.infrastructure_account}:${type}/*"]
  infrastructure_logs = flatten([for group in ["/ecs/${local.infrastructure_name}/*", "/aws/vendedlogs/states/${local.infrastructure_name}-media"] : [
    "arn:aws:logs:${var.region}:${local.infrastructure_account}:log-group:${group}",
    "arn:aws:logs:${var.region}:${local.infrastructure_account}:log-group:${group}:*"
  ]])
  infrastructure_runtime_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat([
      {
        Effect    = "Allow"
        Action    = ["ecs:RegisterTaskDefinition"]
        Resource  = local.infrastructure_tasks
        Condition = { StringEquals = local.infrastructure_request_tags }
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:UpdateCluster", "ecs:UpdateClusterSettings"]
        Resource = local.infrastructure_cluster
      },
      {
        Effect   = "Allow"
        Action   = ["ecs:TagResource", "ecs:UntagResource"]
        Resource = concat(local.infrastructure_tasks, [local.infrastructure_cluster], [for service in ["api", "web"] : "arn:aws:ecs:${var.region}:${local.infrastructure_account}:service/${local.infrastructure_name}/${local.infrastructure_name}-${service}"])
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = [for arn in local.application_roles : arn if !endswith(arn, "-media-workflow")]
        Condition = { StringEquals = { "iam:PassedToService" = "ecs-tasks.amazonaws.com" } }
      },
      {
        Effect    = "Allow"
        Action    = ["iam:PassRole"]
        Resource  = "arn:aws:iam::${local.infrastructure_account}:role/${local.infrastructure_name}-media-workflow"
        Condition = { StringEquals = { "iam:PassedToService" = "states.amazonaws.com" } }
      },
      {
        Effect   = "Allow"
        Action   = ["states:CreateStateMachine", "states:UpdateStateMachine", "states:TagResource", "states:UntagResource"]
        Resource = "arn:aws:states:${var.region}:${local.infrastructure_account}:stateMachine:${local.infrastructure_name}-media"
      },
      {
        Effect   = "Allow"
        Action   = ["logs:CreateLogGroup", "logs:PutRetentionPolicy", "logs:DeleteRetentionPolicy", "logs:TagResource", "logs:UntagResource", "logs:TagLogGroup", "logs:UntagLogGroup"]
        Resource = local.infrastructure_logs
      },
      {
        Effect   = "Allow"
        Action   = ["events:PutRule", "events:PutTargets", "events:RemoveTargets", "events:DeleteRule", "events:TagResource", "events:UntagResource"]
        Resource = "arn:aws:events:${var.region}:${local.infrastructure_account}:rule/${local.infrastructure_name}-uploaded-originals"
      }
      ], flatten([for service in ["api", "web"] : [
        {
          Effect   = "Allow"
          Action   = ["ecs:CreateService"]
          Resource = "arn:aws:ecs:${var.region}:${local.infrastructure_account}:service/${local.infrastructure_name}/${local.infrastructure_name}-${service}"
          Condition = {
            StringEquals = local.infrastructure_request_tags
            ArnLike      = { "ecs:task-definition" = "arn:aws:ecs:${var.region}:${local.infrastructure_account}:task-definition/${local.infrastructure_name}-${service}:*" }
            Bool         = { "ecs:enable-execute-command" = "false" }
          }
        },
        {
          Effect   = "Allow"
          Action   = ["ecs:UpdateService"]
          Resource = "arn:aws:ecs:${var.region}:${local.infrastructure_account}:service/${local.infrastructure_name}/${local.infrastructure_name}-${service}"
          Condition = {
            ArnLikeIfExists = { "ecs:task-definition" = "arn:aws:ecs:${var.region}:${local.infrastructure_account}:task-definition/${local.infrastructure_name}-${service}:*" }
            BoolIfExists    = { "ecs:enable-execute-command" = "false" }
          }
        }
      ]]),
      [for service, role in {
        "ecs.amazonaws.com"                         = "AWSServiceRoleForECS"
        "elasticloadbalancing.amazonaws.com"        = "AWSServiceRoleForElasticLoadBalancing"
        "ecs.application-autoscaling.amazonaws.com" = "AWSServiceRoleForApplicationAutoScaling_ECSService"
        } : {
        Effect    = "Allow"
        Action    = ["iam:CreateServiceLinkedRole"]
        Resource  = "arn:aws:iam::${local.infrastructure_account}:role/aws-service-role/${service}/${role}"
        Condition = { StringEquals = { "iam:AWSServiceName" = service } }
      }]
    )
  })
  infrastructure_runtime_support_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect    = "Allow"
        Action    = ["servicediscovery:CreatePrivateDnsNamespace"]
        Resource  = "*"
        Condition = { StringEquals = merge(local.infrastructure_request_tags, { "aws:RequestedRegion" = var.region }) }
      },
      {
        Effect    = "Allow"
        Action    = ["servicediscovery:CreateService"]
        Resource  = "arn:aws:servicediscovery:${var.region}:${local.infrastructure_account}:service/*"
        Condition = { StringEquals = local.infrastructure_request_tags }
      },
      {
        Effect    = "Allow"
        Action    = ["servicediscovery:CreateService"]
        Resource  = "arn:aws:servicediscovery:${var.region}:${local.infrastructure_account}:namespace/*"
        Condition = { StringEquals = local.planning_tags }
      },
      {
        Effect    = "Allow"
        Action    = ["servicediscovery:UpdateService", "servicediscovery:DeleteService"]
        Resource  = "arn:aws:servicediscovery:${var.region}:${local.infrastructure_account}:service/*"
        Condition = { StringEquals = local.planning_tags }
      },
      {
        Effect   = "Allow"
        Action   = ["servicediscovery:GetOperation"]
        Resource = local.infrastructure_discovery
      },
      {
        Effect    = "Allow"
        Action    = ["application-autoscaling:RegisterScalableTarget", "application-autoscaling:PutScalingPolicy", "application-autoscaling:DeleteScalingPolicy", "application-autoscaling:DeregisterScalableTarget"]
        Resource  = "arn:aws:application-autoscaling:${var.region}:${local.infrastructure_account}:scalable-target/*"
        Condition = { StringEquals = merge(local.planning_tags, { "application-autoscaling:service-namespace" = "ecs", "application-autoscaling:scalable-dimension" = "ecs:service:DesiredCount" }) }
      },
      {
        Effect    = "Allow"
        Action    = ["application-autoscaling:RegisterScalableTarget"]
        Resource  = "arn:aws:application-autoscaling:${var.region}:${local.infrastructure_account}:scalable-target/*"
        Condition = { StringEquals = merge(local.infrastructure_request_tags, { "application-autoscaling:service-namespace" = "ecs", "application-autoscaling:scalable-dimension" = "ecs:service:DesiredCount" }) }
      },
      {
        Effect    = "Allow"
        Action    = ["application-autoscaling:TagResource"]
        Resource  = "arn:aws:application-autoscaling:${var.region}:${local.infrastructure_account}:scalable-target/*"
        Condition = { StringEqualsIfExists = local.planning_tags }
      },
      {
        Effect    = "Allow"
        Action    = ["application-autoscaling:UntagResource"]
        Resource  = "arn:aws:application-autoscaling:${var.region}:${local.infrastructure_account}:scalable-target/*"
        Condition = { StringEquals = local.planning_tags }
      }
    ]
  })
}
