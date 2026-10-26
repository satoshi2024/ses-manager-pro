<#
.SYNOPSIS
  verify-like-ci.ps1 の合取判定・状態管理・証跡保全・異常系ハンドリングの完全隔離検証ハーネス (UTF-8 without BOM).

.DESCRIPTION
  実Maven・Docker・MySQL・Chrome・PITRバックアップを一切起動せず、.
  独立テンポラリ fixture リポジトリ内で runner (verify-like-ci.ps1) を実際に呼び出し、.
  全異常系および正常系シナリオを高速・決定論的に検証する.
  実作業ツリー (ses-snf-final-consolidation-gate) の src/target/証跡への書き込み・削除は一切行わない.
  断言内容: 終了コード、JSON状態、証跡保全、後続 clean 呼び出し抑止、実作業ツリー不変.
  検証結果は可読なサマリーおよび証跡ディレクトリとして永続化・保持される.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding $false

$scriptDir = $PSScriptRoot
$realRepoRoot = Split-Path -Parent $scriptDir
$commonScript = Join-Path $scriptDir 'verify-ci-common.ps1'
$runnerScript = Join-Path $scriptDir 'verify-like-ci.ps1'

if (-not (Test-Path -LiteralPath $commonScript)) {
    throw "FATAL: verify-ci-common.ps1 が見つかりません: '$commonScript'."
}
if (-not (Test-Path -LiteralPath $runnerScript)) {
    throw "FATAL: verify-like-ci.ps1 が見つかりません: '$runnerScript'."
}

$commonContent = [System.IO.File]::ReadAllText($commonScript, $script:Utf8NoBom)
. ([ScriptBlock]::Create($commonContent))

function Invoke-FixtureGit {
    param([string[]]$GitArgs)
    $prev = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $output = @(& git $GitArgs 2>&1)
        return $output
    } finally {
        $ErrorActionPreference = $prev
    }
}

# 実作業ツリーの初期状態を記録 (テスト終了時に不変であることを断言).
$realStartHeadOutput = Invoke-FixtureGit -GitArgs @('-C', $realRepoRoot, 'rev-parse', 'HEAD')
$realStartHead = $realStartHeadOutput -join "`n"
$realStartStatus = @(Invoke-FixtureGit -GitArgs @('-C', $realRepoRoot, 'status', '--porcelain'))

$currentPsExe = (Get-Process -Id $PID).Path
$testBaseDir = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-runner-tests-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
[System.IO.Directory]::CreateDirectory($testBaseDir) | Out-Null

function Safe-RemoveTempItem {
    param([string]$Path)
    if ([string]::IsNullOrWhiteSpace($Path)) { return }
    if (Test-IsSubpathOrSame -ChildPath $Path -ParentPath $testBaseDir) {
        Remove-Item -LiteralPath $Path -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        throw "SAFETY VIOLATION: 一時テスト領域外の削除は禁止されています: '$Path'."
    }
}

$testResults = [System.Collections.Generic.List[PSCustomObject]]::new()

function Assert-Test([bool]$Condition, [string]$TestName, [string]$Detail) {
    $item = [PSCustomObject]@{
        TestName = $TestName
        Passed   = $Condition
        Detail   = $Detail
        Time     = (Get-Date).ToString('o')
    }
    $script:testResults.Add($item)

    if (-not $Condition) {
        $msg = "FAIL: [$TestName] $Detail"
        Write-Error $msg
        throw $msg
    }
    Write-Host "  [PASS] $TestName."
}

Write-Host "==============================================================================."
Write-Host "verify-like-ci.ps1 完全隔離・軽快故障検証ハーネス開始."
Write-Host "実作業ツリー: $realRepoRoot (不変性を保証)."
Write-Host "テスト作業領域: $testBaseDir."
Write-Host "実行シェル: $currentPsExe (PID: $PID)."
Write-Host "==============================================================================."

# ------------------------------------------------------------------------------
# 1. 独立 fixture リポジトリの初期化 (実作業ツリーを一切汚染しない).
# ------------------------------------------------------------------------------
$fixtureRepo = Join-Path $testBaseDir 'fixture-repo'
[System.IO.Directory]::CreateDirectory($fixtureRepo) | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'init') | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'config', 'user.email', 'ci-runner-test@ses.local') | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'config', 'user.name', 'RunnerTestHarness') | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'config', 'core.autocrlf', 'false') | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'config', 'core.safecrlf', 'false') | Out-Null

$fixtureScripts = Join-Path $fixtureRepo 'scripts'
[System.IO.Directory]::CreateDirectory($fixtureScripts) | Out-Null
Copy-Item -LiteralPath $commonScript -Destination (Join-Path $fixtureScripts 'verify-ci-common.ps1') -Force
Copy-Item -LiteralPath $runnerScript -Destination (Join-Path $fixtureScripts 'verify-like-ci.ps1') -Force

$fixtureShards = Join-Path $fixtureScripts 'test-suites'
[System.IO.Directory]::CreateDirectory($fixtureShards) | Out-Null
$realShards = Join-Path $scriptDir 'test-suites'
if (Test-Path -LiteralPath $realShards) {
    Get-ChildItem -LiteralPath $realShards | Copy-Item -Destination $fixtureShards -Force
}

