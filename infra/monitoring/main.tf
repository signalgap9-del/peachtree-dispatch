locals {
  name = "${var.project_name}-${var.environment}"

  lambda_function_names = [
    "${local.name}-api",
    "${local.name}-optimizer",
    "${local.name}-risk-engine",
    "${local.name}-weather-collector",
  ]

  deploy_canary = var.api_url != ""

  common_tags = {
    Project     = var.project_name
    ManagedBy   = "IaC"
    Environment = var.environment
  }
}

# ---------------------------------------------------------------------------
# SNS topic for alarm notifications (solo on-call)
# ---------------------------------------------------------------------------

resource "aws_sns_topic" "alarms" {
  name              = "${local.name}-alarms"
  kms_master_key_id = "alias/aws/sns" # AWS-managed key; no additional cost
}

resource "aws_sns_topic_subscription" "email" {
  count     = var.alert_email != "" ? 1 : 0
  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alert_email
}

# ---------------------------------------------------------------------------
# API Gateway alarms
# ---------------------------------------------------------------------------

# 5xx error rate > threshold (metric math over 5-min window)
resource "aws_cloudwatch_metric_alarm" "api_5xx_rate" {
  count               = var.api_gateway_id != "" ? 1 : 0
  alarm_name          = "${local.name}-api-5xx-rate"
  alarm_description   = "API 5xx error rate exceeds ${var.api_5xx_rate_threshold_pct}% over 5 minutes."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  threshold           = var.api_5xx_rate_threshold_pct
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  metric_query {
    id          = "rate"
    expression  = "IF(total > 0, errors / total * 100, 0)"
    label       = "5xx rate %"
    return_data = true
  }

  metric_query {
    id = "errors"
    metric {
      metric_name = "5xx"
      namespace   = "AWS/ApiGatewayV2"
      period      = 300
      stat        = "Sum"
      dimensions = {
        ApiId = var.api_gateway_id
        Stage = var.api_gateway_stage
      }
    }
  }

  metric_query {
    id = "total"
    metric {
      metric_name = "Count"
      namespace   = "AWS/ApiGatewayV2"
      period      = 300
      stat        = "Sum"
      dimensions = {
        ApiId = var.api_gateway_id
        Stage = var.api_gateway_stage
      }
    }
  }
}

# API latency p95
resource "aws_cloudwatch_metric_alarm" "api_latency_p95" {
  count               = var.api_gateway_id != "" ? 1 : 0
  alarm_name          = "${local.name}-api-latency-p95"
  alarm_description   = "API latency p95 exceeds ${var.api_latency_p95_threshold_ms}ms."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 2
  metric_name         = "Latency"
  namespace           = "AWS/ApiGatewayV2"
  period              = 300
  extended_statistic  = "p95"
  threshold           = var.api_latency_p95_threshold_ms
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    ApiId = var.api_gateway_id
    Stage = var.api_gateway_stage
  }
}

# API Gateway 4xx spike (abuse signal)
resource "aws_cloudwatch_metric_alarm" "api_4xx_spike" {
  count               = var.api_gateway_id != "" ? 1 : 0
  alarm_name          = "${local.name}-api-4xx-spike"
  alarm_description   = "API Gateway 4xx count exceeds ${var.api_4xx_threshold} in 5 minutes (possible abuse)."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "4xx"
  namespace           = "AWS/ApiGatewayV2"
  period              = 300
  statistic           = "Sum"
  threshold           = var.api_4xx_threshold
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]

  dimensions = {
    ApiId = var.api_gateway_id
    Stage = var.api_gateway_stage
  }
}

# ---------------------------------------------------------------------------
# Lambda alarms (errors + throttles per function)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "lambda_errors" {
  for_each            = toset(local.lambda_function_names)
  alarm_name          = "${local.name}-${each.value}-errors"
  alarm_description   = "Lambda ${each.value} errors > 0."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    FunctionName = each.value
  }
}

resource "aws_cloudwatch_metric_alarm" "lambda_throttles" {
  for_each            = toset(local.lambda_function_names)
  alarm_name          = "${local.name}-${each.value}-throttles"
  alarm_description   = "Lambda ${each.value} throttles > 0."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Throttles"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    FunctionName = each.value
  }
}

# ---------------------------------------------------------------------------
# DynamoDB throttle alarms
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "dynamodb_read_throttle" {
  count               = var.dynamodb_table_name != "" ? 1 : 0
  alarm_name          = "${local.name}-dynamodb-read-throttle"
  alarm_description   = "DynamoDB read throttle events detected."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ReadThrottleEvents"
  namespace           = "AWS/DynamoDB"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    TableName = var.dynamodb_table_name
  }
}

