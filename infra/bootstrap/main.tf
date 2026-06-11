data "aws_caller_identity" "current" {}

locals {
  common_tags = {
    Project     = "peachtree-dispatch"
    Environment = "bootstrap"
    ManagedBy   = "Terraform"
    Repository  = var.github_repository
  }
}

resource "aws_s3_bucket" "terraform_state" {
  bucket = var.state_bucket_name
}

resource "aws_s3_bucket_versioning" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_kms_key" "terraform_state" {
  description             = "Encrypts Peachtree Dispatch Terraform state"
  deletion_window_in_days = 30
  enable_key_rotation     = true
}

resource "aws_kms_alias" "terraform_state" {
  name          = "alias/peachtree-dispatch-terraform-state"
  target_key_id = aws_kms_key.terraform_state.key_id
}

resource "aws_s3_bucket_server_side_encryption_configuration" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  rule {
    bucket_key_enabled = true

    apply_server_side_encryption_by_default {
      kms_master_key_id = aws_kms_key.terraform_state.arn
      sse_algorithm     = "aws:kms"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "terraform_state" {
  bucket = aws_s3_bucket.terraform_state.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_iam_openid_connect_provider" "github" {
  url = "https://token.actions.githubusercontent.com"

  client_id_list = ["sts.amazonaws.com"]
}

data "aws_iam_policy_document" "github_plan_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values = [
        "repo:${var.github_repository}:pull_request",
        "repo:${var.github_repository}:ref:refs/heads/main",
      ]
    }
  }
}

data "aws_iam_policy_document" "github_apply_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:dev"]
    }
  }
}

data "aws_iam_policy_document" "github_prod_assume_role" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [aws_iam_openid_connect_provider.github.arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringLike"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:production"]
    }
  }
}

resource "aws_iam_role" "terraform_plan" {
  name               = "peachtree-dispatch-github-plan"
  assume_role_policy = data.aws_iam_policy_document.github_plan_assume_role.json
}

resource "aws_iam_role" "terraform_apply" {
  name               = "peachtree-dispatch-github-apply"
  assume_role_policy = data.aws_iam_policy_document.github_apply_assume_role.json
}

resource "aws_iam_role" "terraform_prod" {
  name               = "peachtree-dispatch-github-prod"
  assume_role_policy = data.aws_iam_policy_document.github_prod_assume_role.json
}

resource "aws_iam_role_policy_attachment" "terraform_plan_read_only" {
  role       = aws_iam_role.terraform_plan.name
  policy_arn = "arn:aws:iam::aws:policy/ReadOnlyAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_apply_power_user" {
  role       = aws_iam_role.terraform_apply.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

resource "aws_iam_role_policy_attachment" "terraform_prod_power_user" {
  role       = aws_iam_role.terraform_prod.name
  policy_arn = "arn:aws:iam::aws:policy/PowerUserAccess"
}

data "aws_iam_policy_document" "terraform_state_access" {
  statement {
    actions = [
      "s3:GetBucketVersioning",
      "s3:ListBucket",
    ]
    resources = [aws_s3_bucket.terraform_state.arn]
  }

  statement {
    actions = [
      "s3:DeleteObject",
      "s3:GetObject",
      "s3:PutObject",
    ]
    resources = ["${aws_s3_bucket.terraform_state.arn}/*"]
  }

  statement {
    actions = [
      "kms:Decrypt",
      "kms:DescribeKey",
      "kms:Encrypt",
      "kms:GenerateDataKey",
    ]
    resources = [aws_kms_key.terraform_state.arn]
  }
}

resource "aws_iam_policy" "terraform_state_access" {
  name   = "peachtree-dispatch-terraform-state-access"
  policy = data.aws_iam_policy_document.terraform_state_access.json
}

resource "aws_iam_role_policy_attachment" "terraform_plan_state" {
  role       = aws_iam_role.terraform_plan.name
  policy_arn = aws_iam_policy.terraform_state_access.arn
}

resource "aws_iam_role_policy_attachment" "terraform_apply_state" {
  role       = aws_iam_role.terraform_apply.name
  policy_arn = aws_iam_policy.terraform_state_access.arn
}

resource "aws_iam_role_policy_attachment" "terraform_prod_state" {
  role       = aws_iam_role.terraform_prod.name
  policy_arn = aws_iam_policy.terraform_state_access.arn
}

data "aws_iam_policy_document" "terraform_project_iam" {
  statement {
    actions = [
      "iam:AttachRolePolicy",
      "iam:CreatePolicy",
      "iam:CreateRole",
      "iam:DeletePolicy",
      "iam:DeleteRole",
      "iam:DeleteRolePolicy",
      "iam:DetachRolePolicy",
      "iam:GetPolicy",
      "iam:GetPolicyVersion",
      "iam:GetRole",
      "iam:GetRolePolicy",
      "iam:ListAttachedRolePolicies",
      "iam:ListPolicyVersions",
      "iam:ListRolePolicies",
      "iam:PassRole",
      "iam:PutRolePolicy",
      "iam:TagPolicy",
      "iam:TagRole",
      "iam:UntagPolicy",
      "iam:UntagRole",
      "iam:UpdateAssumeRolePolicy",
    ]
    resources = [
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:policy/peachtree-dispatch-*",
      "arn:aws:iam::${data.aws_caller_identity.current.account_id}:role/peachtree-dispatch-*",
    ]
  }
}

resource "aws_iam_policy" "terraform_project_iam" {
  name   = "peachtree-dispatch-project-iam"
  policy = data.aws_iam_policy_document.terraform_project_iam.json
}

resource "aws_iam_role_policy_attachment" "terraform_apply_project_iam" {
  role       = aws_iam_role.terraform_apply.name
  policy_arn = aws_iam_policy.terraform_project_iam.arn
}

resource "aws_iam_role_policy_attachment" "terraform_prod_project_iam" {
  role       = aws_iam_role.terraform_prod.name
  policy_arn = aws_iam_policy.terraform_project_iam.arn
}