$fixtureOps = Join-Path $fixtureRepo 'ops\backup\tests'
[System.IO.Directory]::CreateDirectory($fixtureOps) | Out-Null
[System.IO.File]::WriteAllText((Join-Path $fixtureOps 'run-unit-tests.sh'), "#!/bin/bash`nexit 0`n", $script:Utf8NoBom)
[System.IO.File]::WriteAllText((Join-Path $fixtureOps 'run-integration.sh'), "#!/bin/bash`nexit 0`n", $script:Utf8NoBom)

$fixtureSrc = Join-Path $fixtureRepo 'src\main\java\com\ses'
[System.IO.Directory]::CreateDirectory($fixtureSrc) | Out-Null
[System.IO.File]::WriteAllText((Join-Path $fixtureSrc 'App.java'), "package com.ses; public class App {}`n", $script:Utf8NoBom)

$fixturePom = Join-Path $fixtureRepo 'pom.xml'
[System.IO.File]::WriteAllText($fixturePom, "<project><modelVersion>4.0.0</modelVersion><groupId>com.ses</groupId><artifactId>ses-manager-pro</artifactId><version>1.0.0</version></project>`n", $script:Utf8NoBom)

Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'add', '-A') | Out-Null
Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'commit', '-m', 'initial fixture commit.') | Out-Null
$fixtureRunnerScript = Join-Path $fixtureRepo 'scripts\verify-like-ci.ps1'

# ------------------------------------------------------------------------------
# 2. モック Maven / Bash 用のスクリプト生成.
# ------------------------------------------------------------------------------
$mockBinDir = Join-Path $testBaseDir 'mock-bin'
[System.IO.Directory]::CreateDirectory($mockBinDir) | Out-Null

$mockMvnBat = Join-Path $mockBinDir 'mvn.bat'
$mockBashBat = Join-Path $mockBinDir 'bash.bat'
$mockCallsLog = Join-Path $testBaseDir 'mock_calls.log'

$mockMvnPs1 = Join-Path $mockBinDir 'mock-mvn-handler.ps1'
$mockBashPs1 = Join-Path $mockBinDir 'mock-bash-handler.ps1'

# mock-mvn-handler.ps1 の実装.
$mockMvnPs1Content = @'
param(
    [string]$ConfigDir,
    [string]$RepoRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ArgsList = @($args)
$configMode = 'pass'
$configFile = Join-Path $ConfigDir 'mvn-mode.txt'
if (Test-Path -LiteralPath $configFile) {
    $configMode = (Get-Content -LiteralPath $configFile -Raw).Trim()
}

$tracker = Join-Path $ConfigDir 'mock_calls.log'
[System.IO.File]::AppendAllText($tracker, "mvn: $($ArgsList -join ' ')`r`n")

# -v オプション時はバージョン文字列のみ出力し、生成物は一切作らない.
if ($ArgsList -contains '-v' -or $ArgsList -contains '--version') {
    Write-Host 'Apache Maven 3.9.6 (mock).'
    exit 0
}

if ($configMode -eq 'crash-start') {
    Write-Error "CRASH: Mavenプロセス起動時例外."
    exit 127
}

if ($configMode -eq 'fail-exit') {
    exit 1
}

# clean test の処理.
$targetReports = Join-Path $RepoRoot 'target\surefire-reports'
$targetBrowserEvidence = Join-Path $RepoRoot 'target\browser-m-evidence'
$targetJacocoDir = Join-Path $RepoRoot 'target\site\jacoco'
$targetJacocoExec = Join-Path $RepoRoot 'target\jacoco.exec'

if ($ArgsList -contains 'clean') {
    if (Test-Path -LiteralPath $targetReports) {
        Get-ChildItem -LiteralPath $targetReports -Force | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $targetBrowserEvidence) {
        Remove-Item -LiteralPath $targetBrowserEvidence -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $targetJacocoDir) {
        Remove-Item -LiteralPath $targetJacocoDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $targetJacocoExec) {
        Remove-Item -LiteralPath $targetJacocoExec -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Test-Path -LiteralPath $targetReports)) {
    [System.IO.Directory]::CreateDirectory($targetReports) | Out-Null
}

$isMysql = $ArgsList -contains '-Pmysql-tests'
$isPerformance = $ArgsList -contains '-Pperformance-tests'
$isBrowser = $ArgsList -contains '-Pbrowser-tests'

if ($configMode -eq 'zero-tests') {
    $xml = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.EmptyTest" tests="0" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.EmptyTest.xml'), $xml)
    exit 0
}

if ($configMode -eq 'missing-xml') {
    exit 0
}

if ($configMode -eq 'corrupt-xml') {
    $xml = '<?xml version="1.0"?><testsuite name="bad" <unclosed>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-corrupt.xml'), $xml)
    exit 0
}

if ($configMode -eq 'skipped-tests') {
    $xml = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.SkippedTest" tests="5" failures="0" errors="0" skipped="1"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.SkippedTest.xml'), $xml)
    exit 0
}

if ($configMode -eq 'dump-file') {
    $xml = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.SampleTest" tests="5" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.SampleTest.xml'), $xml)
    [System.IO.File]::WriteAllText((Join-Path $targetReports '2026-10-26T16-00-00.dumpstream'), 'JVM dump content')
    exit 0
}