resource "aws_cloudwatch_metric_alarm" "dynamodb_write_throttle" {
  count               = var.dynamodb_table_name != "" ? 1 : 0
  alarm_name          = "${local.name}-dynamodb-write-throttle"
  alarm_description   = "DynamoDB write throttle events detected."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "WriteThrottleEvents"
  namespace           = "AWS/DynamoDB"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    TableName = var.dynamodb_table_name
  }
}

# ---------------------------------------------------------------------------
# LLM daily cost alarm (custom metric published by the cost circuit breaker)
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "llm_daily_cost" {
  alarm_name          = "${local.name}-llm-daily-cost"
  alarm_description   = "LLM daily cost approaching cap (threshold $${var.llm_daily_cost_threshold_usd})."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "LLMDailyCost"
  namespace           = "AtmosPath/Cost"
  period              = 3600
  statistic           = "Maximum"
  threshold           = var.llm_daily_cost_threshold_usd
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    Environment = var.environment
  }
}

# ---------------------------------------------------------------------------
# SQS DLQ alarm
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_metric_alarm" "dlq_messages" {
  count               = var.dlq_name != "" ? 1 : 0
  alarm_name          = "${local.name}-dlq-messages"
  alarm_description   = "Messages visible in the optimization DLQ."
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "notBreaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    QueueName = var.dlq_name
  }
}

# ---------------------------------------------------------------------------
# Synthetic canary
# ---------------------------------------------------------------------------

data "archive_file" "canary" {
  count       = local.deploy_canary ? 1 : 0
  type        = "zip"
  source_file = "${path.module}/canary/index.py"
  output_path = "${path.module}/.build/canary.zip"
}

resource "aws_iam_role" "canary" {
  count = local.deploy_canary ? 1 : 0
  name  = "${local.name}-canary"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Action    = "sts:AssumeRole"
      Effect    = "Allow"
      Principal = { Service = "lambda.amazonaws.com" }
    }]
  })

  tags = local.common_tags
}

resource "aws_iam_role_policy" "canary" {
  count = local.deploy_canary ? 1 : 0
  name  = "${local.name}-canary"
  role  = aws_iam_role.canary[0].id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid      = "CloudWatchMetrics"
        Effect   = "Allow"
        Action   = ["cloudwatch:PutMetricData"]
        Resource = "*"
      },
      {
        Sid    = "CloudWatchLogs"
        Effect = "Allow"
        Action = [
          "logs:CreateLogGroup",
          "logs:CreateLogStream",
          "logs:PutLogEvents",
        ]
        Resource = "arn:aws:logs:*:*:*"
      },
    ]
  })
}

resource "aws_lambda_function" "canary" {
  count            = local.deploy_canary ? 1 : 0
  function_name    = "${local.name}-canary"
  role             = aws_iam_role.canary[0].arn
  handler          = "index.handler"
  runtime          = "python3.12"
  timeout          = 30
  memory_size      = 128
  filename         = data.archive_file.canary[0].output_path
  source_code_hash = data.archive_file.canary[0].output_base64sha256

  environment {
    variables = {
      API_BASE_URL = var.api_url
      ENVIRONMENT  = var.environment
    }
  }

  tags = local.common_tags
}

resource "aws_cloudwatch_log_group" "canary" {
  count             = local.deploy_canary ? 1 : 0
  name              = "/aws/lambda/${local.name}-canary"
  retention_in_days = var.log_retention_days

  tags = local.common_tags
}

resource "aws_cloudwatch_event_rule" "canary" {
  count               = local.deploy_canary ? 1 : 0
  name                = "${local.name}-canary"
  description         = "Synthetic canary: health + route-planning smoke check."
  schedule_expression = var.canary_schedule_expression

  tags = local.common_tags
}

resource "aws_cloudwatch_event_target" "canary" {
  count     = local.deploy_canary ? 1 : 0
  rule      = aws_cloudwatch_event_rule.canary[0].name
  target_id = "canary"
  arn       = aws_lambda_function.canary[0].arn
}

