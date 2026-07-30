# FreightScaler Freight Platform — Application Load Balancer
#
# Local → AWS mapping:
#   docker container "freight-nginx" (nginx reverse proxy) → AWS ALB
#
# The local nginx.conf routes by path prefix; the ALB listener rules
# replicate the same routing:
#   /telemetry/*    → telemetry-service
#   /tracking/*     → tracking-service
#   /loads/*        → load-board-service
#   /bids/*         → bid-service
#   /rankings/*     → ranking-service
#   /settlements/*  → settlement-service
#   /ws/tracking    → tracking-service (WebSocket, sticky sessions)

resource "aws_lb" "freight_platform" {
  name               = "${var.project_name}-alb-${var.environment}"
  internal           = false
  load_balancer_type = "application"
  subnets            = var.private_subnet_ids # replace with public subnet IDs for internet-facing
  security_groups    = [aws_security_group.alb.id]

  enable_deletion_protection = true

  tags = {
    Name = "${var.project_name}-alb-${var.environment}"
  }
}

# --- Target Groups ---

resource "aws_lb_target_group" "services" {
  for_each = local.services

  name        = "${var.project_name}-${each.key}-${var.environment}"
  port        = each.value.container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = each.value.health_path
    port                = "traffic-port"
    protocol            = "HTTP"
    healthy_threshold   = 3
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  tags = {
    Name = "${var.project_name}-${each.key}-tg-${var.environment}"
  }
}

# WebSocket target group for /ws/tracking — sticky sessions, long idle timeout
resource "aws_lb_target_group" "tracking_ws" {
  name        = "${var.project_name}-tracking-ws-${var.environment}"
  port        = local.services["tracking"].container_port
  protocol    = "HTTP"
  vpc_id      = var.vpc_id
  target_type = "ip"

  health_check {
    enabled             = true
    path                = "/actuator/health"
    port                = "traffic-port"
    protocol            = "HTTP"
    healthy_threshold   = 3
    unhealthy_threshold = 3
    timeout             = 5
    interval            = 30
    matcher             = "200"
  }

  stickiness {
    type    = "lb_cookie"
    enabled = true
    cookie_duration = 86400
  }

  tags = {
    Name = "${var.project_name}-tracking-ws-tg-${var.environment}"
  }
}

# --- HTTP Listener ---

resource "aws_lb_listener" "http" {
  load_balancer_arn = aws_lb.freight_platform.arn
  port              = 80
  protocol          = "HTTP"

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = "{\"error\":\"not found\"}"
      status_code  = "404"
    }
  }

  tags = {
    Name = "${var.project_name}-alb-listener-${var.environment}"
  }
}

# --- Listener Rules (path-based routing) ---

locals {
  path_rules = {
    telemetry  = "/telemetry/*"
    tracking   = "/tracking/*"
    load-board = "/loads/*"
    bid        = "/bids/*"
    ranking    = "/rankings/*"
    settlement = "/settlements/*"
  }
}

resource "aws_lb_listener_rule" "services" {
  for_each = local.path_rules

  listener_arn = aws_lb_listener.http.arn
  priority     = 100 + index(keys(local.path_rules), each.key)

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.services[each.key].arn
  }

  condition {
    path_pattern {
      values = [each.value]
    }
  }

  tags = {
    Name = "${var.project_name}-rule-${each.key}-${var.environment}"
  }
}

# WebSocket rule for /ws/tracking — highest priority, sticky sessions
resource "aws_lb_listener_rule" "tracking_ws" {
  listener_arn = aws_lb_listener.http.arn
  priority     = 10

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.tracking_ws.arn
  }

  condition {
    path_pattern {
      values = ["/ws/tracking"]
    }
  }

  tags = {
    Name = "${var.project_name}-rule-ws-tracking-${var.environment}"
  }
}

# --- ALB Security Group ---

resource "aws_security_group" "alb" {
  name_prefix = "${var.project_name}-alb-${var.environment}-"
  description = "Security group for freight-platform ALB"
  vpc_id      = var.vpc_id

  ingress {
    description = "HTTP"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
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
    Name = "${var.project_name}-alb-sg-${var.environment}"
  }
}
