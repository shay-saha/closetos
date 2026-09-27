resource "aws_cloudwatch_log_group" "migration" {
  name              = "/ecs/${local.name}/migration"
  retention_in_days = var.log_retention_days
}
resource "aws_iam_role" "migration_task" {
  name               = "${local.name}-migration-task"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role" "migration_execution" {
  name               = "${local.name}-migration-execution"
  assume_role_policy = local.task_trust
}
resource "aws_iam_role_policy" "migration_execution" {
  name = "migration-startup"
  role = aws_iam_role.migration_execution.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      { Effect = "Allow", Action = ["ecr:GetAuthorizationToken"], Resource = "*" },
      { Effect = "Allow", Action = ["ecr:BatchCheckLayerAvailability", "ecr:GetDownloadUrlForLayer", "ecr:BatchGetImage"], Resource = aws_ecr_repository.application["api"].arn },
      { Effect = "Allow", Action = ["logs:CreateLogStream", "logs:PutLogEvents"], Resource = "${aws_cloudwatch_log_group.migration.arn}:log-stream:*" },
      { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = aws_db_instance.main.master_user_secret[0].secret_arn }
    ]
  })
}
resource "aws_ecs_task_definition" "migration" {
  count                    = local.runtime_enabled ? 1 : 0
  family                   = "${local.name}-migration"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  task_role_arn            = aws_iam_role.migration_task.arn
  execution_role_arn       = aws_iam_role.migration_execution.arn
  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = "X86_64"
  }
  container_definitions = jsonencode([{
    name                   = "migration"
    image                  = "${aws_ecr_repository.application["api"].repository_url}@${var.image_digests["api"]}"
    essential              = true
    readonlyRootFilesystem = true
    user                   = "closetos"
    stopTimeout            = 60
    entryPoint             = ["java"]
    command                = ["-XX:MaxRAMPercentage=75", "-Dloader.main=com.closetos.platform.infrastructure.DatabaseMigration", "-cp", "/app/app.jar", "org.springframework.boot.loader.launch.PropertiesLauncher"]
    environment            = [{ name = "DATABASE_URL", value = local.database_url }, { name = "DATABASE_USERNAME", value = aws_db_instance.main.username }]
    secrets                = [{ name = "DATABASE_PASSWORD", valueFrom = "${aws_db_instance.main.master_user_secret[0].secret_arn}:password::" }]
    mountPoints            = [{ sourceVolume = "temporary", containerPath = "/tmp", readOnly = false }]
    logConfiguration = {
      logDriver = "awslogs"
      options   = { "awslogs-group" = aws_cloudwatch_log_group.migration.name, "awslogs-region" = var.region, "awslogs-stream-prefix" = "migration" }
    }
  }])
  volume { name = "temporary" }
  depends_on = [aws_iam_role_policy.migration_execution]
}
