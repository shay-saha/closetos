locals {
  infrastructure_network_resources = { for type in ["vpc", "subnet", "internet-gateway", "route-table", "elastic-ip", "natgateway", "vpc-endpoint", "security-group", "security-group-rule"] : type => "arn:aws:ec2:${var.region}:${local.infrastructure_account}:${type}/*" }
  infrastructure_network_creates = {
    vpc                 = ["ec2:CreateVpc"]
    subnet              = ["ec2:CreateSubnet"]
    internet-gateway    = ["ec2:CreateInternetGateway"]
    route-table         = ["ec2:CreateRouteTable"]
    elastic-ip          = ["ec2:AllocateAddress"]
    natgateway          = ["ec2:CreateNatGateway"]
    vpc-endpoint        = ["ec2:CreateVpcEndpoint"]
    security-group      = ["ec2:CreateSecurityGroup"]
    security-group-rule = ["ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress"]
  }
  infrastructure_network_existing = {
    vpc                 = ["ec2:CreateSubnet", "ec2:CreateRouteTable", "ec2:CreateNatGateway", "ec2:CreateVpcEndpoint", "ec2:CreateSecurityGroup", "ec2:ModifyVpcAttribute", "ec2:DeleteVpc", "ec2:AttachInternetGateway", "ec2:DetachInternetGateway"]
    subnet              = ["ec2:CreateNatGateway", "ec2:CreateVpcEndpoint", "ec2:ModifySubnetAttribute", "ec2:DeleteSubnet", "ec2:AssociateRouteTable", "ec2:DisassociateRouteTable", "ec2:ReplaceRouteTableAssociation"]
    internet-gateway    = ["ec2:AttachInternetGateway", "ec2:DetachInternetGateway", "ec2:DeleteInternetGateway"]
    route-table         = ["ec2:CreateVpcEndpoint", "ec2:CreateRoute", "ec2:ReplaceRoute", "ec2:DeleteRoute", "ec2:AssociateRouteTable", "ec2:DisassociateRouteTable", "ec2:ReplaceRouteTableAssociation", "ec2:DeleteRouteTable"]
    elastic-ip          = ["ec2:CreateNatGateway", "ec2:ReleaseAddress"]
    natgateway          = ["ec2:DeleteNatGateway"]
    vpc-endpoint        = ["ec2:ModifyVpcEndpoint", "ec2:DeleteVpcEndpoints"]
    security-group      = ["ec2:CreateVpcEndpoint", "ec2:AuthorizeSecurityGroupIngress", "ec2:AuthorizeSecurityGroupEgress", "ec2:RevokeSecurityGroupIngress", "ec2:RevokeSecurityGroupEgress", "ec2:ModifySecurityGroupRules", "ec2:DeleteSecurityGroup"]
    security-group-rule = ["ec2:ModifySecurityGroupRules"]
  }
  infrastructure_network_create_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [for type, actions in local.infrastructure_network_creates : {
        Effect    = "Allow"
        Action    = actions
        Resource  = local.infrastructure_network_resources[type]
        Condition = { StringEquals = local.infrastructure_request_tags }
      }],
      [{
        Effect   = "Allow"
        Action   = ["ec2:CreateTags"]
        Resource = values(local.infrastructure_network_resources)
        Condition = { StringEquals = merge(local.infrastructure_request_tags, {
          "ec2:CreateAction" = distinct(flatten([for actions in values(local.infrastructure_network_creates) : [for action in actions : split(":", action)[1]]]))
        }) }
      }]
    )
  })
  infrastructure_network_update_policy = jsonencode({
    Version = "2012-10-17"
    Statement = concat(
      [for type, actions in local.infrastructure_network_existing : {
        Effect    = "Allow"
        Action    = actions
        Resource  = local.infrastructure_network_resources[type]
        Condition = { StringEquals = local.planning_tags }
      }],
      [{
        Effect   = "Allow"
        Action   = ["ec2:CreateTags", "ec2:DeleteTags"]
        Resource = values(local.infrastructure_network_resources)
        Condition = {
          StringEquals         = local.planning_tags
          StringEqualsIfExists = local.infrastructure_request_tags
        }
      }]
    )
  })
}
