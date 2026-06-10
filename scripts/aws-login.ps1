param(
    [string]$Profile = "bootstrap"
)

$ErrorActionPreference = "Stop"
$awsDir = Join-Path $HOME ".aws"

New-Item -ItemType Directory -Force -Path $awsDir | Out-Null

docker run --rm -it `
    -v "${awsDir}:/root/.aws" `
    public.ecr.aws/aws-cli/aws-cli:latest `
    login --remote --profile $Profile --region us-east-1
