<#
.SYNOPSIS
  CIと同じ条件でテストを実行し、CIとの差分（skipされたテスト）を明示する（Windows向け）。

.DESCRIPTION
  .github/workflows/ci.yml と同じコマンド・同じ判定を行う。
  各テストスイートの証跡アーカイブ・厳格合取判定・状態管理を外置ディレクトリで永続化する。

.EXAMPLE
  .\scripts\verify-like-ci.ps1
  .\scripts\verify-like-ci.ps1 -MavenArgs '-Dtest=DashboardServiceImplTest'
#>
param(
    [string[]]$MavenArgs = @(),
    [string]$MavenExecutable = '',
    [switch]$PreflightOnly,
    [string]$EvidenceDir = '',
    [string[]]$ExpectedMysqlClasses = @()
)

$ErrorActionPreference = 'Stop'
$repoRoot = Split-Path -Parent $PSScriptRoot
Set-Location $repoRoot

. (Join-Path $PSScriptRoot 'verify-ci-common.ps1')

$mvn = 'mvn'
$bundled = Join-Path $repoRoot 'apache-maven-3.9.6\bin\mvn.cmd'
if (-not [string]::IsNullOrWhiteSpace($MavenExecutable)) {
    $mvn = $MavenExecutable
} elseif (Test-Path -LiteralPath $bundled) {
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
$prevEA = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
(& java -version 2>&1 | Select-Object -First 1)
$ErrorActionPreference = $prevEA

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

# ------------------------------------------------------------------------------
# 証跡ディレクトリ初期化と Git HEAD 検証
# ------------------------------------------------------------------------------
$startHeadSha = Get-StrictGitHead -RepoRoot $repoRoot

if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
    $timestamp = (Get-Date).ToString('yyyyMMdd-HHmmss')
    $shortHead = $startHeadSha.Substring(0, 8)
    $EvidenceDir = Join-Path $repoRoot "verification-evidence\gate-runs\$timestamp-$shortHead"
}

Assert-ValidEvidenceDir -RepoRoot $repoRoot -EvidenceDir $EvidenceDir

[System.IO.Directory]::CreateDirectory($EvidenceDir) | Out-Null

$sessionStateFile = Join-Path $EvidenceDir 'session-state.json'
$sessionData = [ordered]@{
    sessionId       = Split-Path -Leaf $EvidenceDir
    startHeadSha    = $startHeadSha
    startTime       = (Get-Date).ToString('o')
    state           = 'RUNNING'
    completed       = $false
    commandArgs     = $MavenArgs
    mavenExecutable = $mvn
    pid             = $PID
    suites          = @()
}
Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

# ------------------------------------------------------------------------------
# Maven スイート定義
# ------------------------------------------------------------------------------
$suites = @(
    @{ Id = 'fast-tests';        Name = 'fast tests (H2 / unit / MVC)'; Profile = '';                 ExpectedClasses = @() },
    @{ Id = 'mysql-tests';       Name = 'MySQL integration / Flyway';   Profile = 'mysql-tests';       ExpectedClasses = $ExpectedMysqlClasses },
    @{ Id = 'performance-tests'; Name = 'performance regression';        Profile = 'performance-tests'; ExpectedClasses = @() },
    @{ Id = 'browser-tests';     Name = 'browser demo (real Chrome, T093)'; Profile = 'browser-tests'; ExpectedClasses = @() }
)

