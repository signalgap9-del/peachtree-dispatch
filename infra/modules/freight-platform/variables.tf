# FreightScaler Freight Platform — Input Variables

variable "region" {
  description = "AWS region for all resources"
  type        = string
  default     = "us-east-1"
}

variable "environment" {
  description = "Deployment environment name (dev, staging, prod)"
  type        = string
  default     = "dev"
}

variable "vpc_id" {
  description = "VPC ID where freight-platform resources will be deployed"
  type        = string
}

variable "private_subnet_ids" {
  description = "List of private subnet IDs for database, cache, and MSK resources"
  type        = list(string)
}

variable "project_name" {
  description = "Project name used as prefix for resource naming"
  type        = string
  default     = "freightscaler"
}
