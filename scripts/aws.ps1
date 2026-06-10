param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$AwsArgs
)

$ErrorActionPreference = "Stop"
$awsDir = Join-Path $HOME ".aws"
$projectDir = (Get-Location).Path
$profile = if ($env:AWS_PROFILE) { $env:AWS_PROFILE } else { "bootstrap" }
$region = if ($env:AWS_REGION) { $env:AWS_REGION } else { "us-east-1" }

New-Item -ItemType Directory -Force -Path $awsDir | Out-Null

docker run --rm -i `
    -v "${awsDir}:/root/.aws" `
    -v "${projectDir}:/workspace" `
    -w /workspace `
    -e "AWS_PROFILE=$profile" `
    -e "AWS_REGION=$region" `
    -e "AWS_DEFAULT_REGION=$region" `
    public.ecr.aws/aws-cli/aws-cli:latest @AwsArgs

exit $LASTEXITCODE
