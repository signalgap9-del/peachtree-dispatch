# FreightScaler Freight Platform — Aurora PostgreSQL
#
# Local → AWS mapping:
#   docker container "postgres-primary" → Aurora writer instance
#   docker container "postgres-replica" → Aurora reader instances (×2)
#
# Aurora provides automatic failover, storage autoscaling (100GB–2TB),
# and managed replication — replacing the manual streaming replication
# configured in docker/postgres-init/.

resource "aws_rds_cluster_parameter_group" "freight_platform" {
  name        = "${var.project_name}-aurora-pg16-${var.environment}"
  family      = "aurora-postgresql16"
  description = "Aurora PostgreSQL 16 cluster parameters for freight-platform"

  parameter {
    name         = "shared_preload_libraries"
    value        = "timescaledb,pg_stat_statements,pg_cron"
    apply_method = "pending-reboot"
  }

  parameter {
    name         = "log_min_duration_statement"
    value        = "250"
    apply_method = "immediate"
  }

  tags = {
    Name = "${var.project_name}-aurora-params-${var.environment}"
  }
}

resource "aws_rds_cluster" "freight_platform" {
  cluster_identifier              = "${var.project_name}-aurora-${var.environment}"
  engine                          = "aurora-postgresql"
  engine_version                  = "16.4"
  database_name                   = "freightplatform"
  master_username                 = "freight_admin"
  manage_master_user_password     = true
  db_cluster_parameter_group_name = aws_rds_cluster_parameter_group.freight_platform.name
  db_subnet_group_name            = aws_db_subnet_group.freight_platform.name
  vpc_security_group_ids          = [aws_security_group.aurora.id]

  # Storage autoscaling: min 100GB, max 2TB
  storage_type                   = "aurora-iopt1"
  allocated_storage              = 100
  storage_encrypted              = true
  kms_key_id                     = aws_kms_key.aurora.arn
  max_allocated_storage          = 2000

  backup_retention_period = 7
  deletion_protection     = true
  skip_final_snapshot     = false
  final_snapshot_identifier = "${var.project_name}-aurora-final-${var.environment}"

  tags = {
    Name = "${var.project_name}-aurora-${var.environment}"
  }
}

# Writer instance (maps to local postgres-primary)
resource "aws_rds_cluster_instance" "writer" {
  identifier              = "${var.project_name}-aurora-writer-${var.environment}"
  cluster_identifier      = aws_rds_cluster.freight_platform.id
  instance_class          = "db.r6g.large"
  engine                  = aws_rds_cluster.freight_platform.engine
  engine_version          = aws_rds_cluster.freight_platform.engine_version
  db_subnet_group_name    = aws_db_subnet_group.freight_platform.name
  publicly_accessible     = false
  performance_insights_enabled = true

  tags = {
    Name = "${var.project_name}-aurora-writer-${var.environment}"
    Role = "writer"
  }
}

# Reader instances (maps to local postgres-replica)
resource "aws_rds_cluster_instance" "readers" {
  count                   = 2
  identifier              = "${var.project_name}-aurora-reader-${count.index + 1}-${var.environment}"
  cluster_identifier      = aws_rds_cluster.freight_platform.id
  instance_class          = "db.r6g.large"
  engine                  = aws_rds_cluster.freight_platform.engine
  engine_version          = aws_rds_cluster.freight_platform.engine_version
  db_subnet_group_name    = aws_db_subnet_group.freight_platform.name
  publicly_accessible     = false
  performance_insights_enabled = true

  tags = {
    Name = "${var.project_name}-aurora-reader-${count.index + 1}-${var.environment}"
    Role = "reader"
  }
}

resource "aws_db_subnet_group" "freight_platform" {
  name       = "${var.project_name}-aurora-${var.environment}"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "${var.project_name}-aurora-subnets-${var.environment}"
  }
}

resource "aws_kms_key" "aurora" {
  description             = "KMS key for Aurora encryption — ${var.project_name}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = {
    Name = "${var.project_name}-aurora-kms-${var.environment}"
  }
}

resource "aws_security_group" "aurora" {
  name_prefix = "${var.project_name}-aurora-${var.environment}-"
  description = "Security group for Aurora PostgreSQL cluster"
  vpc_id      = var.vpc_id

  ingress {
    description = "PostgreSQL"
    from_port   = 5432
    to_port     = 5432
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.project_name}-aurora-sg-${var.environment}"
  }
}
