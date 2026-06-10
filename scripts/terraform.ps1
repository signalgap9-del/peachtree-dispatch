$ErrorActionPreference = "Stop"
$projectDir = (Get-Location).Path
$awsDir = Join-Path $HOME ".aws"
$profile = if ($env:AWS_PROFILE) { $env:AWS_PROFILE } else { "bootstrap" }
$terraformArgs = $args

if ($terraformArgs.Count -eq 0) {
    throw "Pass a Terraform command, for example: ./scripts/terraform.ps1 validate"
}

$credentialJson = docker run --rm `
    -v "${awsDir}:/root/.aws" `
    public.ecr.aws/aws-cli/aws-cli:latest `
    configure export-credentials --profile $profile --format process

if ($LASTEXITCODE -ne 0) {
    throw "Unable to export temporary AWS credentials for Terraform."
}

$credentials = $credentialJson | ConvertFrom-Json

docker run --rm -i `
    -v "${projectDir}:/workspace" `
    -v "${awsDir}:/root/.aws" `
    -w /workspace `
    -e "AWS_PROFILE=$profile" `
    -e "AWS_REGION=us-east-1" `
    -e "AWS_ACCESS_KEY_ID=$($credentials.AccessKeyId)" `
    -e "AWS_SECRET_ACCESS_KEY=$($credentials.SecretAccessKey)" `
    -e "AWS_SESSION_TOKEN=$($credentials.SessionToken)" `
    hashicorp/terraform:1.13.5 @TerraformArgs

exit $LASTEXITCODE
