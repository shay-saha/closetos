locals {
  infrastructure_load_balancers = "arn:aws:elasticloadbalancing:${var.region}:${local.infrastructure_account}:loadbalancer/app/${local.infrastructure_name}/*"
  infrastructure_listeners      = "arn:aws:elasticloadbalancing:${var.region}:${local.infrastructure_account}:listener/app/${local.infrastructure_name}/*/*"
  infrastructure_listener_rules = "arn:aws:elasticloadbalancing:${var.region}:${local.infrastructure_account}:listener-rule/app/${local.infrastructure_name}/*/*/*"
  infrastructure_target_groups  = [for service in ["api", "web"] : "arn:aws:elasticloadbalancing:${var.region}:${local.infrastructure_account}:targetgroup/${local.infrastructure_name}-${service}/*"]
  infrastructure_edge_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["elasticloadbalancing:CreateLoadBalancer", "elasticloadbalancing:ModifyLoadBalancerAttributes", "elasticloadbalancing:SetSecurityGroups", "elasticloadbalancing:SetSubnets", "elasticloadbalancing:DeleteLoadBalancer", "elasticloadbalancing:CreateListener"]
        Resource = local.infrastructure_load_balancers
      },
      { Effect = "Allow", Action = ["elasticloadbalancing:CreateTargetGroup", "elasticloadbalancing:ModifyTargetGroup", "elasticloadbalancing:ModifyTargetGroupAttributes", "elasticloadbalancing:DeleteTargetGroup"], Resource = local.infrastructure_target_groups },
      { Effect = "Allow", Action = ["elasticloadbalancing:ModifyListener", "elasticloadbalancing:DeleteListener", "elasticloadbalancing:CreateRule"], Resource = local.infrastructure_listeners },
      { Effect = "Allow", Action = ["elasticloadbalancing:ModifyRule", "elasticloadbalancing:DeleteRule", "elasticloadbalancing:SetRulePriorities"], Resource = local.infrastructure_listener_rules },
      { Effect = "Allow", Action = ["elasticloadbalancing:AddTags", "elasticloadbalancing:RemoveTags"], Resource = concat([local.infrastructure_load_balancers, local.infrastructure_listeners, local.infrastructure_listener_rules], local.infrastructure_target_groups) },
      {
        Effect   = "Allow"
        Action   = ["acm:RequestCertificate"]
        Resource = "*"
        Condition = {
          StringEquals                = merge(local.infrastructure_request_tags, { "aws:RequestedRegion" = var.region })
          "ForAllValues:StringEquals" = { "acm:DomainNames" = [local.infrastructure_domain] }
          Null                        = { "acm:DomainNames" = "false" }
        }
      },
      {
        Effect    = "Allow"
        Action    = ["acm:AddTagsToCertificate"]
        Resource  = "arn:aws:acm:${var.region}:${local.infrastructure_account}:certificate/*"
        Condition = { StringEquals = local.infrastructure_request_tags, StringEqualsIfExists = local.planning_tags }
      },
      {
        Effect    = "Allow"
        Action    = ["acm:DeleteCertificate", "acm:RemoveTagsFromCertificate"]
        Resource  = "arn:aws:acm:${var.region}:${local.infrastructure_account}:certificate/*"
        Condition = { StringEquals = local.planning_tags }
      },
      {
        Effect   = "Allow"
        Action   = ["route53:ChangeResourceRecordSets"]
        Resource = "arn:aws:route53:::hostedzone/${coalesce(var.github_route53_zone_id, "UNCONFIGURED")}"
        Condition = { "ForAllValues:StringLike" = {
          "route53:ChangeResourceRecordSetsNormalizedRecordNames" = [local.infrastructure_domain, "_*.${local.infrastructure_domain}"]
          "route53:ChangeResourceRecordSetsRecordTypes"           = ["A", "CNAME"]
          "route53:ChangeResourceRecordSetsActions"               = ["CREATE", "UPSERT", "DELETE"]
          }, Null = {
          "route53:ChangeResourceRecordSetsNormalizedRecordNames" = "false"
          "route53:ChangeResourceRecordSetsRecordTypes"           = "false"
          "route53:ChangeResourceRecordSetsActions"               = "false"
        } }
      },
      {
        Effect   = "Allow"
        Action   = ["route53:CreateHostedZone"]
        Resource = "*"
        Condition = {
          "ForAllValues:StringEquals" = { "route53:VPCs" = ["VPCId=${var.github_infrastructure_enabled ? data.aws_vpc.infrastructure[0].id : "UNCONFIGURED"},VPCRegion=${var.region}"] }
          Null                        = { "route53:VPCs" = "false" }
        }
      },
      { Effect = "Allow", Action = ["route53:GetHostedZone"], Resource = "arn:aws:route53:::hostedzone/*" },
      { Effect = "Allow", Action = ["route53:GetChange"], Resource = "arn:aws:route53:::change/*" },
      { Effect = "Allow", Action = ["route53:ListHostedZonesByName"], Resource = "*" },
      { Effect = "Allow", Action = ["ec2:DescribeRegions"], Resource = "*" },
      {
        Effect    = "Allow"
        Action    = ["cloudfront:UpdateDistribution", "cloudfront:TagResource", "cloudfront:UntagResource"]
        Resource  = "arn:aws:cloudfront::${local.infrastructure_account}:distribution/*"
        Condition = { StringEquals = local.planning_tags }
      },
      {
        Effect    = "Allow"
        Action    = ["cognito-idp:UpdateUserPool", "cognito-idp:SetUserPoolMfaConfig", "cognito-idp:CreateUserPoolClient", "cognito-idp:UpdateUserPoolClient", "cognito-idp:DeleteUserPoolClient", "cognito-idp:CreateUserPoolDomain", "cognito-idp:UpdateUserPoolDomain", "cognito-idp:DeleteUserPoolDomain", "cognito-idp:CreateGroup", "cognito-idp:UpdateGroup", "cognito-idp:DeleteGroup", "cognito-idp:TagResource", "cognito-idp:UntagResource"]
        Resource  = "arn:aws:cognito-idp:${var.region}:${local.infrastructure_account}:userpool/*"
        Condition = { StringEquals = local.planning_tags }
      }
    ]
  })
}
