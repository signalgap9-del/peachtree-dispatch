terraform {
  required_version = "~> 1.13.0"

  backend "s3" {}

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
  }
}

provider "aws" {
  region = "us-east-1"

  default_tags {
    tags = {
      Project     = "peachtree-dispatch"
      Environment = "dev"
      ManagedBy   = "Terraform"
      Repository  = "signalgap9-del/peachtree-dispatch"
    }
  }
}
