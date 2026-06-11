locals {
  name              = "${var.project_name}-${var.environment}"
  risk_engine_image = var.risk_engine_image_uri != "" ? var.risk_engine_image_uri : var.api_image_uri
  deploy_app        = var.platform_api_image_uri != "" && local.risk_engine_image != ""
}

resource "random_password" "api_origin_verify" {
  length  = 32
  special = false
}

resource "random_password" "preview_access" {
  length  = 32
  special = false
}

resource "random_id" "cognito_domain" {
  byte_length = 4
}

resource "aws_cognito_user_pool" "users" {
  name                     = "${local.name}-users"
  username_attributes      = ["email"]
  auto_verified_attributes = ["email"]

  admin_create_user_config {
    allow_admin_create_user_only = false
  }

  password_policy {
    minimum_length                   = 12
    require_lowercase                = true
    require_numbers                  = true
    require_symbols                  = true
    require_uppercase                = true
    temporary_password_validity_days = 7
  }

  account_recovery_setting {
    recovery_mechanism {
      name     = "verified_email"
      priority = 1
    }
  }
}

resource "aws_cognito_user_pool_client" "web" {
  name         = "${local.name}-web"
  user_pool_id = aws_cognito_user_pool.users.id

  generate_secret                      = false
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["code"]
  allowed_oauth_scopes                 = ["openid", "email", "profile"]
  supported_identity_providers         = ["COGNITO"]
  callback_urls                        = ["https://${aws_cloudfront_distribution.web.domain_name}/"]
  logout_urls                          = ["https://${aws_cloudfront_distribution.web.domain_name}/"]
  prevent_user_existence_errors        = "ENABLED"
}

resource "aws_cognito_user_pool_domain" "web" {
  domain       = "${local.name}-${random_id.cognito_domain.hex}"
  user_pool_id = aws_cognito_user_pool.users.id
}

