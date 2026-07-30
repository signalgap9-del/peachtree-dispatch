# FreightScaler Freight Platform — ElastiCache Redis
#
# Local → AWS mapping:
#   docker container "redis" (redis:7-alpine) → ElastiCache Redis replication group
#
# Used for: rate limiting, session cache, real-time tracking pub/sub,
# bid leaderboard sorted sets, and hot-path query caching.

resource "aws_elasticache_replication_group" "freight_platform" {
  replication_group_id = "${var.project_name}-redis-${var.environment}"
  description          = "Redis for freight-platform caching and pub/sub"

  node_type            = "cache.r6g.large"
  num_cache_clusters   = 2
  port                 = 6379
  parameter_group_name = aws_elasticache_parameter_group.freight_platform.name
  subnet_group_name    = aws_elasticache_subnet_group.freight_platform.name
  security_group_ids   = [aws_security_group.redis.id]

  engine_version             = "7.1"
  at_rest_encryption_enabled = true
  transit_encryption_enabled = true
  kms_key_id                 = aws_kms_key.redis.arn

  automatic_failover_enabled = true
  snapshot_retention_limit   = 7
  snapshot_window            = "03:00-05:00"
  maintenance_window         = "sun:05:00-sun:07:00"

  tags = {
    Name = "${var.project_name}-redis-${var.environment}"
  }
}

resource "aws_elasticache_parameter_group" "freight_platform" {
  name   = "${var.project_name}-redis7-${var.environment}"
  family = "redis7"

  parameter {
    name  = "maxmemory-policy"
    value = "allkeys-lru"
  }

  tags = {
    Name = "${var.project_name}-redis-params-${var.environment}"
  }
}

resource "aws_elasticache_subnet_group" "freight_platform" {
  name       = "${var.project_name}-redis-${var.environment}"
  subnet_ids = var.private_subnet_ids

  tags = {
    Name = "${var.project_name}-redis-subnets-${var.environment}"
  }
}

resource "aws_kms_key" "redis" {
  description             = "KMS key for ElastiCache Redis encryption — ${var.project_name}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = {
    Name = "${var.project_name}-redis-kms-${var.environment}"
  }
}

resource "aws_security_group" "redis" {
  name_prefix = "${var.project_name}-redis-${var.environment}-"
  description = "Security group for ElastiCache Redis"
  vpc_id      = var.vpc_id

  ingress {
    description = "Redis"
    from_port   = 6379
    to_port     = 6379
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
    Name = "${var.project_name}-redis-sg-${var.environment}"
  }
}