foreach ($suite in $suites) {
    Write-Host ''
    Write-Host ('=== ' + $suite.Name + ' ===')

    $suiteDir = Join-Path $EvidenceDir $suite.Id
    if (-not (Test-Path -LiteralPath $suiteDir)) {
        [System.IO.Directory]::CreateDirectory($suiteDir) | Out-Null
    }
    $suiteLog = Join-Path $suiteDir "$($suite.Id).log"
    $suiteStateFile = Join-Path $suiteDir 'state.json'

    $suiteState = [ordered]@{
        suiteId   = $suite.Id
        name      = $suite.Name
        profile   = $suite.Profile
        state     = 'RUNNING'
        completed = $false
        startTime = (Get-Date).ToString('o')
        pid       = $PID
    }
    Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

    $suiteArgs = @('-B', 'clean', 'test')
    if (-not [string]::IsNullOrWhiteSpace($suite.Profile)) {
        $suiteArgs += ('-P' + $suite.Profile)
    }

    try {
        & $mvn @suiteArgs @MavenArgs 2>&1 | Tee-Object -FilePath $suiteLog
        $suiteStatus = $LASTEXITCODE
    } catch {
        $suiteStatus = 1
        Write-Error "Mavenプロセス実行中に例外が発生しました: $($_.Exception.Message)"
    }

    $suiteArtifactsDir = Join-Path $suiteDir 'artifacts'
    try {
        $archiveResult = Archive-DirectoryArtifacts -SourceDir (Join-Path $repoRoot 'target\surefire-reports') `
            -DestinationDir $suiteArtifactsDir `
            -Extensions @('.xml', '.txt', '.dump', '.dumpstream') `
            -FailOnEmpty $true

        if ($suite.Id -eq 'browser-tests') {
            $browserEvidence = Join-Path $repoRoot 'target\browser-m-evidence'
            if (Test-Path -LiteralPath $browserEvidence) {
                $browserDest = Join-Path $suiteArtifactsDir 'browser-m-evidence'
                Copy-Item -LiteralPath $browserEvidence -Destination $browserDest -Recurse -Force -ErrorAction Stop
            }
        }
    } catch {
        $suiteState.state = 'FAIL'
        $suiteState.completed = $true
        $suiteState['failureReasons'] = @("ARCHIVE_INTEGRITY_FAILURE: $($_.Exception.Message)")
        $suiteState['exitCode'] = $suiteStatus
        Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

        $sessionData.state = 'FAIL'
        $sessionData.completed = $true
        $sessionData['failureReason'] = "ARCHIVE_INTEGRITY_FAILURE in $($suite.Id)"
        Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

        Write-Error "CRITICAL: アーカイブ処理が失敗しました ($($_.Exception.Message))。証跡保全のため後続 clean は厳格に禁止されます。"
        exit 2
    }

    $eval = Evaluate-MavenSurefireArtifacts -SuiteId $suite.Id `
        -ProcessExitCode $suiteStatus `
        -ArtifactsDir $suiteArtifactsDir `
        -ExpectedClasses $suite.ExpectedClasses

    if (-not $eval.Pass) {
        $suiteState.state = 'FAIL'
        $suiteState.completed = $true
        $suiteState['failureReasons'] = $eval.Reasons
        $suiteState['tests'] = $eval.Tests
        $suiteState['failures'] = $eval.Failures
        $suiteState['errors'] = $eval.Errors
        $suiteState['skipped'] = $eval.Skipped
        $suiteState['dumpsCount'] = $eval.DumpsCount
        $suiteState['exitCode'] = $suiteStatus
        Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

        $sessionData.state = 'FAIL'
        $sessionData.completed = $true
        $sessionData['failureReason'] = "Suite $($suite.Id) failed conjunctive evaluation"
        Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

        Write-Host "Maven build/test failed or conjunctive pass criteria violated in $($suite.Name):"
        $eval.Reasons | ForEach-Object { Write-Error "  - $_" }
        exit 1
    }

    $suiteState.state = 'PASS'
    $suiteState.completed = $true
    $suiteState['tests'] = $eval.Tests
    $suiteState['failures'] = 0
    $suiteState['errors'] = 0
    $suiteState['skipped'] = 0
    $suiteState['dumpsCount'] = 0
    $suiteState['exitCode'] = 0
    Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

    Write-Host "Suite $($suite.Name) PASS (tests=$($eval.Tests), zero failures/errors/skips, zero dumps)"
}

# ------------------------------------------------------------------------------
# HFP-03: backup unit suite
# ------------------------------------------------------------------------------
Write-Host ''
Write-Host '=== HFP-03: backup unit suite ==='
$unitSuiteId = 'backup-unit'
$unitDir = Join-Path $EvidenceDir $unitSuiteId
if (-not (Test-Path -LiteralPath $unitDir)) { [System.IO.Directory]::CreateDirectory($unitDir) | Out-Null }
$unitLog = Join-Path $unitDir "$unitSuiteId.log"
$unitStateFile = Join-Path $unitDir 'state.json'

$unitState = [ordered]@{
    suiteId   = $unitSuiteId
    state     = 'RUNNING'
    completed = $false
    startTime = (Get-Date).ToString('o')
    pid       = $PID
}
Write-JsonStateFile -FilePath $unitStateFile -Data $unitState

try {
    & $bashExecutable ops/backup/tests/run-unit-tests.sh 2>&1 | Tee-Object -FilePath $unitLog
    $unitStatus = $LASTEXITCODE
} catch {
    $unitStatus = 1
    Write-Error "Bash unit suite 実行中に例外が発生しました: $($_.Exception.Message)"
}

$unitEval = Evaluate-BashUnitOutput -ProcessExitCode $unitStatus -LogPath $unitLog
if ($unitEval.Pass) {
    Write-Host 'backup unit suite: SUCCESS'
    $unitState.state = 'PASS'
    $unitState.completed = $true
    $unitState['exitCode'] = 0
    Write-JsonStateFile -FilePath $unitStateFile -Data $unitState
} else {
    Write-Host 'backup unit suite: FAIL'
    $unitState.state = 'FAIL'
    $unitState.completed = $true
    $unitState['failureReasons'] = $unitEval.Reasons
    $unitState['exitCode'] = $unitStatus
    Write-JsonStateFile -FilePath $unitStateFile -Data $unitState

    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = 'backup unit suite failed'
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

    $unitEval.Reasons | ForEach-Object { Write-Error "  - $_" }
    exit 1
}

# ------------------------------------------------------------------------------
# HFP-03-011: backup integration suite（実 MySQL PITR）
# ------------------------------------------------------------------------------
Write-Host ''
Write-Host '=== HFP-03-011: backup integration suite（実 MySQL PITR） ==='
$intSuiteId = 'backup-integration'
$intDir = Join-Path $EvidenceDir $intSuiteId
if (-not (Test-Path -LiteralPath $intDir)) { [System.IO.Directory]::CreateDirectory($intDir) | Out-Null }
$intLog = Join-Path $intDir "$intSuiteId.log"
$intStateFile = Join-Path $intDir 'state.json'

$uniqueIntWork = Join-Path $intDir 'raw-work'
$env:INTEGRATION_WORK_DIR = $uniqueIntWork

$intState = [ordered]@{
    suiteId            = $intSuiteId
    state              = 'RUNNING'
    completed          = $false
    startTime          = (Get-Date).ToString('o')
    integrationWorkDir = $uniqueIntWork
    pid                = $PID
}
Write-JsonStateFile -FilePath $intStateFile -Data $intState

try {
    & $bashExecutable ops/backup/tests/run-integration.sh 2>&1 | Tee-Object -FilePath $intLog
    $integrationStatus = $LASTEXITCODE
} catch {
    $integrationStatus = 1
    Write-Error "Backup integration suite 実行中に例外が発生しました: $($_.Exception.Message)"
}

$intArtifactsDir = Join-Path $intDir 'artifacts'
if (-not (Test-Path -LiteralPath $intArtifactsDir)) {
    [System.IO.Directory]::CreateDirectory($intArtifactsDir) | Out-Null
}

$srcEvidenceDir = Join-Path $uniqueIntWork 'evidence'
if (Test-Path -LiteralPath $srcEvidenceDir) {
    try {
        Get-ChildItem -LiteralPath $srcEvidenceDir -File -ErrorAction Stop | ForEach-Object {
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $intArtifactsDir $_.Name) -Force -ErrorAction Stop
        }
    } catch {
        Write-Error "統合証跡コピー中に例外が発生しました: $($_.Exception.Message)"
    }
}

