<#
.SYNOPSIS
  CIと同じ条件でテストを実行し、CIとの差分（skipされたテスト）を明示する（Windows向け）.

.DESCRIPTION
  .github/workflows/ci.yml と同じコマンド・同じ判定を行う.
  各テストスイートの証跡アーカイブ・厳格合取判定・状態管理を外置ディレクトリで永続化する.

.EXAMPLE
  .\scripts\verify-like-ci.ps1
  .\scripts\verify-like-ci.ps1 -PreflightOnly
#>
param(
    [string[]]$MavenArgs = @(),
    [string]$MavenExecutable = '',
    [string]$BashExecutable = '',
    [switch]$PreflightOnly,
    [string]$EvidenceDir = '',
    [string]$CustomPitrWorkDir = '',
    [switch]$TargetedRun
)

Set-StrictMode -Version Latest
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

$bashExec = ''
if (-not [string]::IsNullOrWhiteSpace($BashExecutable)) {
    $bashExec = $BashExecutable
} else {
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
            $bashExec = $candidate
            break
        }
    }
}

Write-Host '=== 前提ツールの確認（CIとの差分） ===.'
& $mvn -v 2>$null | Select-Object -First 1
if (Get-Command java -ErrorAction SilentlyContinue) {
    $tempJavaFile = [System.IO.Path]::GetTempFileName()
    try {
        $jProc = Start-Process -FilePath "java" -ArgumentList "-version" -NoNewWindow -Wait -PassThru -RedirectStandardError $tempJavaFile
        if (Test-Path -LiteralPath $tempJavaFile) {
            Get-Content -LiteralPath $tempJavaFile | Select-Object -First 1
        }
    } finally {
        Remove-Item -LiteralPath $tempJavaFile -Force -ErrorAction SilentlyContinue
    }
}

$dockerOk = $false
if (Get-Command docker -ErrorAction SilentlyContinue) {
    & docker info *> $null
    $dockerOk = ($LASTEXITCODE -eq 0)
}
if ($dockerOk) {
    Write-Host 'Docker : あり  -> mysql-tests profileを実行できます.'
} else {
    Write-Host 'Docker : なし  -> CI full suiteは実行できません.'
}

if (Get-Command node -ErrorAction SilentlyContinue) {
    Write-Host ('Node   : ' + (& node --version) + '  -> JS構文チェック(JsSyntaxCheckTest)が実行されます.')
} else {
    Write-Host 'Node   : なし  -> CI fast suiteは実行できません.'
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
        Write-Host ('Chrome : ' + $candidate + '  -> browser demo (T093) gateが実行できます.')
        break
    }
}
if (-not $chromeOk) {
    Write-Host 'Chrome : なし  -> browser demo (T093) gateは実行できません.'
}

if (-not [string]::IsNullOrWhiteSpace($bashExec)) {
    Write-Host ('Bash   : ' + $bashExec + '  -> backup integration suiteを実行できます.')
} else {
    Write-Host 'Bash   : なし  -> backup integration suiteを実行できません.'
}
Write-Host ''

if ($PreflightOnly) {
    Write-Host 'PreflightOnly: 前提ツール確認まで正常に完了しました.'
    exit 0
}

# ------------------------------------------------------------------------------
# 1. 引数と作業ツリーの厳格事前検証.
# ------------------------------------------------------------------------------
if (-not $TargetedRun) {
    Assert-ValidMavenArgs -ArgsList $MavenArgs
} else {
    Write-Host '[WARN] TargetedRun が有効化されています。この実行は FULL_GATE として扱われません.'
}

Assert-CleanWorkingTree -RepoRoot $repoRoot -EvidenceDir $EvidenceDir

if (-not $dockerOk) {
    Write-Error 'CI full suiteにはDockerが必須です。Docker Desktopを起動して再実行してください.'
    exit 1
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Error 'CI fast suiteにはNode.jsが必須です。Node.jsをPATHへ追加して再実行してください.'
    exit 1
}
if (-not $chromeOk) {
    Write-Error 'CI browser demo gateにはChromeが必須です。CHROME_BIN環境変数で実行ファイルを指定してください.'
    exit 1
}
if ([string]::IsNullOrWhiteSpace($bashExec)) {
    Write-Error 'CI backup integration gateにはBash（Git Bash可）が必須です.'
    exit 1
}