resource "aws_lambda_permission" "canary" {
  count         = local.deploy_canary ? 1 : 0
  statement_id  = "AllowEventBridgeCanary"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.canary[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.canary[0].arn
}

# Canary failure alarm
resource "aws_cloudwatch_metric_alarm" "canary_failure" {
  count               = local.deploy_canary ? 1 : 0
  alarm_name          = "${local.name}-canary-failure"
  alarm_description   = "Synthetic canary failed ${var.canary_failure_threshold} consecutive checks."
  comparison_operator = "LessThanThreshold"
  evaluation_periods  = var.canary_failure_threshold
  metric_name         = "Success"
  namespace           = "AtmosPath/Canary"
  period              = 300
  statistic           = "Minimum"
  threshold           = 1
  treat_missing_data  = "breaching"
  alarm_actions       = [aws_sns_topic.alarms.arn]
  ok_actions          = [aws_sns_topic.alarms.arn]

  dimensions = {
    Check = "overall"
  }
}

# ---------------------------------------------------------------------------
# CloudWatch Dashboard
# ---------------------------------------------------------------------------

resource "aws_cloudwatch_dashboard" "monitoring" {
  dashboard_name = "${local.name}-monitoring"

  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6,
        properties = {
          title  = "API request count"
          region = "us-east-1"
          metrics = var.api_gateway_id != "" ? [
            ["AWS/ApiGatewayV2", "Count", "ApiId", var.api_gateway_id, "Stage", var.api_gateway_stage],
            [".", "4xx", ".", ".", ".", "."],
            [".", "5xx", ".", ".", ".", "."],
          ] : []
          view   = "timeSeries"
          stat   = "Sum"
          period = 300
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6,
        properties = {
          title  = "API latency percentiles"
          region = "us-east-1"
          metrics = var.api_gateway_id != "" ? [
            ["AWS/ApiGatewayV2", "Latency", "ApiId", var.api_gateway_id, "Stage", var.api_gateway_stage],
          ] : []
          view   = "timeSeries"
          stat   = "p95"
          period = 300
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6,
        properties = {
          title  = "Lambda errors"
          region = "us-east-1"
          metrics = [
            for fn in local.lambda_function_names :
            ["AWS/Lambda", "Errors", "FunctionName", fn]
          ]
          view   = "timeSeries"
          stat   = "Sum"
          period = 300
        }
      },
      {
        type = "metric", x = 12, y = 6, width = 12, height = 6,
        properties = {
          title  = "Lambda throttles"
          region = "us-east-1"
          metrics = [
            for fn in local.lambda_function_names :
            ["AWS/Lambda", "Throttles", "FunctionName", fn]
          ]
          view   = "timeSeries"
          stat   = "Sum"
          period = 300
        }
      },
      {
        type = "metric", x = 0, y = 12, width = 12, height = 6,
        properties = {
          title  = "DynamoDB throttle events"
          region = "us-east-1"
          metrics = var.dynamodb_table_name != "" ? [
            ["AWS/DynamoDB", "ReadThrottleEvents", "TableName", var.dynamodb_table_name],
            [".", "WriteThrottleEvents", ".", "."],
          ] : []
          view   = "timeSeries"
          stat   = "Sum"
          period = 300
        }
      },
      {
        type = "metric", x = 12, y = 12, width = 12, height = 6,
        properties = {
          title  = "LLM daily cost (USD)"
          region = "us-east-1"
          metrics = [
            ["AtmosPath/Cost", "LLMDailyCost", "Environment", var.environment],
          ]
          view   = "singleValue"
          stat   = "Maximum"
          period = 3600
        }
      },
      {
        type = "metric", x = 0, y = 18, width = 12, height = 6,
        properties = {
          title  = "Synthetic canary health"
          region = "us-east-1"
          metrics = local.deploy_canary ? [
            ["AtmosPath/Canary", "Success", "Check", "health"],
            [".", ".", ".", "risk_national"],
            [".", ".", ".", "overall"],
          ] : []
          view   = "timeSeries"
          stat   = "Minimum"
          period = 300
        }
      },
      {
        type = "metric", x = 12, y = 18, width = 12, height = 6,
        properties = {
          title  = "Canary latency (ms)"
          region = "us-east-1"
          metrics = local.deploy_canary ? [
            ["AtmosPath/Canary", "Latency", "Check", "health"],
            [".", ".", ".", "risk_national"],
          ] : []
          view   = "timeSeries"
          stat   = "Average"
          period = 300
        }
      },
      {
        type = "metric", x = 0, y = 24, width = 12, height = 6,
        properties = {
          title  = "Optimization DLQ messages"
          region = "us-east-1"
          metrics = var.dlq_name != "" ? [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", var.dlq_name],
          ] : []
          view   = "timeSeries"
          stat   = "Maximum"
          period = 300
        }
      },
    ]
  })
}
