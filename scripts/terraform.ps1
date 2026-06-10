param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$TerraformArgs
)

$ErrorActionPreference = "Stop"
$projectDir = (Get-Location).Path
$awsDir = Join-Path $HOME ".aws"
$profile = if ($env:AWS_PROFILE) { $env:AWS_PROFILE } else { "bootstrap" }

docker run --rm -i `
    -v "${projectDir}:/workspace" `
    -v "${awsDir}:/root/.aws" `
    -w /workspace `
    -e "AWS_PROFILE=$profile" `
    -e "AWS_REGION=us-east-1" `
    hashicorp/terraform:1.13.5 @TerraformArgs

exit $LASTEXITCODE
