# FreightScaler Freight Platform — ECS Fargate
#
# Local → AWS mapping:
#   docker container "telemetry-service" (×1)   → ECS service "telemetry" (desired_count = 2)
#   docker container "tracking-service" (×1)    → ECS service "tracking" (desired_count = 2)
#   docker container "load-board-service" (×1)  → ECS service "load-board" (desired_count = 1)
#   docker container "bid-service" (×1)         → ECS service "bid" (desired_count = 1)
#   docker container "ranking-service" (×1)     → ECS service "ranking" (desired_count = 1)
#   docker container "settlement-service" (×1)  → ECS service "settlement" (desired_count = 1)
#
# All containers run as Fargate tasks (serverless — no EC2 instance management).
# Images are pulled from ECR (placeholder URLs below; replace with actual repo URIs).

resource "aws_ecs_cluster" "freight_platform" {
  name = "${var.project_name}-freight-platform-${var.environment}"

  setting {
    name  = "containerInsights"
    value = "enabled"
  }

  tags = {
    Name = "${var.project_name}-ecs-${var.environment}"
  }
}

# --- CloudWatch Log Groups ---

resource "aws_cloudwatch_log_group" "ecs" {
  for_each = toset([
    "telemetry",
    "tracking",
    "load-board",
    "bid",
    "ranking",
    "settlement",
  ])

  name              = "/ecs/${var.project_name}/${each.key}-${var.environment}"
  retention_in_days = 30

  tags = {
    Name = "${var.project_name}-${each.key}-logs-${var.environment}"
  }
}

# --- Task Definitions ---

locals {
  ecr_registry = "123456789012.dkr.ecr.${var.region}.amazonaws.com" # placeholder account

  services = {
    telemetry = {
      image         = "${local.ecr_registry}/${var.project_name}/telemetry-service:latest"
      container_port = 8081
      desired_count = 2
      health_path   = "/actuator/health"
    }
    tracking = {
      image         = "${local.ecr_registry}/${var.project_name}/tracking-service:latest"
      container_port = 8082
      desired_count = 2
      health_path   = "/actuator/health"
    }
    load-board = {
      image         = "${local.ecr_registry}/${var.project_name}/load-board-service:latest"
      container_port = 8083
      desired_count = 1
      health_path   = "/actuator/health"
    }
    bid = {
      image         = "${local.ecr_registry}/${var.project_name}/bid-service:latest"
      container_port = 8084
      desired_count = 1
      health_path   = "/actuator/health"
    }
    ranking = {
      image         = "${local.ecr_registry}/${var.project_name}/ranking-service:latest"
      container_port = 8085
      desired_count = 1
      health_path   = "/actuator/health"
    }
    settlement = {
      image         = "${local.ecr_registry}/${var.project_name}/settlement-service:latest"
      container_port = 8086
      desired_count = 1
      health_path   = "/actuator/health"
    }
  }
}

resource "aws_ecs_task_definition" "services" {
  for_each = local.services

  family                   = "${var.project_name}-${each.key}-${var.environment}"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.ecs_execution.arn
  task_role_arn            = aws_iam_role.ecs_task.arn

  container_definitions = jsonencode([
    {
      name      = each.key
      image     = each.value.image
      essential = true

      portMappings = [
        {
          containerPort = each.value.container_port
          protocol      = "tcp"
        }
      ]

      logConfiguration = {
        logDriver = "awslogs"
        options = {
          "awslogs-group"         = aws_cloudwatch_log_group.ecs[each.key].name
          "awslogs-region"        = var.region
          "awslogs-stream-prefix" = each.key
        }
      }

      environment = [
        { name = "SPRING_PROFILES_ACTIVE", value = var.environment },
        { name = "SERVICE_NAME", value = each.key },
      ]
    }
  ])

  tags = {
    Name = "${var.project_name}-${each.key}-taskdef-${var.environment}"
  }
}

# --- ECS Services ---

resource "aws_ecs_service" "services" {
  for_each = local.services

  name            = "${var.project_name}-${each.key}-${var.environment}"
  cluster         = aws_ecs_cluster.freight_platform.id
  task_definition = aws_ecs_task_definition.services[each.key].arn
  desired_count   = each.value.desired_count
  launch_type     = "FARGATE"

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [aws_security_group.ecs_tasks.id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = aws_lb_target_group.services[each.key].arn
    container_name   = each.key
    container_port   = each.value.container_port
  }

  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  depends_on = [aws_lb_listener.http]

  tags = {
    Name = "${var.project_name}-${each.key}-svc-${var.environment}"
  }
}

# --- IAM Roles ---

resource "aws_iam_role" "ecs_execution" {
  name = "${var.project_name}-ecs-execution-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-ecs-execution-role-${var.environment}"
  }
}

resource "aws_iam_role_policy_attachment" "ecs_execution" {
  role       = aws_iam_role.ecs_execution.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AmazonECSTaskExecutionRolePolicy"
}

resource "aws_iam_role" "ecs_task" {
  name = "${var.project_name}-ecs-task-${var.environment}"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Action = "sts:AssumeRole"
        Effect = "Allow"
        Principal = {
          Service = "ecs-tasks.amazonaws.com"
        }
      }
    ]
  })

  tags = {
    Name = "${var.project_name}-ecs-task-role-${var.environment}"
  }
}

# --- Security Group for ECS Tasks ---

resource "aws_security_group" "ecs_tasks" {
  name_prefix = "${var.project_name}-ecs-tasks-${var.environment}-"
  description = "Security group for ECS Fargate tasks"
  vpc_id      = var.vpc_id

  ingress {
    description     = "From ALB"
    from_port       = 8081
    to_port         = 8086
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
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
    Name = "${var.project_name}-ecs-tasks-sg-${var.environment}"
  }
}