# ------------------------------------------------------------------------------
# 2. 証跡ディレクトリ初期化と Git HEAD 検証.
# ------------------------------------------------------------------------------
$startHeadSha = Get-StrictGitHead -RepoRoot $repoRoot
$startStatus = (& git -C $repoRoot status --porcelain 2>$null) -join "; "

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
    gateType        = if ($TargetedRun) { 'TARGETED_NON_FULL_GATE' } else { 'FULL_GATE' }
    startHeadSha    = $startHeadSha
    startStatus     = $startStatus
    startTime       = (Get-Date).ToString('o')
    state           = 'RUNNING'
    completed       = $false
    commandArgs     = $MavenArgs
    mavenExecutable = $mvn
    bashExecutable  = $bashExec
    pid             = $PID
    suites          = @()
}
Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

# ------------------------------------------------------------------------------
# 3. 初回 clean 前の既存 target 保全.
# ------------------------------------------------------------------------------
try {
    $preservedTarget = Preserve-InitialTarget -RepoRoot $repoRoot -EvidenceDir $EvidenceDir
    if ($preservedTarget.Preserved) {
        Write-Host "既存 target ディレクトリの保全完了 (件数: $($preservedTarget.Count))."
    }
} catch {
    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = "PRE_CLEAN_TARGET_PRESERVATION_FAILED: $($_.Exception.Message)"
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Error "CRITICAL: 初回 clean 前の target 保全に失敗しました。clean を中止します: $($_.Exception.Message)."
    exit 2
}

# ------------------------------------------------------------------------------
# 4. MySQL 必須実行集合の固定取得 (shard 1, 2, 3 の全量87クラス)
# ------------------------------------------------------------------------------
$mysqlExpectedClasses = Get-RequiredMysqlClasses -RepoRoot $repoRoot

# ------------------------------------------------------------------------------
# 5. Maven スイート定義と実行ループ.
# ------------------------------------------------------------------------------
$suites = @(
    @{ Id = 'fast-tests';        Name = 'fast tests (H2 / unit / MVC)'; Profile = '';                 ExpectedClasses = @() },
    @{ Id = 'mysql-tests';       Name = 'MySQL integration / Flyway';   Profile = 'mysql-tests';       ExpectedClasses = $mysqlExpectedClasses },
    @{ Id = 'performance-tests'; Name = 'performance regression';        Profile = 'performance-tests'; ExpectedClasses = @() },
    @{ Id = 'browser-tests';     Name = 'browser demo (real Chrome, T093)'; Profile = 'browser-tests'; ExpectedClasses = @() }
)