resource "aws_ecr_repository" "api" {
  name                 = "${local.name}-api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_repository" "platform_api" {
  name                 = "${local.name}-platform-api"
  image_tag_mutability = "IMMUTABLE"

  image_scanning_configuration {
    scan_on_push = true
  }
}

resource "aws_ecr_lifecycle_policy" "api" {
  repository = aws_ecr_repository.api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Retain the latest 10 application images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_ecr_lifecycle_policy" "platform_api" {
  repository = aws_ecr_repository.platform_api.name
  policy = jsonencode({
    rules = [{
      rulePriority = 1
      description  = "Retain the latest 10 platform API images"
      selection = {
        tagStatus   = "any"
        countType   = "imageCountMoreThan"
        countNumber = 10
      }
      action = { type = "expire" }
    }]
  })
}

resource "aws_dynamodb_table" "operational" {
  name         = local.name
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "PK"
  range_key    = "SK"

  deletion_protection_enabled = var.enable_deletion_protection

  attribute {
    name = "PK"
    type = "S"
  }

  attribute {
    name = "SK"
    type = "S"
  }

  dynamic "attribute" {
    for_each = toset(["GSI1PK", "GSI1SK", "GSI2PK", "GSI2SK", "GSI3PK", "GSI3SK", "GSI4PK", "GSI4SK"])
    content {
      name = attribute.value
      type = "S"
    }
  }

  dynamic "global_secondary_index" {
    for_each = toset(["1", "2", "3", "4"])
    content {
      name            = "GSI${global_secondary_index.value}"
      hash_key        = "GSI${global_secondary_index.value}PK"
      range_key       = "GSI${global_secondary_index.value}SK"
      projection_type = "ALL"
    }
  }

  point_in_time_recovery {
    enabled = true
  }

  ttl {
    attribute_name = "expiresAt"
    enabled        = true
  }

  stream_enabled   = true
  stream_view_type = "NEW_AND_OLD_IMAGES"
}

resource "aws_rds_cluster" "relational" {
  count = var.enable_relational_store ? 1 : 0

  cluster_identifier          = "${local.name}-relational"
  engine                      = "aurora-postgresql"
  database_name               = "atmospath"
  master_username             = "atmospath_admin"
  manage_master_user_password = true
  enable_http_endpoint        = true
  storage_encrypted           = true
  deletion_protection         = var.enable_deletion_protection
  backup_retention_period     = var.environment == "prod" ? 7 : 1
  skip_final_snapshot         = var.environment != "prod"
  final_snapshot_identifier   = var.environment == "prod" ? "${local.name}-relational-final" : null

  serverlessv2_scaling_configuration {
    min_capacity             = var.relational_min_capacity
    max_capacity             = var.relational_max_capacity
    seconds_until_auto_pause = 900
  }
}

resource "aws_rds_cluster_instance" "relational" {
  count = var.enable_relational_store ? 1 : 0

  identifier          = "${local.name}-relational-1"
  cluster_identifier  = aws_rds_cluster.relational[0].id
  instance_class      = "db.serverless"
  engine              = aws_rds_cluster.relational[0].engine
  engine_version      = aws_rds_cluster.relational[0].engine_version
  publicly_accessible = false
}

resource "aws_sqs_queue" "optimization_dlq" {
  name                      = "${local.name}-optimization-dlq"
  message_retention_seconds = 1209600
  sqs_managed_sse_enabled   = true
}

resource "aws_sqs_queue" "optimization" {
  name                       = "${local.name}-optimization"
  visibility_timeout_seconds = 180
  message_retention_seconds  = 345600
  sqs_managed_sse_enabled    = true

  redrive_policy = jsonencode({
    deadLetterTargetArn = aws_sqs_queue.optimization_dlq.arn
    maxReceiveCount     = 3
  })
}

resource "aws_s3_bucket" "web" {
  bucket_prefix = "${local.name}-web-"
  force_destroy = var.environment != "prod"
}

resource "aws_s3_bucket" "weather" {
  bucket_prefix = "${local.name}-weather-"
  force_destroy = var.environment != "prod"
}

resource "aws_s3_bucket_public_access_block" "weather" {
  bucket = aws_s3_bucket.weather.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "weather" {
  bucket = aws_s3_bucket.weather.id

  rule {
    id     = "expire-weather-snapshots"
    status = "Enabled"

    filter {}

    expiration {
      days = 3
    }
  }
}

resource "aws_s3_bucket_public_access_block" "web" {
  bucket = aws_s3_bucket.web.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_versioning" "web" {
  bucket = aws_s3_bucket.web.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_cloudfront_origin_access_control" "web" {
  name                              = "${local.name}-web"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_cloudfront_response_headers_policy" "security" {
  name = "${local.name}-security-headers"

  security_headers_config {
    content_type_options { override = true }
    frame_options {
      frame_option = "DENY"
      override     = true
    }
    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      preload                    = true
      override                   = true
    }
    xss_protection {
      mode_block = true
      protection = true
      override   = true
    }
  }
}

resource "aws_cloudfront_function" "api_path" {
  name    = "${local.name}-api-path"
  runtime = "cloudfront-js-2.0"
  comment = "Strip the public /api prefix before forwarding to API Gateway."
  publish = true
  code    = <<-EOT
    function handler(event) {
      var request = event.request;
      var queryToken = request.querystring && request.querystring.preview && request.querystring.preview.value;
      var cookieToken = request.cookies && request.cookies['atmospath-preview'] && request.cookies['atmospath-preview'].value;
      if (${var.enable_preview_gate} && queryToken !== '${random_password.preview_access.result}' && cookieToken !== '${random_password.preview_access.result}') {
        return {
          statusCode: 404,
          statusDescription: 'Not Found',
          headers: { 'cache-control': { value: 'no-store' } }
        };
      }
      request.uri = request.uri.replace(/^\/api/, '') || '/';
      return request;
    }
  EOT
}

resource "aws_cloudfront_function" "spa_path" {
  name    = "${local.name}-spa-path"
  runtime = "cloudfront-js-2.0"
  comment = "Serve the SPA shell for client-side routes without masking API errors."
  publish = true
  code    = <<-EOT
    function handler(event) {
      var request = event.request;
      var queryToken = request.querystring && request.querystring.preview && request.querystring.preview.value;
      var cookieToken = request.cookies && request.cookies['atmospath-preview'] && request.cookies['atmospath-preview'].value;
      if (${var.enable_preview_gate} && queryToken !== '${random_password.preview_access.result}' && cookieToken !== '${random_password.preview_access.result}') {
        return {
          statusCode: 404,
          statusDescription: 'Not Found',
          headers: { 'cache-control': { value: 'no-store' } }
        };
      }
      if (!request.uri.includes('.')) {
        request.uri = '/index.html';
      }
      return request;
    }
  EOT
}

resource "aws_cloudfront_function" "preview_cookie" {
  name    = "${local.name}-preview-cookie"
  runtime = "cloudfront-js-2.0"
  comment = "Persist successful preview-link access in a secure browser cookie."
  publish = true
  code    = <<-EOT
    function handler(event) {
      var response = event.response;
      var queryToken = event.request.querystring && event.request.querystring.preview && event.request.querystring.preview.value;
      if (queryToken === '${random_password.preview_access.result}') {
        response.cookies = response.cookies || {};
        response.cookies['atmospath-preview'] = {
          value: '${random_password.preview_access.result}',
          attributes: 'Path=/; Max-Age=604800; Secure; HttpOnly; SameSite=Lax'
        };
        response.headers['cache-control'] = { value: 'no-store' };
      }
      return response;
    }
  EOT
}

resource "aws_cloudfront_distribution" "web" {
  enabled             = true
  default_root_object = "index.html"
  price_class         = "PriceClass_100"

  origin {
    domain_name              = aws_s3_bucket.web.bucket_regional_domain_name
    origin_id                = "web"
    origin_access_control_id = aws_cloudfront_origin_access_control.web.id
  }

  dynamic "origin" {
    for_each = local.deploy_app ? [1] : []
    content {
      domain_name = replace(aws_apigatewayv2_api.api[0].api_endpoint, "https://", "")
      origin_id   = "api"

      custom_header {
        name  = "X-Origin-Verify"
        value = random_password.api_origin_verify.result
      }

      custom_origin_config {
        http_port              = 80
        https_port             = 443
        origin_protocol_policy = "https-only"
        origin_ssl_protocols   = ["TLSv1.2"]
      }
    }
  }

  default_cache_behavior {
    target_origin_id           = "web"
    viewer_protocol_policy     = "redirect-to-https"
    allowed_methods            = ["GET", "HEAD", "OPTIONS"]
    cached_methods             = ["GET", "HEAD"]
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }

    function_association {
      event_type   = "viewer-request"
      function_arn = aws_cloudfront_function.spa_path.arn
    }

    function_association {
      event_type   = "viewer-response"
      function_arn = aws_cloudfront_function.preview_cookie.arn
    }
  }

  dynamic "ordered_cache_behavior" {
    for_each = local.deploy_app ? [1] : []
    content {
      path_pattern             = "/api/*"
      target_origin_id         = "api"
      viewer_protocol_policy   = "https-only"
      allowed_methods          = ["DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT"]
      cached_methods           = ["GET", "HEAD"]
      cache_policy_id          = "413f8c86-8e88-4d88-91f5-9f789f5b5d1c"
      origin_request_policy_id = "b689b0a8-53d0-40ab-baf2-68738e2966ac"

      function_association {
        event_type   = "viewer-request"
        function_arn = aws_cloudfront_function.api_path.arn
      }

      function_association {
        event_type   = "viewer-response"
        function_arn = aws_cloudfront_function.preview_cookie.arn
      }
    }
  }

  restrictions {
    geo_restriction {
      restriction_type = "whitelist"
      locations        = var.allowed_country_codes
    }
  }

  viewer_certificate {
    cloudfront_default_certificate = true
  }
}

