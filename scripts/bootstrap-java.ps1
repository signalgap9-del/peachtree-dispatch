param(
  [string]$MavenVersion = "3.9.16",
  [string]$JdkMajorVersion = "21"
)

$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$tools = Join-Path $root ".tools"
$jdkHome = Join-Path $tools "jdk-$JdkMajorVersion"
$mavenHome = Join-Path $tools "apache-maven-$MavenVersion"
$jdkZip = Join-Path $tools "temurin-jdk$JdkMajorVersion.zip"
$mavenZip = Join-Path $tools "apache-maven-$MavenVersion-bin.zip"

New-Item -ItemType Directory -Force -Path $tools | Out-Null

if (!(Test-Path $jdkZip)) {
  curl.exe -L "https://api.adoptium.net/v3/binary/latest/$JdkMajorVersion/ga/windows/x64/jdk/hotspot/normal/eclipse?project=jdk" -o $jdkZip
}

if (!(Test-Path $mavenZip)) {
  curl.exe -L "https://downloads.apache.org/maven/maven-3/$MavenVersion/binaries/apache-maven-$MavenVersion-bin.zip" -o $mavenZip
}

if (!(Test-Path $jdkHome)) {
  New-Item -ItemType Directory -Force -Path $jdkHome | Out-Null
  tar -xf $jdkZip -C $jdkHome --strip-components=1
}

if (!(Test-Path $mavenHome)) {
  tar -xf $mavenZip -C $tools
}

$env:JAVA_HOME = $jdkHome
$env:Path = "$jdkHome\bin;$mavenHome\bin;$env:Path"

java -version
mvn --version
