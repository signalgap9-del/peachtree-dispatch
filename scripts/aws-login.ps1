param(
    [string]$Profile = "bootstrap",
    [switch]$BreakGlass
)

$ErrorActionPreference = "Stop"

if (-not $BreakGlass) {
    throw "Routine deployments use GitHub Actions OIDC and require no local AWS login. Pass -BreakGlass only for an explicitly approved emergency session."
}

$awsDir = Join-Path $HOME ".aws"

New-Item -ItemType Directory -Force -Path $awsDir | Out-Null

docker run --rm -it `
    -v "${awsDir}:/root/.aws" `
    public.ecr.aws/aws-cli/aws-cli:latest `
    login --remote --profile $Profile --region us-east-1