data "aws_iam_policy_document" "web_bucket" {
  statement {
    actions   = ["s3:GetObject"]
    resources = ["${aws_s3_bucket.web.arn}/*"]

    principals {
      type        = "Service"
      identifiers = ["cloudfront.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "AWS:SourceArn"
      values   = [aws_cloudfront_distribution.web.arn]
    }
  }
}

resource "aws_s3_bucket_policy" "web" {
  bucket = aws_s3_bucket.web.id
  policy = data.aws_iam_policy_document.web_bucket.json
}

data "aws_iam_policy_document" "lambda_assume" {
  statement {
    actions = ["sts:AssumeRole"]
    principals {
      type        = "Service"
      identifiers = ["lambda.amazonaws.com"]
    }
  }
}

resource "aws_iam_role" "api" {
  name               = "${local.name}-api"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

resource "aws_iam_role" "worker" {
  name               = "${local.name}-worker"
  assume_role_policy = data.aws_iam_policy_document.lambda_assume.json
}

data "aws_iam_policy_document" "api" {
  statement {
    actions = [
      "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query",
      "dynamodb:TransactWriteItems"
    ]
    resources = [
      aws_dynamodb_table.operational.arn,
      "${aws_dynamodb_table.operational.arn}/index/*",
    ]
  }

  statement {
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.optimization.arn]
  }

  dynamic "statement" {
    for_each = local.deploy_app ? [1] : []
    content {
      actions   = ["lambda:InvokeFunction"]
      resources = [aws_lambda_function.risk_engine[0].arn]
    }
  }

  dynamic "statement" {
    for_each = var.enable_relational_store ? [1] : []
    content {
      actions = [
        "rds-data:BatchExecuteStatement",
        "rds-data:BeginTransaction",
        "rds-data:CommitTransaction",
        "rds-data:ExecuteStatement",
        "rds-data:RollbackTransaction",
      ]
      resources = [aws_rds_cluster.relational[0].arn]
    }
  }

  dynamic "statement" {
    for_each = var.enable_relational_store ? [1] : []
    content {
      actions   = ["secretsmanager:GetSecretValue"]
      resources = [aws_rds_cluster.relational[0].master_user_secret[0].secret_arn]
    }
  }
}

data "aws_iam_policy_document" "worker" {
  statement {
    actions = [
      "dynamodb:GetItem", "dynamodb:PutItem", "dynamodb:Query",
      "dynamodb:TransactWriteItems"
    ]
    resources = [
      aws_dynamodb_table.operational.arn,
      "${aws_dynamodb_table.operational.arn}/index/*",
    ]
  }

  statement {
    actions = [
      "sqs:ReceiveMessage", "sqs:DeleteMessage", "sqs:GetQueueAttributes"
    ]
    resources = [aws_sqs_queue.optimization.arn]
  }

  statement {
    actions   = ["s3:GetObject", "s3:PutObject"]
    resources = ["${aws_s3_bucket.weather.arn}/weather/*"]
  }
}

resource "aws_iam_role_policy" "api" {
  name   = "${local.name}-api"
  role   = aws_iam_role.api.id
  policy = data.aws_iam_policy_document.api.json
}

resource "aws_iam_role_policy" "worker" {
  name   = "${local.name}-worker"
  role   = aws_iam_role.worker.id
  policy = data.aws_iam_policy_document.worker.json
}

resource "aws_iam_role_policy_attachment" "api_logs" {
  role       = aws_iam_role.api.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_iam_role_policy_attachment" "worker_logs" {
  role       = aws_iam_role.worker.name
  policy_arn = "arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}

resource "aws_lambda_function" "api" {
  count                          = local.deploy_app ? 1 : 0
  function_name                  = "${local.name}-api"
  role                           = aws_iam_role.api.arn
  package_type                   = "Image"
  image_uri                      = var.platform_api_image_uri
  timeout                        = 30
  memory_size                    = 1024
  reserved_concurrent_executions = var.lambda_reserved_concurrency

  environment {
    variables = {
      DYNAMODB_TABLE               = aws_dynamodb_table.operational.name
      OPTIMIZATION_QUEUE_URL       = aws_sqs_queue.optimization.url
      ENVIRONMENT                  = var.environment
      CORS_ORIGINS                 = "https://invalid.local"
      API_ORIGIN_VERIFY_SECRET     = random_password.api_origin_verify.result
      RISK_ENGINE_MODE             = "lambda"
      RISK_ENGINE_FUNCTION_NAME    = aws_lambda_function.risk_engine[0].function_name
      RELATIONAL_STORE_ENABLED     = tostring(var.enable_relational_store)
      RELATIONAL_INITIALIZE_SCHEMA = "false"
      RELATIONAL_DATABASE          = "atmospath"
      RELATIONAL_RESOURCE_ARN      = var.enable_relational_store ? aws_rds_cluster.relational[0].arn : ""
      RELATIONAL_SECRET_ARN        = var.enable_relational_store ? aws_rds_cluster.relational[0].master_user_secret[0].secret_arn : ""
      AUTH_ENABLED                 = "true"
      AUTH_ISSUER_URI              = "https://cognito-idp.us-east-1.amazonaws.com/${aws_cognito_user_pool.users.id}"
    }
  }
}

resource "aws_lambda_function" "risk_engine" {
  count                          = local.deploy_app ? 1 : 0
  function_name                  = "${local.name}-risk-engine"
  role                           = aws_iam_role.worker.arn
  package_type                   = "Image"
  image_uri                      = local.risk_engine_image
  timeout                        = 60
  memory_size                    = 1536
  reserved_concurrent_executions = var.lambda_reserved_concurrency

  image_config {
    command = ["app.internal_handler.handler"]
  }

  environment {
    variables = {
      ENVIRONMENT             = var.environment
      WEATHER_SNAPSHOT_BUCKET = aws_s3_bucket.weather.id
    }
  }
}

resource "aws_lambda_function" "weather_collector" {
  count                          = local.deploy_app ? 1 : 0
  function_name                  = "${local.name}-weather-collector"
  role                           = aws_iam_role.worker.arn
  package_type                   = "Image"
  image_uri                      = local.risk_engine_image
  timeout                        = 120
  memory_size                    = 512
  reserved_concurrent_executions = 1

  image_config {
    command = ["app.weather_collector.handler"]
  }

  environment {
    variables = {
      ENVIRONMENT             = var.environment
      WEATHER_SNAPSHOT_BUCKET = aws_s3_bucket.weather.id
    }
  }
}

resource "aws_cloudwatch_event_rule" "weather_collector" {
  count               = local.deploy_app ? 1 : 0
  name                = "${local.name}-weather-hourly"
  description         = "Refresh the low-cost NOAA/NWS interest-point weather snapshot."
  schedule_expression = "rate(1 hour)"
}

resource "aws_cloudwatch_event_target" "weather_collector" {
  count     = local.deploy_app ? 1 : 0
  rule      = aws_cloudwatch_event_rule.weather_collector[0].name
  target_id = "weather-collector"
  arn       = aws_lambda_function.weather_collector[0].arn
}

resource "aws_lambda_permission" "weather_collector" {
  count         = local.deploy_app ? 1 : 0
  statement_id  = "AllowEventBridgeWeatherCollector"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.weather_collector[0].function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.weather_collector[0].arn
}

resource "aws_lambda_function" "worker" {
  count                          = local.deploy_app ? 1 : 0
  function_name                  = "${local.name}-optimizer"
  role                           = aws_iam_role.worker.arn
  package_type                   = "Image"
  image_uri                      = local.risk_engine_image
  timeout                        = 120
  memory_size                    = 2048
  reserved_concurrent_executions = var.lambda_reserved_concurrency

  image_config {
    command = ["app.worker.handler"]
  }

  environment {
    variables = {
      DYNAMODB_TABLE = aws_dynamodb_table.operational.name
      ENVIRONMENT    = var.environment
    }
  }
}

resource "aws_lambda_event_source_mapping" "worker" {
  count                              = local.deploy_app ? 1 : 0
  event_source_arn                   = aws_sqs_queue.optimization.arn
  function_name                      = aws_lambda_function.worker[0].arn
  batch_size                         = 5
  function_response_types            = ["ReportBatchItemFailures"]
  maximum_batching_window_in_seconds = 5
}

resource "aws_apigatewayv2_api" "api" {
  count         = local.deploy_app ? 1 : 0
  name          = local.name
  protocol_type = "HTTP"

}

resource "aws_apigatewayv2_integration" "api" {
  count                  = local.deploy_app ? 1 : 0
  api_id                 = aws_apigatewayv2_api.api[0].id
  integration_type       = "AWS_PROXY"
  integration_uri        = aws_lambda_function.api[0].invoke_arn
  payload_format_version = "2.0"
}

resource "aws_apigatewayv2_route" "api" {
  count     = local.deploy_app ? 1 : 0
  api_id    = aws_apigatewayv2_api.api[0].id
  route_key = "$default"
  target    = "integrations/${aws_apigatewayv2_integration.api[0].id}"
}

resource "aws_apigatewayv2_stage" "api" {
  count       = local.deploy_app ? 1 : 0
  api_id      = aws_apigatewayv2_api.api[0].id
  name        = "$default"
  auto_deploy = true

  access_log_settings {
    destination_arn = aws_cloudwatch_log_group.api_gateway[0].arn
    format = jsonencode({
      requestId        = "$context.requestId"
      requestTime      = "$context.requestTime"
      httpMethod       = "$context.httpMethod"
      routeKey         = "$context.routeKey"
      status           = "$context.status"
      responseLength   = "$context.responseLength"
      integrationError = "$context.integrationErrorMessage"
    })
  }

  default_route_settings {
    detailed_metrics_enabled = true
    throttling_burst_limit   = var.api_throttling_burst_limit
    throttling_rate_limit    = var.api_throttling_rate_limit
  }
}

resource "aws_lambda_permission" "api" {
  count         = local.deploy_app ? 1 : 0
  statement_id  = "AllowApiGateway"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.api[0].function_name
  principal     = "apigateway.amazonaws.com"
  source_arn    = "${aws_apigatewayv2_api.api[0].execution_arn}/*/*"
}

resource "aws_cloudwatch_log_group" "api" {
  count             = local.deploy_app ? 1 : 0
  name              = "/aws/lambda/${aws_lambda_function.api[0].function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "api_gateway" {
  count             = local.deploy_app ? 1 : 0
  name              = "/aws/apigateway/${local.name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "worker" {
  count             = local.deploy_app ? 1 : 0
  name              = "/aws/lambda/${aws_lambda_function.worker[0].function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "risk_engine" {
  count             = local.deploy_app ? 1 : 0
  name              = "/aws/lambda/${aws_lambda_function.risk_engine[0].function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_log_group" "weather_collector" {
  count             = local.deploy_app ? 1 : 0
  name              = "/aws/lambda/${aws_lambda_function.weather_collector[0].function_name}"
  retention_in_days = var.log_retention_days
}

resource "aws_cloudwatch_metric_alarm" "dlq" {
  alarm_name          = "${local.name}-optimization-dlq-visible"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Maximum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  dimensions = {
    QueueName = aws_sqs_queue.optimization_dlq.name
  }
}

resource "aws_cloudwatch_metric_alarm" "api_errors" {
  count               = local.deploy_app ? 1 : 0
  alarm_name          = "${local.name}-api-errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = aws_lambda_function.api[0].function_name
  }
}

resource "aws_cloudwatch_metric_alarm" "worker_errors" {
  count               = local.deploy_app ? 1 : 0
  alarm_name          = "${local.name}-optimizer-errors"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "Errors"
  namespace           = "AWS/Lambda"
  period              = 300
  statistic           = "Sum"
  threshold           = 0
  treat_missing_data  = "notBreaching"

  dimensions = {
    FunctionName = aws_lambda_function.worker[0].function_name
  }
}

resource "aws_cloudwatch_dashboard" "operations" {
  dashboard_name = "${local.name}-operations"
  dashboard_body = jsonencode({
    widgets = [
      {
        type = "metric", x = 0, y = 0, width = 12, height = 6,
        properties = {
          title = "Optimization queues", region = "us-east-1",
          metrics = [
            ["AWS/SQS", "ApproximateNumberOfMessagesVisible", "QueueName", aws_sqs_queue.optimization.name],
            [".", ".", ".", aws_sqs_queue.optimization_dlq.name],
          ]
        }
      },
      {
        type = "metric", x = 12, y = 0, width = 12, height = 6,
        properties = {
          title = "DynamoDB requests", region = "us-east-1",
          metrics = [
            ["AWS/DynamoDB", "ConsumedReadCapacityUnits", "TableName", aws_dynamodb_table.operational.name],
            [".", "ConsumedWriteCapacityUnits", ".", "."],
          ]
        }
      },
      {
        type = "metric", x = 0, y = 6, width = 12, height = 6,
        properties = {
          title = "Optional relational store capacity", region = "us-east-1",
          metrics = var.enable_relational_store ? [
            ["AWS/RDS", "ServerlessDatabaseCapacity", "DBClusterIdentifier", aws_rds_cluster.relational[0].cluster_identifier],
            [".", "DatabaseConnections", ".", "."],
          ] : []
        }
      }
    ]
  })
}
