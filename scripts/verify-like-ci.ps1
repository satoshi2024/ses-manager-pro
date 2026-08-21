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
    [string[]]$MavenArgs = @(),
    [string]$MavenExecutable = '',
    [switch]$PreflightOnly
)

$ErrorActionPreference = 'Continue'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

$mvn = 'mvn'
$bundled = Join-Path $repoRoot 'apache-maven-3.9.6\bin\mvn.cmd'
if (-not [string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $mvn = $MavenExecutable
} elseif (Test-Path $bundled) {
    $mvn = $bundled
}

$bashExecutable = ''
$bashCandidates = @(
    'C:\Program Files\Git\bin\bash.exe',
    'C:\Program Files\Git\usr\bin\bash.exe'
)
$bashCommand = Get-Command bash -ErrorAction SilentlyContinue
if ($null -ne $bashCommand) {
    $bashCandidates += $bashCommand.Source
}
foreach ($candidate in $bashCandidates) {
    if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path -LiteralPath $candidate)) {
        $bashExecutable = $candidate
        break
    }
}

Write-Host '=== 前提ツールの確認（CIとの差分） ==='
& $mvn -v 2>$null | Select-Object -First 1
(& java -version 2>&1 | Select-Object -First 1)

$dockerOk = $false
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    $dockerOk = ($LASTEXITCODE -eq 0)
}
if ($dockerOk) {
    Write-Host 'Docker : あり  -> mysql-tests profileを実行できます'
} else {
    Write-Host 'Docker : なし  -> CI full suiteは実行できません'
}

if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Host ('Node   : ' + (& node --version) + '  -> JS構文チェック(JsSyntaxCheckTest)が実行されます')
} else {
    Write-Host 'Node   : なし  -> CI fast suiteは実行できません'
}
$chromeOk = $false
$chromeCandidates = @(
    $env:CHROME_BIN,
    'C:\Program Files\Google\Chrome\Application\chrome.exe',
    'C:\Program Files (x86)\Google\Chrome\Application\chrome.exe',
    '/usr/bin/google-chrome',
    '/usr/bin/google-chrome-stable',
    '/usr/bin/chromium',
    '/usr/bin/chromium-browser',
    '/snap/bin/chromium'
) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
foreach ($candidate in $chromeCandidates) {
    if (Test-Path -LiteralPath $candidate) {
        $chromeOk = $true
        Write-Host ('Chrome : ' + $candidate + '  -> browser demo (T093) gateが実行できます')
        break
    }
}
if (-not $chromeOk) {
    Write-Host 'Chrome : なし  -> browser demo (T093) gateは実行できません'
}
if (-not [string]::IsNullOrWhiteSpace($bashExecutable)) {
    Write-Host ('Bash   : ' + $bashExecutable + '  -> backup integration suiteを実行できます')
} else {
    Write-Host 'Bash   : なし  -> backup integration suiteを実行できません'
}
Write-Host ''

if ($PreflightOnly) {
    Write-Host 'PreflightOnly: 前提ツール確認まで正常に完了しました。'
    exit 0
}

if (-not $dockerOk) {
    Write-Error 'CI full suiteにはDockerが必須です。Docker Desktopを起動して再実行してください。'
    exit 1
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error 'CI fast suiteにはNode.jsが必須です。Node.jsをPATHへ追加して再実行してください。'
    exit 1
}
if (-not $chromeOk) {
    Write-Error 'CI browser demo gateにはChromeが必須です。CHROME_BIN環境変数で実行ファイルを指定してください。'
    exit 1
}
if ([string]::IsNullOrWhiteSpace($bashExecutable)) {
    Write-Error 'CI backup integration gateにはBash（Git Bash可）が必須です。'
    exit 1
}

$suites = @(
    @{ Name = 'fast tests (H2 / unit / MVC)'; Profile = '' },
    @{ Name = 'MySQL integration / Flyway'; Profile = 'mysql-tests' },
    @{ Name = 'performance regression'; Profile = 'performance-tests' },
    @{ Name = 'browser demo (real Chrome, T093)'; Profile = 'browser-tests' }
)

foreach ($suite in $suites) {
    Write-Host ''
    Write-Host ('=== ' + $suite.Name + ' ===')
    $suiteArgs = @('-B', 'clean', 'test')
    if (-not [string]::IsNullOrWhiteSpace($suite.Profile)) {
        $suiteArgs += ('-P' + $suite.Profile)
    }
    & $mvn @suiteArgs @MavenArgs
    $suiteStatus = $LASTEXITCODE
    if ($suiteStatus -ne 0) {
        # 長いworkspace pathでもPowerShellの自動改行で固定識別子が分断されないよう単独出力する。
        Write-Host 'Maven build/test failed'
        Write-Error ($suite.Name + " failed (exit=$suiteStatus).")
        exit $suiteStatus
    }

    $skipped = @()
    if (Test-Path 'target\surefire-reports') {
        $skipped = Get-ChildItem 'target\surefire-reports\*.xml' |
            Where-Object { (Select-String -Path $_.FullName -Pattern 'skipped="[1-9]' -Quiet) }
    }
    if ($skipped.Count -gt 0) {
        Write-Host '以下のテストがskipされました。CIはこの状態を失敗として扱います:'
        $skipped | ForEach-Object { Write-Host ('  ' + $_.Name) }
        exit 1
    }
    Write-Host 'skipされたテストはありません'
}

Write-Host ''
Write-Host '=== HFP-03: backup unit suite ==='
$unitStatus = 1
& $bashExecutable ops/backup/tests/run-unit-tests.sh
if ($LASTEXITCODE -eq 0) {
    Write-Host 'backup unit suite: SUCCESS'
    $unitStatus = 0
} else {
    Write-Host 'backup unit suite: FAIL（CI と同じ判定で失敗扱い）'
}
if ($unitStatus -ne 0) { exit 1 }

Write-Host ''
Write-Host '=== HFP-03-011: backup integration suite（実 MySQL PITR） ==='
$integrationStatus = 1
if ($dockerOk) {
    Write-Host 'Docker あり -> integration suite を実行します（数分かかります）'
    & $bashExecutable ops/backup/tests/run-integration.sh
    if ($LASTEXITCODE -eq 0) {
        Write-Host 'integration suite: SUCCESS'
        $integrationStatus = 0
    } else {
        Write-Host 'integration suite: FAIL（CI と同じ判定で失敗扱い）'
    }
} else {
    Write-Host 'Docker なし -> integration suite は実行できません（CI では必須・失敗扱い）'
}

if ($integrationStatus -ne 0) { exit 1 }
exit 0
