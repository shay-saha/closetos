resource "aws_db_subnet_group" "main" {
  name       = local.name
  subnet_ids = [for subnet in aws_subnet.database : subnet.id]
}
resource "aws_db_parameter_group" "main" {
  name   = "${local.name}-postgres18"
  family = "postgres18"
  parameter {
    name         = "rds.force_ssl"
    value        = "1"
    apply_method = "pending-reboot"
  }
  parameter {
    name         = "log_min_duration_statement"
    value        = "250"
    apply_method = "immediate"
  }
}
resource "aws_db_instance" "main" {
  identifier                      = local.name
  engine                          = "postgres"
  engine_version                  = var.database_engine_version
  instance_class                  = var.database_instance_class
  db_name                         = "closetos"
  username                        = "closetos"
  manage_master_user_password     = true
  allocated_storage               = 20
  max_allocated_storage           = var.database_maximum_storage_gib
  storage_type                    = "gp3"
  storage_encrypted               = true
  publicly_accessible             = false
  db_subnet_group_name            = aws_db_subnet_group.main.name
  parameter_group_name            = aws_db_parameter_group.main.name
  vpc_security_group_ids          = [aws_security_group.service["database"].id]
  multi_az                        = local.production
  backup_retention_period         = local.production ? 14 : 7
  backup_window                   = "02:00-03:00"
  maintenance_window              = "sun:03:00-sun:04:00"
  copy_tags_to_snapshot           = true
  deletion_protection             = true
  skip_final_snapshot             = false
  final_snapshot_identifier       = "${local.name}-final"
  auto_minor_version_upgrade      = true
  allow_major_version_upgrade     = false
  apply_immediately               = false
  enabled_cloudwatch_logs_exports = ["postgresql", "upgrade"]
  performance_insights_enabled    = true
}
