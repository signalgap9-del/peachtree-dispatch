$ErrorActionPreference = "Stop"
$projectDir = (Get-Location).Path
$terraformArgs = $args

if ($terraformArgs.Count -eq 0) {
    throw "Pass a Terraform command, for example: ./scripts/terraform.ps1 validate"
}

$isOfflineInit = $terraformArgs[0] -eq "init" -and $terraformArgs -contains "-backend=false"
if ($terraformArgs[0] -notin @("fmt", "validate", "version") -and -not $isOfflineInit) {
    throw "AWS-changing Terraform commands run through GitHub Actions OIDC. Local login is not required or supported by this wrapper."
}

docker run --rm -i `
    -v "${projectDir}:/workspace" `
    -w /workspace `
    hashicorp/terraform:1.13.5 @TerraformArgs

exit $LASTEXITCODE
