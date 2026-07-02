param(
  [Parameter(ValueFromRemainingArguments = $true)]
  [string[]]$MavenArgs
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$jdkHome = Join-Path $root ".tools\jdk-21"
$mavenHome = Join-Path $root ".tools\apache-maven-3.9.16"

if (!(Test-Path $jdkHome) -or !(Test-Path $mavenHome)) {
  & (Join-Path $PSScriptRoot "bootstrap-java.ps1")
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$mavenHome\bin;$env:Path"

& (Join-Path $mavenHome "bin\mvn.cmd") @MavenArgs
