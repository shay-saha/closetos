locals {
  runtime_enabled = length(var.image_digests) > 0
  services        = local.runtime_enabled ? toset(["api", "web"]) : toset([])
  service_ports   = { api = 8080, web = 3000 }
}

resource "aws_acm_certificate" "application" {
  count             = local.runtime_enabled ? 1 : 0
  domain_name       = var.app_domain
  validation_method = "DNS"
  lifecycle { create_before_destroy = true }
}
resource "aws_route53_record" "certificate" {
  count   = local.runtime_enabled ? 1 : 0
  zone_id = var.route53_zone_id
  name    = one(aws_acm_certificate.application[0].domain_validation_options).resource_record_name
  type    = one(aws_acm_certificate.application[0].domain_validation_options).resource_record_type
  records = [one(aws_acm_certificate.application[0].domain_validation_options).resource_record_value]
  ttl     = 300
}
resource "aws_acm_certificate_validation" "application" {
  count                   = local.runtime_enabled ? 1 : 0
  certificate_arn         = aws_acm_certificate.application[0].arn
  validation_record_fqdns = [aws_route53_record.certificate[0].fqdn]
}
resource "aws_lb" "application" {
  count                      = local.runtime_enabled ? 1 : 0
  name                       = local.name
  load_balancer_type         = "application"
  internal                   = false
  subnets                    = [for subnet in aws_subnet.public : subnet.id]
  security_groups            = [aws_security_group.service["alb"].id]
  enable_deletion_protection = local.production
  drop_invalid_header_fields = true
  desync_mitigation_mode     = "strictest"
  idle_timeout               = 60
}
resource "aws_lb_target_group" "service" {
  for_each             = local.services
  name                 = "${local.name}-${each.key}"
  vpc_id               = aws_vpc.main.id
  port                 = local.service_ports[each.key]
  protocol             = "HTTP"
  target_type          = "ip"
  deregistration_delay = 30
  health_check {
    enabled             = true
    path                = each.key == "api" ? "/actuator/health/readiness" : "/signin"
    matcher             = "200"
    interval            = 30
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}
resource "aws_lb_listener" "http" {
  count             = local.runtime_enabled ? 1 : 0
  load_balancer_arn = aws_lb.application[0].arn
  port              = 80
  protocol          = "HTTP"
  default_action {
    type = "redirect"
    redirect {
      port        = "443"
      protocol    = "HTTPS"
      status_code = "HTTP_301"
    }
  }
}
resource "aws_lb_listener" "https" {
  count             = local.runtime_enabled ? 1 : 0
  load_balancer_arn = aws_lb.application[0].arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = aws_acm_certificate_validation.application[0].certificate_arn
  default_action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service["web"].arn
  }
}
resource "aws_lb_listener_rule" "api" {
  count        = local.runtime_enabled ? 1 : 0
  listener_arn = aws_lb_listener.https[0].arn
  priority     = 10
  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.service["api"].arn
  }
  condition {
    path_pattern { values = ["/api/v1", "/api/v1/*"] }
  }
}
resource "aws_route53_record" "application" {
  count   = local.runtime_enabled ? 1 : 0
  zone_id = var.route53_zone_id
  name    = var.app_domain
  type    = "A"
  alias {
    name                   = aws_lb.application[0].dns_name
    zone_id                = aws_lb.application[0].zone_id
    evaluate_target_health = true
  }
}
