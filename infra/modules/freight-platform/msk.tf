# FreightScaler Freight Platform — Amazon MSK (Managed Kafka)
#
# Local → AWS mapping:
#   docker container "kafka" (confluentinc/cp-kafka) → Amazon MSK cluster
#
# Same topics are used in both environments:
#   - telemetry-raw
#   - load-events
#   - bid-events
#   - shipment-events
#   - settlement-events

resource "aws_msk_cluster" "freight_platform" {
  cluster_name           = "${var.project_name}-msk-${var.environment}"
  kafka_version          = "3.7.x"
  number_of_broker_nodes = 3

  broker_node_group_info {
    instance_type   = "kafka.m5.large"
    client_subnets  = var.private_subnet_ids
    security_groups = [aws_security_group.msk.id]

    storage_info {
      ebs_storage_info {
        volume_size = 100
      }
    }
  }

  encryption_info {
    encryption_in_transit {
      client_broker = "TLS"
      in_cluster    = true
    }

    encryption_at_rest_kms_key_arn = aws_kms_key.msk.arn
  }

  logging_info {
    broker_logs {
      cloudwatch_logs {
        enabled   = true
        log_group = aws_cloudwatch_log_group.msk.name
      }
    }
  }

  tags = {
    Name = "${var.project_name}-msk-${var.environment}"
  }
}

resource "aws_kms_key" "msk" {
  description             = "KMS key for MSK encryption at rest — ${var.project_name}"
  deletion_window_in_days = 7
  enable_key_rotation     = true

  tags = {
    Name = "${var.project_name}-msk-kms-${var.environment}"
  }
}

resource "aws_cloudwatch_log_group" "msk" {
  name              = "/msk/${var.project_name}-${var.environment}"
  retention_in_days = 30

  tags = {
    Name = "${var.project_name}-msk-logs-${var.environment}"
  }
}

resource "aws_security_group" "msk" {
  name_prefix = "${var.project_name}-msk-${var.environment}-"
  description = "Security group for MSK brokers"
  vpc_id      = var.vpc_id

  ingress {
    description = "Kafka broker port"
    from_port   = 9092
    to_port     = 9092
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
  }

  ingress {
    description = "Kafka TLS port"
    from_port   = 9094
    to_port     = 9094
    protocol    = "tcp"
    cidr_blocks = ["10.0.0.0/8"]
  }

  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = [var.vpc_cidr] # broker is internal; egress scoped to the VPC
  }

  lifecycle {
    create_before_destroy = true
  }

  tags = {
    Name = "${var.project_name}-msk-sg-${var.environment}"
  }
}
