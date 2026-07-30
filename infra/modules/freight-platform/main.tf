# FreightScaler Freight Platform — AWS Managed Service Stack
# Maps 1:1 from local Docker Compose (freight-platform profile)
# DO NOT APPLY without cost review: estimated ~$2,075/month

terraform {
  required_version = ">= 1.5"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = var.region
  default_tags {
    tags = {
      Project     = "awsresumeproject"
      ManagedBy   = "IaC"
      Environment = var.environment
      Component   = "freight-platform"
    }
  }
}