if (Test-Path -LiteralPath $intLog) {
    Copy-Item -LiteralPath $intLog -Destination (Join-Path $intArtifactsDir 'runner-integration.log') -Force -ErrorAction SilentlyContinue
}

$intEval = Evaluate-BackupIntegrationEvidenceDir -ProcessExitCode $integrationStatus -EvidenceDir $intArtifactsDir
if ($intEval.Pass) {
    Write-Host 'integration suite: SUCCESS'
    $intState.state = 'PASS'
    $intState.completed = $true
    $intState['exitCode'] = 0
    Write-JsonStateFile -FilePath $intStateFile -Data $intState
} else {
    Write-Host 'integration suite: FAIL'
    $intState.state = 'FAIL'
    $intState.completed = $true
    $intState['failureReasons'] = $intEval.Reasons
    $intState['exitCode'] = $integrationStatus
    Write-JsonStateFile -FilePath $intStateFile -Data $intState

    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = 'backup integration suite failed'
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

    $intEval.Reasons | ForEach-Object { Write-Error "  - $_" }
    exit 1
}

# ------------------------------------------------------------------------------
# 終了時 Git HEAD SHA 整合性検証
# ------------------------------------------------------------------------------
$endHeadSha = Get-StrictGitHead -RepoRoot $repoRoot
if ($endHeadSha -ne $startHeadSha) {
    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = "HEAD_CHANGED_DURING_EXECUTION (start=$startHeadSha, end=$endHeadSha)"
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Error "FATAL: 実行中に Git HEAD SHA が変化しました ($startHeadSha -> $endHeadSha)。門禁は拒絶されます。"
    exit 1
}

$sessionData.state = 'PASS'
$sessionData.completed = $true
$sessionData['endHeadSha'] = $endHeadSha
$sessionData['endTime'] = (Get-Date).ToString('o')
Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

Write-Host ''
Write-Host "=== 全CIゲート合取判定PASS: 証跡保管先 -> $EvidenceDir ==="
exit 0
