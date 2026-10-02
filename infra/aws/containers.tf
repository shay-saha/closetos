resource "aws_ecs_cluster" "application" {
  name = local.name
  setting {
    name  = "containerInsights"
    value = "enabled"
  }
}
resource "aws_cloudwatch_log_group" "container" {
  for_each          = aws_ecr_repository.application
  name              = "/ecs/${local.name}/${each.key}"
  retention_in_days = var.log_retention_days
}
resource "aws_service_discovery_private_dns_namespace" "application" {
  count = local.runtime_enabled ? 1 : 0
  name  = "${local.name}.internal"
  vpc   = aws_vpc.main.id
}
resource "aws_service_discovery_service" "api" {
  count = local.runtime_enabled ? 1 : 0
  name  = "api"
  dns_config {
    namespace_id   = aws_service_discovery_private_dns_namespace.application[0].id
    routing_policy = "MULTIVALUE"
    dns_records {
      ttl  = 10
      type = "A"
    }
  }
  health_check_custom_config {}
}

locals {
  oidc_issuer  = "https://cognito-idp.${var.region}.amazonaws.com/${aws_cognito_user_pool.main.id}"
  database_url = "jdbc:postgresql://${aws_db_instance.main.address}:5432/closetos?sslmode=verify-full&sslrootcert=/app/rds-global-bundle.pem"
  service_capacity = {
    api = { cpu = local.production ? 1024 : 512, memory = local.production ? 2048 : 1024 }
    web = { cpu = 256, memory = 512 }
  }
  api_environment = {
    AWS_REGION                                         = var.region
    DATABASE_URL                                       = local.database_url
    DATABASE_USERNAME                                  = "closetos_app"
    COGNITO_ISSUER_URI                                 = local.oidc_issuer
    COGNITO_CLIENT_ID                                  = aws_cognito_user_pool_client.web.id
    MEDIA_BUCKET                                       = aws_s3_bucket.media.id
    MEDIA_WORKFLOW_MODE                                = "aws"
    MEDIA_STATE_MACHINE_ARN                            = local.workflow_arn
    MEDIA_CLUSTER_ARN                                  = local.cluster_arn
    MEDIA_DISPATCH_ENABLED                             = "true"
    MEDIA_AWS_CONSUMER_ENABLED                         = "true"
    MEDIA_INGEST_QUEUE_URL                             = aws_sqs_queue.media["media-ingest"].url
    MEDIA_RESULT_QUEUE_URL                             = aws_sqs_queue.media["media-results"].url
    CLOUDFRONT_DOMAIN                                  = aws_cloudfront_distribution.media.domain_name
    CLOUDFRONT_DISTRIBUTION_ID                         = aws_cloudfront_distribution.media.id
    CLOUDFRONT_KEY_ID                                  = aws_cloudfront_public_key.media.id
    BEDROCK_EMBEDDING_MODEL_ID                         = var.embedding_model_arn
    BEDROCK_EMBEDDING_REGION                           = split(":", var.embedding_model_arn)[3]
    EMBEDDING_DISPATCH_ENABLED                         = "true"
    EMBEDDING_PROVIDER                                 = "bedrock"
    SPRING_FLYWAY_ENABLED                              = "false"
    MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE = "readinessState,db"
    SERVER_FORWARD_HEADERS_STRATEGY                    = "framework"
  }
  web_environment = {
    NODE_ENV       = "production"
    API_URL        = "http://api.${local.name}.internal:8080"
    NEXTAUTH_URL   = local.origin
    OIDC_ISSUER    = local.oidc_issuer
    OIDC_CLIENT_ID = aws_cognito_user_pool_client.web.id
  }
  container_environment = { api = local.api_environment, web = local.web_environment }
  container_secrets = {
    api = [
      { name = "DATABASE_PASSWORD", valueFrom = aws_secretsmanager_secret.application["database-app"].arn },
      { name = "CLOUDFRONT_PRIVATE_KEY", valueFrom = aws_secretsmanager_secret.application["media-signing"].arn }
    ]
    web = [{ name = "NEXTAUTH_SECRET", valueFrom = aws_secretsmanager_secret.application["web-session"].arn }]
  }
  container_health = {
    api = ["CMD-SHELL", "curl --fail --silent --max-time 8 http://127.0.0.1:8080/actuator/health/readiness > /dev/null"]
    web = ["CMD", "node", "-e", "fetch('http://127.0.0.1:3000/signin',{signal:AbortSignal.timeout(8000)}).then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"]
  }
}
resource "aws_ecs_task_definition" "service" {
  for_each                 = local.services
  skip_destroy             = true
  family                   = "${local.name}-${each.key}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = local.service_capacity[each.key].cpu
  memory                   = local.service_capacity[each.key].memory
  task_role_arn            = aws_iam_role.task[each.key].arn
  execution_role_arn       = aws_iam_role.execution[each.key].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = each.key
    image                  = "${aws_ecr_repository.application[each.key].repository_url}@${var.image_digests[each.key]}"
    essential              = true
    readonlyRootFilesystem = true
    user                   = each.key == "api" ? "closetos" : "node"
    stopTimeout            = 60
    portMappings           = [{ containerPort = local.service_ports[each.key], protocol = "tcp" }]
    environment            = [for name, value in local.container_environment[each.key] : { name = name, value = value }]
    secrets                = local.container_secrets[each.key]
    mountPoints            = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }]
    healthCheck            = { command = local.container_health[each.key], interval = 30, timeout = 10, retries = 3, startPeriod = 60 }
    logConfiguration = {
      logDriver = "awslogs"
      options   = { "awslogs-group" = aws_cloudwatch_log_group.container[each.key].name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "application" }
    }
  }])
  volume { name = "temporary" }
  depends_on = [aws_iam_role_policy.execution]
}
resource "aws_ecs_task_definition" "media" {
  count                    = local.runtime_enabled ? 1 : 0
  skip_destroy             = true
  family                   = "${local.name}-media"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 2048
  memory                   = 8192
  task_role_arn            = aws_iam_role.task["media-worker"].arn
  execution_role_arn       = aws_iam_role.execution["media-worker"].arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = "media-worker"
    image                  = "${aws_ecr_repository.application["media-worker"].repository_url}@${var.image_digests["media-worker"]}"
    essential              = true
    readonlyRootFilesystem = true
    user                   = "10001"
    stopTimeout            = 60
    command                = ["process"]
    environment = [for name, value in {
      AWS_REGION                     = var.region
      MEDIA_BUCKET                   = aws_s3_bucket.media.id
      BEDROCK_ANALYSIS_MODEL_ID      = var.analysis_model_id
      BEDROCK_ANALYSIS_REGION        = split(":", var.analysis_model_id)[3]
      BEDROCK_ANALYSIS_MODEL_VERSION = var.analysis_model_id
      OMP_NUM_THREADS                = "2"
    } : { name = name, value = value }]
    mountPoints = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = { "awslogs-group" = aws_cloudwatch_log_group.container["media-worker"].name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "processing" }
    }
  }])
  volume { name = "temporary" }
  depends_on = [aws_iam_role_policy.execution, aws_iam_role_policy.worker]
}
resource "aws_ecs_service" "application" {
  for_each                           = local.services
  name                               = "${local.name}-${each.key}"
  cluster                            = aws_ecs_cluster.application.id
  task_definition                    = aws_ecs_task_definition.service[each.key].arn
  desired_count                      = 0
  launch_type                        = "FARGATE"
  platform_version                   = "1.4.0"
  health_check_grace_period_seconds  = 120
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200
  enable_ecs_managed_tags            = true
  propagate_tags                     = "SERVICE"
  enable_execute_command             = false
  wait_for_steady_state              = true
  lifecycle {
    ignore_changes = [task_definition, desired_count]
  }
  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }
  network_configuration {
    subnets          = [for subnet in aws_subnet.application : subnet.id]
    security_groups  = [aws_security_group.service[each.key].id]
    assign_public_ip = false
  }
  load_balancer {
    target_group_arn = aws_lb_target_group.service[each.key].arn
    container_name   = each.key
    container_port   = local.service_ports[each.key]
  }
  dynamic "service_registries" {
    for_each = each.key == "api" ? [1] : []
    content { registry_arn = aws_service_discovery_service.api[0].arn }
  }
  depends_on = [aws_lb_listener.https, aws_lb_listener_rule.api, aws_sfn_state_machine.media, aws_route.application_egress, aws_vpc_security_group_ingress_rule.service, aws_vpc_security_group_egress_rule.service]
}
resource "aws_appautoscaling_target" "application" {
  for_each           = var.services_enabled ? local.services : toset([])
  min_capacity       = local.production ? 2 : 1
  max_capacity       = var.maximum_service_tasks
  resource_id        = "service/${aws_ecs_cluster.application.name}/${aws_ecs_service.application[each.key].name}"
  scalable_dimension = "ecs:service:DesiredCount"
  service_namespace  = "ecs"
}
resource "aws_appautoscaling_policy" "cpu" {
  for_each           = aws_appautoscaling_target.application
  name               = "${local.name}-${each.key}-cpu"
  policy_type        = "TargetTrackingScaling"
  resource_id        = each.value.resource_id
  scalable_dimension = each.value.scalable_dimension
  service_namespace  = each.value.service_namespace
  target_tracking_scaling_policy_configuration {
    target_value       = 65
    scale_in_cooldown  = 300
    scale_out_cooldown = 60
    predefined_metric_specification { predefined_metric_type = "ECSServiceAverageCPUUtilization" }
  }
}
