variable "project_name" {
  type    = string
  default = "peachtree-dispatch"
}

variable "environment" {
  type = string
}

variable "alert_email" {
  description = "Email address for alarm notifications (solo on-call)."
  type        = string
  default     = ""
}

variable "api_url" {
  description = "Base URL of the deployed API for the synthetic canary."
  type        = string
  default     = ""
}

variable "api_gateway_id" {
  description = "API Gateway v2 HTTP API ID for request metrics."
  type        = string
  default     = ""
}

variable "api_gateway_stage" {
  description = "API Gateway stage name."
  type        = string
  default     = "$default"
}

variable "dynamodb_table_name" {
  description = "DynamoDB operational table name for throttle alarms."
  type        = string
  default     = ""
}

variable "dlq_name" {
  description = "SQS optimization DLQ name."
  type        = string
  default     = ""
}

# -- Alarm thresholds --------------------------------------------------------

variable "api_5xx_rate_threshold_pct" {
  description = "API 5xx error rate percentage that triggers an alarm."
  type        = number
  default     = 1
}

variable "api_latency_p95_threshold_ms" {
  description = "API latency p95 threshold in milliseconds."
  type        = number
  default     = 2000
}

variable "api_4xx_threshold" {
  description = "API Gateway 4xx count threshold (abuse signal) per 5-min period."
  type        = number
  default     = 50
}

variable "llm_daily_cost_threshold_usd" {
  description = "LLM daily cost alarm threshold in USD. Alert fires before the cap."
  type        = number
  default     = 8
}

variable "canary_failure_threshold" {
  description = "Number of consecutive canary failures before alarming."
  type        = number
  default     = 2
}

variable "canary_schedule_expression" {
  description = "EventBridge schedule for the synthetic canary."
  type        = string
  default     = "rate(5 minutes)"
}

variable "log_retention_days" {
  type    = number
  default = 14
}