# 通常 pass モード.
if ($isMysql) {
    $cScriptPath = Join-Path $RepoRoot 'scripts\verify-ci-common.ps1'
    $cContent = [System.IO.File]::ReadAllText($cScriptPath, [System.Text.Encoding]::UTF8)
    . ([ScriptBlock]::Create($cContent))
    $classes = Get-RequiredMysqlClasses -RepoRoot $RepoRoot
    if ($configMode -eq 'missing-shard-class') {
        $classes = $classes | Where-Object { $_ -ne $classes[0] }
    }
    foreach ($cls in $classes) {
        $xml = "<?xml version=""1.0"" encoding=""UTF-8""?><testsuite name=""com.ses.$cls"" tests=""3"" failures=""0"" errors=""0"" skipped=""0""></testsuite>"
        [System.IO.File]::WriteAllText((Join-Path $targetReports "TEST-com.ses.$cls.xml"), $xml)
        [System.IO.File]::WriteAllText((Join-Path $targetReports "com.ses.$cls.txt"), "Test output report text")
    }
} elseif ($isBrowser) {
    $xml = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.web.SampleBrowserTest" tests="2" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.web.SampleBrowserTest.xml'), $xml)
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'com.ses.web.SampleBrowserTest.txt'), 'Browser test report')
    $bDir = Join-Path $RepoRoot 'target\browser-m-evidence'
    [System.IO.Directory]::CreateDirectory($bDir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $bDir 'screenshot.png'), 'fake-png-screenshot')
} elseif ($isPerformance) {
    $xmlPerf = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.PerformanceSampleTest" tests="5" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.PerformanceSampleTest.xml'), $xmlPerf)
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'com.ses.PerformanceSampleTest.txt'), 'Performance report')
} else {
    # fast-tests.
    $xmlInv = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.migration.MySqlTestShardInventoryTest" tests="1" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.migration.MySqlTestShardInventoryTest.xml'), $xmlInv)
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'com.ses.migration.MySqlTestShardInventoryTest.txt'), 'Inventory check output')
    $xmlFast = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.FastSampleTest" tests="10" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.FastSampleTest.xml'), $xmlFast)
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'com.ses.FastSampleTest.txt'), 'Fast test output')

    # JaCoCo のモック生成.
    [System.IO.File]::WriteAllText($targetJacocoExec, 'dummy-jacoco-binary-data')
    [System.IO.Directory]::CreateDirectory($targetJacocoDir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $targetJacocoDir 'index.html'), '<html>jacoco report</html>')
}

if ($configMode -eq 'archive-fail') {
    $archiveFailTargetFile = Join-Path $ConfigDir 'archive-fail-target.txt'
    if (Test-Path -LiteralPath $archiveFailTargetFile) {
        $targetEv = (Get-Content -LiteralPath $archiveFailTargetFile -Raw).Trim()
        $blockDir = Join-Path $targetEv 'fast-tests'
        [System.IO.Directory]::CreateDirectory($blockDir) | Out-Null
        [System.IO.File]::WriteAllText((Join-Path $blockDir 'artifacts'), 'blocking-file')
    }
}

exit 0
'@

[System.IO.File]::WriteAllText($mockMvnPs1, $mockMvnPs1Content, $script:Utf8NoBom)

