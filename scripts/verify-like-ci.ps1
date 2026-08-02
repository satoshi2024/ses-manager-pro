<#
.SYNOPSIS
  CIと同じ条件でテストを実行し、CIとの差分（skipされたテスト）を明示する（Windows向け）。

.DESCRIPTION
  .github/workflows/ci.yml と同じコマンド・同じ判定を行う。
  ここが緑なら push 後のCIも（環境差では）落ちない。

.EXAMPLE
  .\scripts\verify-like-ci.ps1
  .\scripts\verify-like-ci.ps1 -MavenArgs '-Dtest=DashboardServiceImplTest'
#>
param(
    [string[]]$MavenArgs = @()
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$mvn = 'mvn'
$bundled = Join-Path $repoRoot 'apache-maven-3.9.6\bin\mvn.cmd'
if (Test-Path $bundled) { $mvn = $bundled }

Write-Host '=== 前提ツールの確認（CIとの差分） ==='
& $mvn -v 2>$null | Select-Object -First 1
(& java -version 2>&1 | Select-Object -First 1)

$dockerOk = $false
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    $dockerOk = ($LASTEXITCODE -eq 0)
}
if ($dockerOk) {
    Write-Host 'Docker : あり  -> Flyway系のMySQL smoke（マイグレーション検証）が実行されます'
} else {
    Write-Host 'Docker : なし  -> Flyway*SmokeTest / FlywayRepairRunbookTest / ConcurrentUpdateTest はskipされます。'
    Write-Host '                 これらはMySQL方言・マイグレーション衝突を検出する唯一のテストで、CIでは必ず実行されます。'
    Write-Host '                 つまり今の環境で緑でも、CIで落ちる可能性が残ります。'
}

if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Host ('Node   : ' + (& node --version) + '  -> JS構文チェック(JsSyntaxCheckTest)が実行されます')
} else {
    Write-Host 'Node   : なし  -> JsSyntaxCheckTest はskipされます（CIでは必須）'
}
Write-Host ''

Write-Host '=== mvn -B clean test ==='
& $mvn -B clean test @MavenArgs
$testStatus = $LASTEXITCODE

Write-Host ''
Write-Host '=== skipされたテストの確認（CIと同じ判定） ==='
$skipped = @()
if (Test-Path 'target\surefire-reports') {
    $skipped = Get-ChildItem 'target\surefire-reports\*.xml' |
        Where-Object { (Select-String -Path $_.FullName -Pattern 'skipped="[1-9]' -Quiet) }
}

$skipStatus = 0
if ($skipped.Count -gt 0) {
    Write-Host ' 以下のテストがskipされました。CIはこの状態を失敗として扱います:'
    $skipped | ForEach-Object { Write-Host ('  ' + $_.Name) }
    if (-not $dockerOk) {
        Write-Host ''
        Write-Host 'Docker Desktop を起動してから再実行すると、CIと同じ範囲を検証できます。'
    }
    $skipStatus = 1
} else {
    Write-Host 'skipされたテストはありません（CIと同じ範囲を検証できています）'
}

if ($testStatus -ne 0) { exit $testStatus }
exit $skipStatus