foreach ($suite in $suites) {
    Write-Host ''
    Write-Host ('=== ' + $suite.Name + ' ===.')

    $suiteDir = Join-Path $EvidenceDir $suite.Id
    if (-not (Test-Path -LiteralPath $suiteDir)) {
        [System.IO.Directory]::CreateDirectory($suiteDir) | Out-Null
    }
    $suiteLog = Join-Path $suiteDir "$($suite.Id).log"
    $suiteStateFile = Join-Path $suiteDir 'state.json'
    $suiteStartTime = (Get-Date).ToString('o')

    $suiteState = [ordered]@{
        suiteId   = $suite.Id
        name      = $suite.Name
        profile   = $suite.Profile
        state     = 'RUNNING'
        completed = $false
        startTime = $suiteStartTime
        pid       = $PID
    }
    Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

    $suiteArgs = @('-B', 'clean', 'test')
    if (-not [string]::IsNullOrWhiteSpace($suite.Profile)) {
        $suiteArgs += ('-P' + $suite.Profile)
    }

    $suiteStatus = 0
    try {
        & $mvn @suiteArgs @MavenArgs 2>&1 | Tee-Object -FilePath $suiteLog
        $suiteStatus = $LASTEXITCODE
    } catch {
        $suiteStatus = 1
        Write-Error "Mavenプロセス実行中に例外が発生しました: $($_.Exception.Message)."
    }

    # 統一終結処理: 例外時も必ずアーカイブを試みる.
    $suiteArtifactsDir = Join-Path $suiteDir 'artifacts'
    try {
        $archiveParams = @{
            SourceDir      = (Join-Path $repoRoot 'target\surefire-reports')
            DestinationDir = $suiteArtifactsDir
            Extensions     = @('.xml', '.txt', '.dump', '.dumpstream')
            FailOnEmpty    = $true
        }
        $archiveResult = Archive-DirectoryArtifacts @archiveParams

        # JaCoCo レポートが存在する場合は保全.
        $jacocoDir = Join-Path $repoRoot 'target\site\jacoco'
        if (Test-Path -LiteralPath $jacocoDir) {
            $jacocoDest = Join-Path $suiteArtifactsDir 'jacoco'
            Copy-Item -LiteralPath $jacocoDir -Destination $jacocoDest -Recurse -Force -ErrorAction Stop
        }

        # browser-m-evidence の保全.
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
        $suiteState['endTime'] = (Get-Date).ToString('o')
        Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

        $sessionData.state = 'FAIL'
        $sessionData.completed = $true
        $sessionData['failureReason'] = "ARCHIVE_INTEGRITY_FAILURE in $($suite.Id)"
        $sessionData['endTime'] = (Get-Date).ToString('o')
        Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

        Write-Error "CRITICAL: アーカイブ処理が失敗しました ($($_.Exception.Message))。証跡保全のため後続 clean は厳格に禁止されます."
        exit 2
    }

    $evalParams = @{
        SuiteId         = $suite.Id
        ProcessExitCode = $suiteStatus
        ArtifactsDir    = $suiteArtifactsDir
        ExpectedClasses = $suite.ExpectedClasses
    }
    $eval = Evaluate-MavenSurefireArtifacts @evalParams

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
        $suiteState['endTime'] = (Get-Date).ToString('o')
        Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

        $sessionData.state = 'FAIL'
        $sessionData.completed = $true
        $sessionData['failureReason'] = "Suite $($suite.Id) failed conjunctive evaluation"
        $sessionData['endTime'] = (Get-Date).ToString('o')
        Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

        Write-Host "Maven build/test failed or conjunctive pass criteria violated in $($suite.Name):."
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
    $suiteState['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $suiteStateFile -Data $suiteState

    Write-Host "Suite $($suite.Name) PASS (tests=$($eval.Tests), zero failures/errors/skips, zero dumps)."
}

# ------------------------------------------------------------------------------
# 6. HFP-03: backup unit suite
# ------------------------------------------------------------------------------
Write-Host ''
Write-Host '=== HFP-03: backup unit suite ===.'
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

$unitStatus = 0
try {
    & $bashExec ops/backup/tests/run-unit-tests.sh 2>&1 | Tee-Object -FilePath $unitLog
    $unitStatus = $LASTEXITCODE
} catch {
    $unitStatus = 1
    Write-Error "Bash unit suite 実行中に例外が発生しました: $($_.Exception.Message)."
}

$unitEval = Evaluate-BashUnitOutput -ProcessExitCode $unitStatus -LogPath $unitLog
if ($unitEval.Pass) {
    Write-Host 'backup unit suite: SUCCESS.'
    $unitState.state = 'PASS'
    $unitState.completed = $true
    $unitState['exitCode'] = 0
    $unitState['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $unitStateFile -Data $unitState
} else {
    Write-Host 'backup unit suite: FAIL.'
    $unitState.state = 'FAIL'
    $unitState.completed = $true
    $unitState['failureReasons'] = $unitEval.Reasons
    $unitState['exitCode'] = $unitStatus
    $unitState['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $unitStateFile -Data $unitState

    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = 'backup unit suite failed'
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

    $unitEval.Reasons | ForEach-Object { Write-Error "  - $_" }
    exit 1
}

# ------------------------------------------------------------------------------
# 7. HFP-03-011: backup integration suite（実 MySQL PITR）.
# ------------------------------------------------------------------------------
Write-Host ''
Write-Host '=== HFP-03-011: backup integration suite（実 MySQL PITR） ===.'
$intSuiteId = 'backup-integration'
$intDir = Join-Path $EvidenceDir $intSuiteId
if (-not (Test-Path -LiteralPath $intDir)) { [System.IO.Directory]::CreateDirectory($intDir) | Out-Null }
$intLog = Join-Path $intDir "$intSuiteId.log"
$intStateFile = Join-Path $intDir 'state.json'

# EvidenceDir 外の完全独立な私有作業ディレクトリを決定.
$privateWork = ''
if (-not [string]::IsNullOrWhiteSpace($CustomPitrWorkDir)) {
    $privateWork = $CustomPitrWorkDir
} else {
    $rndSuffix = [System.Guid]::NewGuid().ToString('N').Substring(0, 8)
    $privateWork = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-pitr-work-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + "-$rndSuffix")
}
$privateWorkFull = [System.IO.Path]::GetFullPath($privateWork)
$repoFull = [System.IO.Path]::GetFullPath($repoRoot)
$evidenceFull = [System.IO.Path]::GetFullPath($EvidenceDir)

if ($privateWorkFull.StartsWith($repoFull, [System.StringComparison]::OrdinalIgnoreCase) -or $privateWorkFull.StartsWith($evidenceFull, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "FATAL: PITR私有作業ディレクトリはリポジトリおよび EvidenceDir の外部でなければなりません: '$privateWorkFull'."
}

$env:INTEGRATION_WORK_DIR = $privateWorkFull

$intState = [ordered]@{
    suiteId            = $intSuiteId
    state              = 'RUNNING'
    completed          = $false
    startTime          = (Get-Date).ToString('o')
    integrationWorkDir = $privateWorkFull
    pid                = $PID
}
Write-JsonStateFile -FilePath $intStateFile -Data $intState

$integrationStatus = 0
try {
    & $bashExec ops/backup/tests/run-integration.sh 2>&1 | Tee-Object -FilePath $intLog
    $integrationStatus = $LASTEXITCODE
} catch {
    $integrationStatus = 1
    Write-Error "Backup integration suite 実行中に例外が発生しました: $($_.Exception.Message)."
}

$intArtifactsDir = Join-Path $intDir 'artifacts'
if (-not (Test-Path -LiteralPath $intArtifactsDir)) {
    [System.IO.Directory]::CreateDirectory($intArtifactsDir) | Out-Null
}

$srcEvidenceDir = Join-Path $privateWorkFull 'evidence'
if (Test-Path -LiteralPath $srcEvidenceDir) {
    try {
        Get-ChildItem -LiteralPath $srcEvidenceDir -File -ErrorAction Stop | ForEach-Object {
            $destItem = Join-Path $intArtifactsDir $_.Name
            Copy-Item -LiteralPath $_.FullName -Destination $destItem -Force -ErrorAction Stop
            $srcH = Get-Sha256Hex -FilePath $_.FullName
            $dstH = Get-Sha256Hex -FilePath $destItem
            if ($srcH -ne $dstH) {
                throw "FATAL: PITR証跡コピー時のハッシュ不一致: '$($_.Name)'."
            }
        }
    } catch {
        Write-Error "統合証跡コピー中に例外が発生しました: $($_.Exception.Message)."
    }
}

# runner ログを証跡ディレクトリへコピー (失敗時は例外スロー)
if (Test-Path -LiteralPath $intLog) {
    $runnerLogDest = Join-Path $intArtifactsDir 'runner-integration.log'
    Copy-Item -LiteralPath $intLog -Destination $runnerLogDest -Force -ErrorAction Stop
    $srcLogHash = Get-Sha256Hex -FilePath $intLog
    $dstLogHash = Get-Sha256Hex -FilePath $runnerLogDest
    if ($srcLogHash -ne $dstLogHash) {
        throw "FATAL: runner-integration.log コピー時のハッシュ不一致."
    }
} else {
    throw "FATAL: backup-integration の runner ログが存在しません: '$intLog'."
}

# 全アーカイブファイルの sha256sum.txt を追加作成.
$allIntFiles = @(Get-ChildItem -LiteralPath $intArtifactsDir -File -ErrorAction Stop | Where-Object { $_.Name -ne 'sha256sum.txt' })
$fullShaLines = @()
foreach ($f in ($allIntFiles | Sort-Object -Property Name)) {
    $h = Get-Sha256Hex -FilePath $f.FullName
    $fullShaLines += "$h  $($f.Name)"
}
[System.IO.File]::WriteAllLines((Join-Path $intArtifactsDir 'sha256sum.txt'), $fullShaLines, $script:Utf8NoBom)

$intEval = Evaluate-BackupIntegrationEvidenceDir -ProcessExitCode $integrationStatus -EvidenceDir $intArtifactsDir
if ($intEval.Pass) {
    Write-Host 'integration suite: SUCCESS.'
    $intState.state = 'PASS'
    $intState.completed = $true
    $intState['exitCode'] = 0
    $intState['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $intStateFile -Data $intState
} else {
    Write-Host 'integration suite: FAIL.'
    $intState.state = 'FAIL'
    $intState.completed = $true
    $intState['failureReasons'] = $intEval.Reasons
    $intState['exitCode'] = $integrationStatus
    $intState['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $intStateFile -Data $intState

    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = 'backup integration suite failed'
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

    $intEval.Reasons | ForEach-Object { Write-Error "  - $_" }
    exit 1
}

# ------------------------------------------------------------------------------
# 8. 交付ツリーの秘密情報スキャン.
# ------------------------------------------------------------------------------
try {
    Scan-EvidenceDirectoryForSecrets -EvidenceDir $EvidenceDir
} catch {
    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = "SECRET_LEAK_IN_DELIVERY_TREE: $($_.Exception.Message)"
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Error $_.Exception.Message
    exit 1
}

# ------------------------------------------------------------------------------
# 9. 終了時 Git HEAD SHA & 作業ツリー整合性検証.
# ------------------------------------------------------------------------------
$endHeadSha = Get-StrictGitHead -RepoRoot $repoRoot
if ($endHeadSha -ne $startHeadSha) {
    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = "HEAD_CHANGED_DURING_EXECUTION (start=$startHeadSha, end=$endHeadSha)"
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Error "FATAL: 実行中に Git HEAD SHA が変化しました ($startHeadSha -> $endHeadSha)。門禁は拒絶されます."
    exit 1
}

try {
    Assert-CleanWorkingTree -RepoRoot $repoRoot -EvidenceDir $EvidenceDir
} catch {
    $sessionData.state = 'FAIL'
    $sessionData.completed = $true
    $sessionData['failureReason'] = "WORKING_TREE_MUTATED_DURING_EXECUTION: $($_.Exception.Message)"
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Error "FATAL: 実行中に作業ツリーのソース・設定が変更されました: $($_.Exception.Message)."
    exit 1
}

# ------------------------------------------------------------------------------
# 10. 最終合取判定記録.
# ------------------------------------------------------------------------------
if ($TargetedRun) {
    $sessionData.state = 'TARGETED_PASS_NOT_FULL_GATE'
    $sessionData.completed = $true
    $sessionData['endHeadSha'] = $endHeadSha
    $sessionData['endTime'] = (Get-Date).ToString('o')
    Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData
    Write-Host ''
    Write-Host "=== TARGETED RUN PASS (非FULL_GATE): 証跡保管先 -> $EvidenceDir ==="
    exit 0
}

$sessionData.state = 'PASS'
$sessionData.completed = $true
$sessionData['endHeadSha'] = $endHeadSha
$sessionData['endTime'] = (Get-Date).ToString('o')
Write-JsonStateFile -FilePath $sessionStateFile -Data $sessionData

Write-Host ''
Write-Host "=== 全CIゲート合取判定PASS: 証跡保管先 -> $EvidenceDir ==="
exit 0