# mock-bash-handler.ps1 の実装.
$mockBashPs1Content = @'
param(
    [string]$ConfigDir,
    [string]$RepoRoot
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$ArgsList = @($args)
$configMode = 'pass'
$configFile = Join-Path $ConfigDir 'bash-mode.txt'
if (Test-Path -LiteralPath $configFile) {
    $configMode = (Get-Content -LiteralPath $configFile -Raw).Trim()
}

$tracker = Join-Path $ConfigDir 'mock_calls.log'
[System.IO.File]::AppendAllText($tracker, "bash: $($ArgsList -join ' ')`r`n")

if ($configMode -eq 'fail-exit') {
    exit 1
}

$scriptArg = if ($ArgsList.Count -gt 0) { $ArgsList[0] } else { '' }
if ($scriptArg -like '*run-unit-tests.sh') {
    Write-Host 'backup unit suite: SUCCESS.'
    exit 0
}

if ($scriptArg -like '*run-integration.sh') {
    $workDir = $env:INTEGRATION_WORK_DIR
    if ([string]::IsNullOrWhiteSpace($workDir)) {
        throw "INTEGRATION_WORK_DIR not set"
    }
    $evDir = Join-Path $workDir 'evidence'
    [System.IO.Directory]::CreateDirectory($evDir) | Out-Null

    $reqFiles = @(
        'integration-summary.json',
        'validate.json',
        'restore.log',
        'target-markers.txt',
        'uploads-markers.txt',
        'source-state.txt',
        'report-restore-contract.txt'
    )
    foreach ($rf in $reqFiles) {
        $p = Join-Path $evDir $rf
        [System.IO.File]::WriteAllText($p, "dummy-$rf-content")
    }

    $drillJson = '{"rto_ok": true, "rpo_ok": true}'
    [System.IO.File]::WriteAllText((Join-Path $evDir 'drill-report.json'), $drillJson)

    $beforeMarker = 1
    if ($configMode -eq 'bad-marker') {
        $beforeMarker = 0
    }
    $summaryJson = "{""state"": ""SUCCESS"", ""target_db"": {""before_marker"": 1, ""mid_dml_replayed"": 1, ""after_marker"": 0}, ""uploads_staging"": {""before_marker"": $beforeMarker, ""after_marker"": 0}}"
    [System.IO.File]::WriteAllText((Join-Path $evDir 'integration-summary.json'), $summaryJson)

    $shaLines = @()
    foreach ($rf in $reqFiles) {
        $fp = Join-Path $evDir $rf
        $stream = [System.IO.File]::OpenRead($fp)
        $sha = [System.Security.Cryptography.SHA256]::Create()
        $h = [System.BitConverter]::ToString($sha.ComputeHash($stream)).Replace('-', '').ToLowerInvariant()
        $sha.Dispose()
        $stream.Dispose()
        $shaLines += "$h  $rf"
    }
    if ($configMode -eq 'bad-manifest') {
        $shaLines += 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  non-existent.txt'
    }
    [System.IO.File]::WriteAllLines((Join-Path $evDir 'evidence-sha.txt'), $shaLines)

    Write-Host 'backup integration suite: SUCCESS.'
    exit 0
}

exit 0
'@

[System.IO.File]::WriteAllText($mockBashPs1, $mockBashPs1Content, $script:Utf8NoBom)

# .bat ラッパー作成 (実行中の $currentPsExe をそのまま継承).
$mvnBatContent = "@echo off`r`n`"$currentPsExe`" -ExecutionPolicy Bypass -File `"$mockMvnPs1`" -ConfigDir `"$testBaseDir`" -RepoRoot `"$fixtureRepo`" %*`r`nexit /b %ERRORLEVEL%`r`n"
[System.IO.File]::WriteAllText($mockMvnBat, $mvnBatContent, [System.Text.Encoding]::ASCII)

$bashBatContent = "@echo off`r`n`"$currentPsExe`" -ExecutionPolicy Bypass -File `"$mockBashPs1`" -ConfigDir `"$testBaseDir`" -RepoRoot `"$fixtureRepo`" %*`r`nexit /b %ERRORLEVEL%`r`n"
[System.IO.File]::WriteAllText($mockBashBat, $bashBatContent, [System.Text.Encoding]::ASCII)

try {
    # --------------------------------------------------------------------------
    # 1. 門禁縮減パラメータの厳格白名単拒絶 (Group 1)
    # --------------------------------------------------------------------------
    Write-Host "Test 01: Maven引数の厳格白名単および不正引数拒絶検証..."
    $forbiddenTests = @(
        '-Dtest=SampleTest',
        '-DskipTests',
        '-Pmysql-tests',
        '--file=other.xml',
        '-f other.xml',
        '--activate-profiles=p',
        '--projects=mod',
        '-pl mod',
        '-Dgroups=smoke',
        '-Dsurefire.includes=**/*Test.java',
        '-Dunknown=foo'
    )
    foreach ($fArg in $forbiddenTests) {
        $ev01 = Join-Path $testBaseDir ("ev-forbidden-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 6))
        $proc01 = Start-Process -FilePath $currentPsExe `
            -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev01`"", "-MavenArgs", $fArg) `
            -NoNewWindow -Wait -PassThru

        Assert-Test ($proc01.ExitCode -eq 1) "01.白名単拒絶 ($fArg) - 終了コード." "未許可パラメータが ExitCode 1 で拒絶されること."
        Assert-Test (-not (Test-Path -LiteralPath $ev01)) "01.白名単拒絶 - 証跡作成前中止." "EvidenceDir が作成される前に中止されること."
    }

    # 許可された安全引数の通過確認.
    $safeArg = '-Djava.awt.headless=true'
    $ev01Safe = Join-Path $testBaseDir 'ev-safe-arg'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')
    $proc01Safe = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev01Safe`"", "-MavenArgs", $safeArg) `
        -NoNewWindow -Wait -PassThru
    Assert-Test ($proc01Safe.ExitCode -eq 0) "01.安全引数許可 ($safeArg) - 終了コード." "安全パラメータが正常に受容されること."

    # --------------------------------------------------------------------------
    # 2. TargetedRun 区分 (非 FULL_GATE 判定) (Group 1)
    # --------------------------------------------------------------------------
    Write-Host "Test 02: TargetedRun モードの非FULL_GATE判定検証..."
    $ev02 = Join-Path $testBaseDir 'ev-targeted'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $proc02 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev02`"", "-TargetedRun", "-MavenArgs", "-Dtest=SampleTest") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc02.ExitCode -eq 0) "02.TargetedRun - 終了コード." "ExitCode が 0 であること."
    $sess02 = [System.IO.File]::ReadAllText((Join-Path $ev02 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess02.gateType -eq 'TARGETED_NON_FULL_GATE') "02.TargetedRun - gateType判定." "gateType が TARGETED_NON_FULL_GATE であること."
    Assert-Test ($sess02.state -eq 'TARGETED_PASS_NOT_FULL_GATE') "02.TargetedRun - state判定." "state が TARGETED_PASS_NOT_FULL_GATE (非PASS) であること."

    # --------------------------------------------------------------------------
    # 3. ワークツリー境界判定・衝突検証・遮蔽防止 (Group 2)
    # --------------------------------------------------------------------------
    Write-Host "Test 03: ワークツリー境界判定・プレフィックス衝突・遮蔽防止検証..."

    # 3A: 未追跡ファイルの拒絶.
    $dirtyFile = Join-Path $fixtureRepo 'src\main\dirty-marker.txt'
    [System.IO.File]::WriteAllText($dirtyFile, 'untracked dirty content')
    try {
        $ev03A = Join-Path $testBaseDir 'ev-dirty'
        $proc03A = Start-Process -FilePath $currentPsExe `
            -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev03A`"") `
            -NoNewWindow -Wait -PassThru
        Assert-Test ($proc03A.ExitCode -eq 1) "03A.未追跡検知 - 終了コード." "未追跡ファイル存在時に ExitCode 1 で拒絶されること."
    } finally {
        Remove-Item -LiteralPath $dirtyFile -Force -ErrorAction SilentlyContinue
    }

    # 3B: src/ma vs src/main プレフィックス衝突検証 (src/ma 証跡ディレクトリが src/main の変更を遮蔽してはならない).
    $mainDirty = Join-Path $fixtureRepo 'src\main\java\com\ses\CollisionTest.java'
    [System.IO.File]::WriteAllText($mainDirty, 'public class CollisionTest {}')
    try {
        $fakeMaEv = Join-Path $fixtureRepo 'src\ma'
        $cleanThrown = $false
        try {
            Assert-CleanWorkingTree -RepoRoot $fixtureRepo -EvidenceDir $fakeMaEv
        } catch {
            if ($_.Exception.Message -match 'CollisionTest\.java' -or $_.Exception.Message -like "*FATAL:*") {
                $cleanThrown = $true
            }
        }
        Assert-Test $cleanThrown "03B.境界衝突 (src/ma vs src/main) - 遮蔽防止." "src/ma は src/main 配下の変更を遮蔽できないこと."
    } finally {
        Remove-Item -LiteralPath $mainDirty -Force -ErrorAction SilentlyContinue
    }

    # 3C: target vs target-other 衝突検証 (target-other は target として除外されてはならない).
    $targetOtherDir = Join-Path $fixtureRepo 'target-other'
    [System.IO.Directory]::CreateDirectory($targetOtherDir) | Out-Null
    $otherFile = Join-Path $targetOtherDir 'leak.txt'
    [System.IO.File]::WriteAllText($otherFile, 'other file')
    try {
        $cleanThrownTarget = $false
        try {
            Assert-CleanWorkingTree -RepoRoot $fixtureRepo
        } catch {
            if ($_.Exception.Message -match 'leak\.txt' -or $_.Exception.Message -match 'target-other' -or $_.Exception.Message -like "*FATAL:*") {
                $cleanThrownTarget = $true
            }
        }
        Assert-Test $cleanThrownTarget "03C.境界衝突 (target vs target-other) - 除外抑止." "target-other 配下のファイルは target として除外されないこと."
    } finally {
        Remove-Item -LiteralPath $targetOtherDir -Recurse -Force -ErrorAction SilentlyContinue
    }

    # 3D: 追跡済み (tracked) ファイルの変更は EvidenceDir 配下であっても遮蔽禁止.
    # fixture リポジトリ内でコミット済みの App.java を変更.
    $trackedApp = Join-Path $fixtureRepo 'src\main\java\com\ses\App.java'
    [System.IO.File]::WriteAllText($trackedApp, "package com.ses; public class App { int modified = 1; }`n", $script:Utf8NoBom)
    try {
        $cleanThrownTracked = $false
        try {
            # EvidenceDir をリポジトリ全体に広げても tracked 変更は除外されない.
            Assert-CleanWorkingTree -RepoRoot $fixtureRepo -EvidenceDir $fixtureRepo
        } catch {
            if ($_.Exception.Message -match 'App\.java' -or $_.Exception.Message -like "*FATAL:*") {
                $cleanThrownTracked = $true
            }
        }
        Assert-Test $cleanThrownTracked "03D.追跡済み変更 - 遮蔽禁止." "tracked ファイルの変更は EvidenceDir の指定に関わらず検出されること."
    } finally {
        Invoke-FixtureGit -GitArgs @('-C', $fixtureRepo, 'checkout', 'HEAD', '--', 'src/main/java/com/ses/App.java') | Out-Null
    }

    # 3E: ソース領域を EvidenceDir に指定した場合の拒絶.
    $srcAsEv = Join-Path $fixtureRepo 'src\my-evidence'
    $evDirRejected = $false
    try {
        Assert-ValidEvidenceDir -RepoRoot $fixtureRepo -EvidenceDir $srcAsEv
    } catch {
        if ($_.Exception.Message -match 'my-evidence' -or $_.Exception.Message -like "*FATAL:*") {
            $evDirRejected = $true
        }
    }
    Assert-Test $evDirRejected "03E.ソース領域のEvidenceDir指定拒絶." "ソースディレクトリ配下は EvidenceDir として拒絶されること."

    # --------------------------------------------------------------------------
    # 4. 初回 clean 前の旧 target 保全 & ハッシュ検証 (Group 5)
    # --------------------------------------------------------------------------
    Write-Host "Test 04: 初回 clean 前の旧 target 保全 & 元ハッシュ照合検証..."
    $fTargetDir = Join-Path $fixtureRepo 'target'
    [System.IO.Directory]::CreateDirectory($fTargetDir) | Out-Null
    $oldTargetFile = Join-Path $fTargetDir 'old-build-artifact.txt'
    $expectedOldContent = 'original target data for preservation test.'
    [System.IO.File]::WriteAllText($oldTargetFile, $expectedOldContent)
    $origHash = Get-Sha256Hex -FilePath $oldTargetFile

    $ev04 = Join-Path $testBaseDir 'ev-target-preservation'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')

    $proc04 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev04`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc04.ExitCode -eq 0) "04.target保全 - 終了コード." "ExitCode が 0 であること."
    $preservedOld = Join-Path $ev04 'pre-run-target-preservation\old-build-artifact.txt'
    Assert-Test (Test-Path -LiteralPath $preservedOld) "04.target保全 - ファイル存在." "旧ファイルが pre-run-target-preservation に保全されること."
    $presHash = Get-Sha256Hex -FilePath $preservedOld
    Assert-Test ($origHash -eq $presHash) "04.target保全 - ハッシュ完全一致." "保全ファイルのSHA-256が投入前と完全一致すること (hash=$presHash)."

    # --------------------------------------------------------------------------
    # 5. 非ゼロ終了コードによる中断 & 後続 clean 抑止検証 (Group 5)
    # --------------------------------------------------------------------------
    Write-Host "Test 05: 非ゼロ終了コードと後続 clean 抑止検証..."
    [System.IO.File]::WriteAllText($mockCallsLog, '')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'fail-exit')

    $ev05 = Join-Path $testBaseDir 'ev-fail-exit'
    $proc05 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev05`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc05.ExitCode -eq 1) "05.非ゼロ終了 - 終了コード." "ExitCode が 1 であること."
    $calls05 = @(Get-Content -LiteralPath $mockCallsLog)
    $cleanCalls05 = @($calls05 | Where-Object { $_ -like '*clean*' })
    Assert-Test ($cleanCalls05.Count -eq 1) "05.非ゼロ終了 - clean呼び出し抑止." "clean の実行回数が1回（第1スイートのみ）で後続が抑止されること."

    # --------------------------------------------------------------------------
    # 6. ゼロテスト実行の拒絶 (Group 1/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 06: ゼロテスト実行の拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'zero-tests')
    $ev06 = Join-Path $testBaseDir 'ev-zero-tests'
    $proc06 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev06`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc06.ExitCode -eq 1) "06.ゼロテスト - 終了コード." "ExitCode が 1 であること."
    $sess06 = [System.IO.File]::ReadAllText((Join-Path $ev06 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess06.state -eq 'FAIL') "06.ゼロテスト - セッション状態." "セッション状態が FAIL であること."

    # --------------------------------------------------------------------------
    # 7. XML欠落の拒絶 (Group 1/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 07: XML欠落の拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'missing-xml')
    $ev07 = Join-Path $testBaseDir 'ev-missing-xml'
    $proc07 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev07`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc07.ExitCode -eq 1) "07.XML欠落 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 8. 破損XMLの拒絶 (Group 1/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 08: 破損XMLの拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'corrupt-xml')
    $ev08 = Join-Path $testBaseDir 'ev-corrupt-xml'
    $proc08 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev08`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc08.ExitCode -eq 1) "08.破損XML - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 9. skipテストの拒絶 (Group 1/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 09: skipテストの拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'skipped-tests')
    $ev09 = Join-Path $testBaseDir 'ev-skipped-tests'
    $proc09 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev09`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc09.ExitCode -eq 1) "09.skip検知 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 10. Shardクラス欠落検知 (Group 1)
    # --------------------------------------------------------------------------
    Write-Host "Test 10: Shardクラス欠落検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'missing-shard-class')
    $ev10 = Join-Path $testBaseDir 'ev-missing-shard'
    $proc10 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev10`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc10.ExitCode -eq 1) "10.Shard欠落 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 11. dumpファイル存在時の一票否決 (Group 1/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 11: dumpファイル存在時の一票否決検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'dump-file')
    $ev11 = Join-Path $testBaseDir 'ev-dump-file'
    $proc11 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev11`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc11.ExitCode -eq 1) "11.dump検知 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 12. PITR不正manifest検知 (Group 3)
    # --------------------------------------------------------------------------
    Write-Host "Test 12: PITR不正manifest検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'bad-manifest')
    $ev12 = Join-Path $testBaseDir 'ev-bad-manifest'
    $proc12 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev12`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc12.ExitCode -eq 1) "12.不正manifest - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 13. PITR不正marker検知 (Group 3)
    # --------------------------------------------------------------------------
    Write-Host "Test 13: PITR不正marker検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'bad-marker')
    $ev13 = Join-Path $testBaseDir 'ev-bad-marker'
    $proc13 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev13`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc13.ExitCode -eq 1) "13.不正marker - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 14. 残留 RUNNING 状態の INCOMPLETE 判定 (Group 4/5)
    # --------------------------------------------------------------------------
    Write-Host "Test 14: 残留 RUNNING 状態の INCOMPLETE 判定検証..."
    $ev14 = Join-Path $testBaseDir 'ev-running-incomplete'
    [System.IO.Directory]::CreateDirectory($ev14) | Out-Null
    $runningData = [ordered]@{
        state     = 'RUNNING'
        completed = $false
    }
    Write-JsonStateFile -FilePath (Join-Path $ev14 'session-state.json') -Data $runningData
    $gateState = Get-GateExecutionState -EvidenceDir $ev14
    Assert-Test ($gateState -eq 'INCOMPLETE') "14.RUNNING残留 - 状態判定." "state が INCOMPLETE と判定されること."

    # --------------------------------------------------------------------------
    # 15. 既存証跡ディレクトリ再利用拒絶 (Group 2)
    # --------------------------------------------------------------------------
    Write-Host "Test 15: 既存証跡ディレクトリ再利用拒絶検証..."
    $ev15 = Join-Path $testBaseDir 'ev-existing'
    [System.IO.Directory]::CreateDirectory($ev15) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $ev15 'leftover.txt'), 'old data')

    $proc15 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev15`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc15.ExitCode -eq 1) "15.ディレクトリ再利用拒絶 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 16. 空白を含む証跡パス (Group 2)
    # --------------------------------------------------------------------------
    Write-Host "Test 16: 空白を含む証跡パス検証..."
    $ev16 = Join-Path $testBaseDir 'evidence with spaces'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $proc16 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev16`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc16.ExitCode -eq 0) "16.空白パス - 終了コード." "ExitCode が 0 であること."
    Assert-Test (Test-Path -LiteralPath (Join-Path $ev16 'session-state.json')) "16.空白パス - session-state存在." "session-state.json が存在すること."

    # --------------------------------------------------------------------------
    # 17. 秘密情報漏洩スキャン検知 (Group 4)
    # --------------------------------------------------------------------------
    Write-Host "Test 17: 秘密情報漏洩スキャン検知検証..."
    $ev17 = Join-Path $testBaseDir 'ev-secret-leak'
    [System.IO.Directory]::CreateDirectory($ev17) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $ev17 'leaked.txt'), 'contains src-password inside')
    $leakDetected = $false
    try {
        Scan-EvidenceDirectoryForSecrets -EvidenceDir $ev17
    } catch {
        if ($_.Exception.Message -match 'src-password' -or $_.Exception.Message -like "*FATAL:*") {
            $leakDetected = $true
        }
    }
    Assert-Test $leakDetected "17.秘密情報スキャン - 検知." "src-password の混入が検出されること."

    # --------------------------------------------------------------------------
    # 18. 故障注入: プロセス実行例外 (Group 4)
    # --------------------------------------------------------------------------
    Write-Host "Test 18: プロセス実行例外時の最善努力アーカイブ & 状態記録検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'crash-start')
    $ev18 = Join-Path $testBaseDir 'ev-process-crash'

    $proc18 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev18`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc18.ExitCode -eq 1) "18.プロセス例外 - 終了コード." "ExitCode が 1 であること."
    $sess18 = [System.IO.File]::ReadAllText((Join-Path $ev18 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess18.state -eq 'FAIL') "18.プロセス例外 - セッション状態." "セッション状態が FAIL であること."
    Assert-Test ($sess18.completed -eq $true) "18.プロセス例外 - 完了状態." "completed が true として記録されること."

    # --------------------------------------------------------------------------
    # 19. 故障注入: アーカイブ失敗時の終了コード2 & 後続clean抑止 (Group 4/6)
    # --------------------------------------------------------------------------
    Write-Host "Test 19: アーカイブ失敗時の終了コード2と後続clean厳格抑止検証..."
    $ev19 = Join-Path $testBaseDir 'ev-archive-fail'
    [System.IO.File]::WriteAllText($mockCallsLog, '')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'archive-fail')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'archive-fail-target.txt'), $ev19)

    $proc19 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev19`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc19.ExitCode -eq 2) "19.アーカイブ失敗 - 終了コード2." "アーカイブ失敗時に約束された ExitCode 2 で中止されること."
    $calls19 = @(Get-Content -LiteralPath $mockCallsLog)
    $cleanCalls19 = @($calls19 | Where-Object { $_ -like '*clean*' })
    Assert-Test ($cleanCalls19.Count -eq 1) "19.アーカイブ失敗 - 後続clean抑止." "アーカイブ失敗により第2スイート以降の clean が厳格に抑止されること (callCount=1)."

    # --------------------------------------------------------------------------
    # 20. PITR セキュリティ境界検証 (ルート・祖先・既存ディレクトリ・ジャンクション拒絶) (Group 3)
    # --------------------------------------------------------------------------
    Write-Host "Test 20: PITR セキュリティ境界検証..."
    $rootPath = [System.IO.Path]::GetPathRoot($testBaseDir)
    $rootRejected = $false
    try {
        Assert-ValidPitrWorkDir -RepoRoot $fixtureRepo -EvidenceDir $ev04 -PitrWorkDir $rootPath
    } catch {
        if ($_.Exception.Message -like "*FATAL:*" -and $_.Exception.Message -like "*$rootPath*") {
            $rootRejected = $true
        }
    }
    Assert-Test $rootRejected "20.PITR境界 - ルート拒絶." "C:\ 等のルートディレクトリが拒絶されること."

    $ancestorRejected = $false
    $parentRepo = Split-Path -Parent $fixtureRepo
    try {
        Assert-ValidPitrWorkDir -RepoRoot $fixtureRepo -EvidenceDir $ev04 -PitrWorkDir $parentRepo
    } catch {
        if ($_.Exception.Message -like "*FATAL:*" -and $_.Exception.Message -like "*$parentRepo*") {
            $ancestorRejected = $true
        }
    }
    Assert-Test $ancestorRejected "20.PITR境界 - リポジトリ祖先拒絶." "リポジトリの親・祖先ディレクトリが拒絶されること."

    $existingRejected = $false
    try {
        Assert-ValidPitrWorkDir -RepoRoot $fixtureRepo -EvidenceDir $ev04 -PitrWorkDir $mockBinDir
    } catch {
        if ($_.Exception.Message -like "*FATAL:*" -and $_.Exception.Message -like "*$mockBinDir*") {
            $existingRejected = $true
        }
    }
    Assert-Test $existingRejected "20.PITR境界 - 既存ディレクトリ拒絶." "既知の既存ディレクトリが拒絶されること."

    # --------------------------------------------------------------------------
    # 21. 正常全通過 (Full Pass) 検証 (Group 6)
    # --------------------------------------------------------------------------
    Write-Host "Test 21: 正常全通過 (Full Pass) 検証..."
    [System.IO.File]::WriteAllText($mockCallsLog, '')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $ev21 = Join-Path $testBaseDir 'ev-full-pass'
    $proc21 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$fixtureRunnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev21`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc21.ExitCode -eq 0) "21.全通過 - 終了コード." "ExitCode が 0 であること."
    $sess21 = [System.IO.File]::ReadAllText((Join-Path $ev21 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess21.state -eq 'PASS') "21.全通過 - state判定." "state が PASS であること."
    Assert-Test ($sess21.completed -eq $true) "21.全通過 - completed判定." "completed が true であること."

    $calls21 = @(Get-Content -LiteralPath $mockCallsLog)
    $cleanCalls21 = @($calls21 | Where-Object { $_ -like '*clean*' })
    Assert-Test ($cleanCalls21.Count -eq 4) "21.全通過 - clean呼び出し回数." "全4スイート分 (4回) clean が呼び出されること."

    # マニフェスト sha256sum.txt の存在と整合性確認.
    $fastManifest = Join-Path $ev21 'fast-tests\artifacts\sha256sum.txt'
    Assert-Test (Test-Path -LiteralPath $fastManifest) "21.全通過 - fastマニフェスト." "fast-tests の sha256sum.txt が存在すること."
    $mysqlManifest = Join-Path $ev21 'mysql-tests\artifacts\sha256sum.txt'
    Assert-Test (Test-Path -LiteralPath $mysqlManifest) "21.全通過 - mysqlマニフェスト." "mysql-tests の sha256sum.txt が存在すること."

    $noSecrets = $true
    try {
        Scan-EvidenceDirectoryForSecrets -EvidenceDir $ev21
    } catch {
        $noSecrets = $false
    }
    Assert-Test $noSecrets "21.全通過 - 交付ツリー無秘密." "最終交付ツリーに秘密情報が存在しないこと."

    # --------------------------------------------------------------------------
    # 22. 実作業ツリー不変性の厳格断言 (Group 5)
    # --------------------------------------------------------------------------
    Write-Host "Test 22: 実作業ツリー不変性の検証..."
    $realEndHeadOutput = Invoke-FixtureGit -GitArgs @('-C', $realRepoRoot, 'rev-parse', 'HEAD')
    $realEndHead = $realEndHeadOutput -join "`n"
    $realEndStatus = @(Invoke-FixtureGit -GitArgs @('-C', $realRepoRoot, 'status', '--porcelain'))
    Assert-Test ($realEndHead -eq $realStartHead) "22.実作業ツリー不変 - HEAD検証." "実リポジトリのHEAD SHAが変化していないこと (head=$realEndHead)."
    Assert-Test ($realEndStatus.Count -eq $realStartStatus.Count) "22.実作業ツリー不変 - 作業ツリー検証." "実リポジトリの作業ツリーが一切変更されていないこと."

    # --------------------------------------------------------------------------
    # 証跡の永続サマリー保存 (削除せずレビュー可能状態として保全).
    # --------------------------------------------------------------------------
    $deliverySummaryDir = Join-Path $testBaseDir 'delivery-summary'
    [System.IO.Directory]::CreateDirectory($deliverySummaryDir) | Out-Null

    $summaryObj = [ordered]@{
        timestamp       = (Get-Date).ToString('o')
        psVersion       = $PSVersionTable.PSVersion.ToString()
        currentPsExe    = $currentPsExe
        realRepoRoot    = $realRepoRoot
        realStartHead   = $realStartHead
        fixtureRepo     = $fixtureRepo
        totalTests      = $testResults.Count
        passedCount     = @($testResults | Where-Object { $_.Passed }).Count
        failedCount     = @($testResults | Where-Object { -not $_.Passed }).Count
        results         = $testResults
        inspectableDirs = @{
            fullPassEvidence   = $ev21
            targetPreservation = $ev04
            targetedRun        = $ev02
            archiveFail        = $ev19
        }
    }
    $summaryJsonPath = Join-Path $deliverySummaryDir 'test-summary.json'
    [System.IO.File]::WriteAllText($summaryJsonPath, ($summaryObj | ConvertTo-Json -Depth 6), $script:Utf8NoBom)

    Write-Host "==============================================================================."
    Write-Host ("全 " + $testResults.Count + " 項目の完全隔離・軽快故障・合取判定テストがすべて PASS しました (0 FAILURES).")
    Write-Host "可読サマリー保存先: $summaryJsonPath."
    Write-Host "検証証跡保存先: $testBaseDir."
    Write-Host "==============================================================================."
    exit 0
} catch {
    Write-Error "テストハーネス実行中に致命的エラーが発生しました: $($_.Exception.Message)."
    Write-Host "検証失敗のため作業領域を保全します: $testBaseDir."
    exit 1
}
