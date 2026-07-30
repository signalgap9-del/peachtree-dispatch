# FreightScaler Freight Platform — Outputs

output "msk_bootstrap_brokers" {
  description = "MSK bootstrap broker connection string"
  value       = aws_msk_cluster.freight_platform.bootstrap_brokers_tls
}

output "aurora_endpoint" {
  description = "Aurora PostgreSQL writer endpoint"
  value       = aws_rds_cluster.freight_platform.endpoint
}

output "aurora_reader_endpoint" {
  description = "Aurora PostgreSQL reader endpoint (load-balanced across readers)"
  value       = aws_rds_cluster.freight_platform.reader_endpoint
}

output "redis_endpoint" {
  description = "ElastiCache Redis primary endpoint address"
  value       = aws_elasticache_replication_group.freight_platform.primary_endpoint_address
}

output "alb_dns_name" {
  description = "ALB DNS name (internet-facing entry point)"
  value       = aws_lb.freight_platform.dns_name
}

output "ecs_cluster_name" {
  description = "ECS cluster name for freight-platform services"
  value       = aws_ecs_cluster.freight_platform.name
}
