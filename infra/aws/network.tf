resource "aws_vpc" "main" {
  cidr_block           = var.vpc_cidr
  enable_dns_hostnames = true
  enable_dns_support   = true
  tags                 = { Name = local.name }
}
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id
}
resource "aws_subnet" "public" {
  for_each                = toset(local.zones)
  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, index(local.zones, each.key))
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-public-${each.key}" }
}
resource "aws_subnet" "application" {
  for_each                = toset(local.zones)
  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 10 + index(local.zones, each.key))
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-application-${each.key}" }
}
resource "aws_subnet" "database" {
  for_each                = toset(local.zones)
  vpc_id                  = aws_vpc.main.id
  availability_zone       = each.key
  cidr_block              = cidrsubnet(var.vpc_cidr, 8, 20 + index(local.zones, each.key))
  map_public_ip_on_launch = false
  tags                    = { Name = "${local.name}-database-${each.key}" }
}
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id
}
resource "aws_route" "internet" {
  route_table_id         = aws_route_table.public.id
  destination_cidr_block = "0.0.0.0/0"
  gateway_id             = aws_internet_gateway.main.id
}
resource "aws_route_table_association" "public" {
  for_each       = aws_subnet.public
  subnet_id      = each.value.id
  route_table_id = aws_route_table.public.id
}
resource "aws_eip" "nat" {
  for_each = toset(local.nat_zones)
  domain   = "vpc"
}
resource "aws_nat_gateway" "main" {
  for_each      = toset(local.nat_zones)
  allocation_id = aws_eip.nat[each.key].id
  subnet_id     = aws_subnet.public[each.key].id
  depends_on    = [aws_internet_gateway.main]
}
resource "aws_route_table" "application" {
  for_each = aws_subnet.application
  vpc_id   = aws_vpc.main.id
}
resource "aws_route" "application_egress" {
  for_each               = aws_route_table.application
  route_table_id         = each.value.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.main[local.production ? each.key : local.zones[0]].id
}
resource "aws_route_table_association" "application" {
  for_each       = aws_subnet.application
  subnet_id      = each.value.id
  route_table_id = aws_route_table.application[each.key].id
}
resource "aws_route_table" "database" {
  vpc_id = aws_vpc.main.id
}
resource "aws_route_table_association" "database" {
  for_each       = aws_subnet.database
  subnet_id      = each.value.id
  route_table_id = aws_route_table.database.id
}
resource "aws_vpc_endpoint" "s3" {
  vpc_id            = aws_vpc.main.id
  service_name      = "com.amazonaws.${var.region}.s3"
  vpc_endpoint_type = "Gateway"
  route_table_ids   = [for route in aws_route_table.application : route.id]
}
resource "aws_security_group" "service" {
  for_each    = toset(["alb", "web", "api", "worker", "database"])
  name        = "${local.name}-${each.key}"
  description = "${each.key} connectivity for ${local.name}"
  vpc_id      = aws_vpc.main.id
}
resource "aws_vpc_security_group_ingress_rule" "https" {
  security_group_id = aws_security_group.service["alb"].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
resource "aws_vpc_security_group_ingress_rule" "http_redirect" {
  security_group_id = aws_security_group.service["alb"].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 80
  to_port           = 80
  ip_protocol       = "tcp"
}
locals {
  connections = {
    alb_web = { source = "alb", destination = "web", port = 3000 }
    alb_api = { source = "alb", destination = "api", port = 8080 }
    web_api = { source = "web", destination = "api", port = 8080 }
    api_db  = { source = "api", destination = "database", port = 5432 }
  }
}
resource "aws_vpc_security_group_ingress_rule" "service" {
  for_each                     = local.connections
  security_group_id            = aws_security_group.service[each.value.destination].id
  referenced_security_group_id = aws_security_group.service[each.value.source].id
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "service" {
  for_each                     = local.connections
  security_group_id            = aws_security_group.service[each.value.source].id
  referenced_security_group_id = aws_security_group.service[each.value.destination].id
  from_port                    = each.value.port
  to_port                      = each.value.port
  ip_protocol                  = "tcp"
}
resource "aws_vpc_security_group_egress_rule" "aws_https" {
  for_each          = toset(["api", "web", "worker"])
  security_group_id = aws_security_group.service[each.key].id
  cidr_ipv4         = "0.0.0.0/0"
  from_port         = 443
  to_port           = 443
  ip_protocol       = "tcp"
}
