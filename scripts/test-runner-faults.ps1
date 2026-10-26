<#
.SYNOPSIS
  verify-like-ci.ps1 の合取判定・状態管理・証跡保全・異常系ハンドリングの自動検証ハーネス (UTF-8 without BOM).

.DESCRIPTION
  実Maven・Docker・MySQL・Chrome・PITRバックアップを一切起動せず、.
  モック環境を用いて runner (verify-like-ci.ps1) を実際に呼び出し、.
  全異常系および正常系シナリオを高速・決定論的に検証する.
  断言内容: 終了コード、JSON状態、証跡保全、後続 clean 呼び出し抑止.
  失敗時は現場（テンポラリディレクトリ）を保全して終了する.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptDir
$commonScript = Join-Path $scriptDir 'verify-ci-common.ps1'
$runnerScript = Join-Path $scriptDir 'verify-like-ci.ps1'

if (-not (Test-Path -LiteralPath $commonScript)) {
    throw "FATAL: verify-ci-common.ps1 が見つかりません: '$commonScript'."
}
if (-not (Test-Path -LiteralPath $runnerScript)) {
    throw "FATAL: verify-like-ci.ps1 が見つかりません: '$runnerScript'."
}

. $commonScript

$currentPsExe = (Get-Process -Id $PID).Path
$testBaseDir = Join-Path ([System.IO.Path]::GetTempPath()) ("runner-fault-tests-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
[System.IO.Directory]::CreateDirectory($testBaseDir) | Out-Null

$testFailed = $false
$failureReason = ''

function Assert-Test([bool]$Condition, [string]$TestName, [string]$FailureDetail) {
    if (-not $Condition) {
        $script:testFailed = $true
        $script:failureReason = "FAIL: [$TestName] $FailureDetail"
        Write-Error $script:failureReason
        throw $script:failureReason
    }
    Write-Host "  [PASS] $TestName."
}

Write-Host "==============================================================================."
Write-Host "verify-like-ci.ps1 軽快故障・合取判定検証ハーネス開始."
Write-Host "テスト作業領域: $testBaseDir."
Write-Host "==============================================================================."

# ------------------------------------------------------------------------------
# モック Maven / Bash 用のスクリプト生成.
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

if ($configMode -eq 'fail-exit') {
    exit 1
}

# clean test の処理.
$targetReports = Join-Path $RepoRoot 'target\surefire-reports'
$targetBrowserEvidence = Join-Path $RepoRoot 'target\browser-m-evidence'
if ($ArgsList -contains 'clean') {
    if (Test-Path -LiteralPath $targetReports) {
        Get-ChildItem -LiteralPath $targetReports -Force | Remove-Item -Recurse -Force -ErrorAction SilentlyContinue
    }
    if (Test-Path -LiteralPath $targetBrowserEvidence) {
        Remove-Item -LiteralPath $targetBrowserEvidence -Recurse -Force -ErrorAction SilentlyContinue
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
    # XMLを生成しない.
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
    # 87クラスのXMLを生成.
    . (Join-Path $RepoRoot 'scripts\verify-ci-common.ps1')
    $classes = Get-RequiredMysqlClasses -RepoRoot $RepoRoot
    if ($configMode -eq 'missing-shard-class') {
        # 1クラス削る.
        $classes = $classes | Where-Object { $_ -ne $classes[0] }
    }
    foreach ($cls in $classes) {
        $xml = "<?xml version=""1.0"" encoding=""UTF-8""?><testsuite name=""com.ses.$cls"" tests=""3"" failures=""0"" errors=""0"" skipped=""0""></testsuite>"
        [System.IO.File]::WriteAllText((Join-Path $targetReports "TEST-com.ses.$cls.xml"), $xml)
    }
} elseif ($isBrowser) {
    $xml = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.web.SampleBrowserTest" tests="2" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.web.SampleBrowserTest.xml'), $xml)
    $bDir = Join-Path $RepoRoot 'target\browser-m-evidence'
    [System.IO.Directory]::CreateDirectory($bDir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $bDir 'screenshot.png'), 'fake-png')
} elseif ($isPerformance) {
    $xmlPerf = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.PerformanceSampleTest" tests="5" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.PerformanceSampleTest.xml'), $xmlPerf)
} else {
    # fast-tests: MySqlTestShardInventoryTest を必ず含む.
    $xmlInv = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.migration.MySqlTestShardInventoryTest" tests="1" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.migration.MySqlTestShardInventoryTest.xml'), $xmlInv)
    $xmlFast = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.FastSampleTest" tests="10" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $targetReports 'TEST-com.ses.FastSampleTest.xml'), $xmlFast)
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
    Write-Host 'backup unit suite: SUCCESS'
    exit 0
}

if ($scriptArg -like '*run-integration.sh') {
    $workDir = $env:INTEGRATION_WORK_DIR
    if ([string]::IsNullOrWhiteSpace($workDir)) {
        throw "INTEGRATION_WORK_DIR not set"
    }
    $evDir = Join-Path $workDir 'evidence'
    [System.IO.Directory]::CreateDirectory($evDir) | Out-Null

    # 7つの必須ファイル.
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

    # drill-report.json
    $drillJson = '{"rto_ok": true, "rpo_ok": true}'
    [System.IO.File]::WriteAllText((Join-Path $evDir 'drill-report.json'), $drillJson)

    # integration-summary.json のマーカー設定.
    $beforeMarker = 1
    if ($configMode -eq 'bad-marker') {
        $beforeMarker = 0
    }
    $summaryJson = "{""state"": ""SUCCESS"", ""target_db"": {""before_marker"": 1, ""mid_dml_replayed"": 1, ""after_marker"": 0}, ""uploads_staging"": {""before_marker"": $beforeMarker, ""after_marker"": 0}}"
    [System.IO.File]::WriteAllText((Join-Path $evDir 'integration-summary.json'), $summaryJson)

    # evidence-sha.txt の生成.
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
        # 不正行と重複を追加.
        $shaLines += 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  non-existent.txt'
    }
    [System.IO.File]::WriteAllLines((Join-Path $evDir 'evidence-sha.txt'), $shaLines)

    Write-Host 'backup integration suite: SUCCESS'
    exit 0
}

exit 0
'@

[System.IO.File]::WriteAllText($mockBashPs1, $mockBashPs1Content, $script:Utf8NoBom)

# .bat ラッパー作成.
$mvnBatContent = "@echo off`r`n`"$currentPsExe`" -ExecutionPolicy Bypass -File `"$mockMvnPs1`" -ConfigDir `"$testBaseDir`" -RepoRoot `"$repoRoot`" %*`r`nexit /b %ERRORLEVEL%`r`n"
[System.IO.File]::WriteAllText($mockMvnBat, $mvnBatContent, [System.Text.Encoding]::ASCII)

$bashBatContent = "@echo off`r`n`"$currentPsExe`" -ExecutionPolicy Bypass -File `"$mockBashPs1`" -ConfigDir `"$testBaseDir`" -RepoRoot `"$repoRoot`" %*`r`nexit /b %ERRORLEVEL%`r`n"
[System.IO.File]::WriteAllText($mockBashBat, $bashBatContent, [System.Text.Encoding]::ASCII)

try {
    # --------------------------------------------------------------------------
    # 1. 門禁縮減パラメータの拒絶 (-Dtest, -DskipTests, -P)
    # --------------------------------------------------------------------------
    Write-Host "Test 01: 縮減Mavenパラメータの拒絶検証..."
    $forbiddenTests = @(
        @('-Dtest=SampleTest'),
        @('-DskipTests'),
        @('-Pmysql-tests')
    )
    foreach ($fArg in $forbiddenTests) {
        $ev01 = Join-Path $testBaseDir ("ev-forbidden-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 6))
        $proc01 = Start-Process -FilePath $currentPsExe `
            -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev01`"", "-MavenArgs", ($fArg -join ' ')) `
            -NoNewWindow -Wait -PassThru

        Assert-Test ($proc01.ExitCode -eq 1) "01.パラメータ拒絶 ($($fArg -join ' ')) - 終了コード." "ExitCode が 1 であること."
        Assert-Test (-not (Test-Path -LiteralPath $ev01)) "01.パラメータ拒絶 - 証跡作成前中止." "EvidenceDir が作成される前に中止されること."
    }

    # --------------------------------------------------------------------------
    # 2. TargetedRun 区分 (非 FULL_GATE 判定)
    # --------------------------------------------------------------------------
    Write-Host "Test 02: TargetedRun モードの非FULL_GATE判定検証..."
    $ev02 = Join-Path $testBaseDir 'ev-targeted'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $proc02 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev02`"", "-TargetedRun", "-MavenArgs", "-Dtest=SampleTest") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc02.ExitCode -eq 0) "02.TargetedRun - 終了コード." "ExitCode が 0 であること."
    $sess02 = [System.IO.File]::ReadAllText((Join-Path $ev02 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess02.gateType -eq 'TARGETED_NON_FULL_GATE') "02.TargetedRun - gateType判定." "gateType が TARGETED_NON_FULL_GATE であること."
    Assert-Test ($sess02.state -eq 'TARGETED_PASS_NOT_FULL_GATE') "02.TargetedRun - state判定." "state が TARGETED_PASS_NOT_FULL_GATE (非PASS) であること."

    # --------------------------------------------------------------------------
    # 3. dirty 作業ツリーの拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 03: 未コミット・未追跡ソース変更の拒絶検証..."
    $dirtyFile = Join-Path $repoRoot 'src\dirty-test-marker.txt'
    [System.IO.File]::WriteAllText($dirtyFile, 'dirty content')
    try {
        $ev03 = Join-Path $testBaseDir 'ev-dirty'
        $proc03 = Start-Process -FilePath $currentPsExe `
            -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev03`"") `
            -NoNewWindow -Wait -PassThru
        Assert-Test ($proc03.ExitCode -eq 1) "03.dirty検知 - 終了コード." "未追跡ファイル存在時に ExitCode 1 で拒絶されること."
    } finally {
        Remove-Item -LiteralPath $dirtyFile -Force -ErrorAction SilentlyContinue
    }

    # --------------------------------------------------------------------------
    # 4. 初回 clean 前の旧 target 保全検証.
    # --------------------------------------------------------------------------
    Write-Host "Test 04: 初回 clean 前の旧 target 保全検証..."
    $targetDir = Join-Path $repoRoot 'target'
    [System.IO.Directory]::CreateDirectory($targetDir) | Out-Null
    $oldTargetFile = Join-Path $targetDir 'old-build-artifact.txt'
    [System.IO.File]::WriteAllText($oldTargetFile, 'original target data')

    $ev04 = Join-Path $testBaseDir 'ev-target-preservation'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')

    $proc04 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev04`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc04.ExitCode -eq 0) "04.target保全 - 終了コード." "ExitCode が 0 であること."
    $preservedOld = Join-Path $ev04 'pre-run-target-preservation\old-build-artifact.txt'
    Assert-Test (Test-Path -LiteralPath $preservedOld) "04.target保全 - ファイル存在." "旧ファイルが pre-run-target-preservation に保全されること."
    $presShaManifest = Join-Path $ev04 'pre-run-target-preservation\sha256sum.txt'
    Assert-Test (Test-Path -LiteralPath $presShaManifest) "04.target保全 - ハッシュ一覧存在." "保全ディレクトリに sha256sum.txt が存在すること."

    # --------------------------------------------------------------------------
    # 5. 非ゼロ終了コードによる中断 & 後続 clean 抑止検証.
    # --------------------------------------------------------------------------
    Write-Host "Test 05: 非ゼロ終了コードと後続 clean 抑止検証..."
    [System.IO.File]::WriteAllText($mockCallsLog, '') # ログ初期化.
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'fail-exit')

    $ev05 = Join-Path $testBaseDir 'ev-fail-exit'
    $proc05 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev05`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc05.ExitCode -eq 1) "05.非ゼロ終了 - 終了コード." "ExitCode が 1 であること."
    $calls05 = @(Get-Content -LiteralPath $mockCallsLog)
    $cleanCalls05 = @($calls05 | Where-Object { $_ -like '*clean*' })
    Assert-Test ($cleanCalls05.Count -eq 1) "05.非ゼロ終了 - clean呼び出し抑止." "clean の実行回数が1回（第1スイートのみ）で後続が抑止されること."

    $sess05 = [System.IO.File]::ReadAllText((Join-Path $ev05 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess05.state -eq 'FAIL') "05.非ゼロ終了 - セッション状態." "セッション状態が FAIL であること."

    # --------------------------------------------------------------------------
    # 6. ゼロテスト実行の拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 06: ゼロテスト実行の拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'zero-tests')
    $ev06 = Join-Path $testBaseDir 'ev-zero-tests'
    $proc06 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev06`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc06.ExitCode -eq 1) "06.ゼロテスト - 終了コード." "ExitCode が 1 であること."
    $sess06 = [System.IO.File]::ReadAllText((Join-Path $ev06 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess06.state -eq 'FAIL') "06.ゼロテスト - セッション状態." "セッション状態が FAIL であること."

    # --------------------------------------------------------------------------
    # 7. XML欠落の拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 07: XML欠落の拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'missing-xml')
    $ev07 = Join-Path $testBaseDir 'ev-missing-xml'
    $proc07 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev07`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc07.ExitCode -eq 1) "07.XML欠落 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 8. 破損XMLの拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 08: 破損XMLの拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'corrupt-xml')
    $ev08 = Join-Path $testBaseDir 'ev-corrupt-xml'
    $proc08 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev08`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc08.ExitCode -eq 1) "08.破損XML - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 9. skipテストの拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 09: skipテストの拒絶検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'skipped-tests')
    $ev09 = Join-Path $testBaseDir 'ev-skipped'
    $proc09 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev09`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc09.ExitCode -eq 1) "09.skip検知 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 10. Shardクラス欠落検知 (mysql-tests 必須実行集合照合)
    # --------------------------------------------------------------------------
    Write-Host "Test 10: Shardクラス欠落検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'missing-shard-class')
    $ev10 = Join-Path $testBaseDir 'ev-missing-shard'
    $proc10 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev10`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc10.ExitCode -eq 1) "10.Shard欠落 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 11. dumpファイル存在時の一票否決.
    # --------------------------------------------------------------------------
    Write-Host "Test 11: dumpファイル存在時の一票否決検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'dump-file')
    $ev11 = Join-Path $testBaseDir 'ev-dump'
    $proc11 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev11`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc11.ExitCode -eq 1) "11.dump検知 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 12. 不正manifest検知 (PITR)
    # --------------------------------------------------------------------------
    Write-Host "Test 12: PITR不正manifest検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'bad-manifest')
    $ev12 = Join-Path $testBaseDir 'ev-bad-manifest'
    $proc12 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev12`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc12.ExitCode -eq 1) "12.不正manifest - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 13. 不正marker検知 (PITR)
    # --------------------------------------------------------------------------
    Write-Host "Test 13: PITR不正marker検知検証..."
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'bad-marker')
    $ev13 = Join-Path $testBaseDir 'ev-bad-marker'
    $proc13 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev13`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc13.ExitCode -eq 1) "13.不正marker - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 14. 残留 RUNNING 状態の INCOMPLETE 判定.
    # --------------------------------------------------------------------------
    Write-Host "Test 14: 残留 RUNNING 状態の INCOMPLETE 判定検証..."
    $ev14 = Join-Path $testBaseDir 'ev-incomplete'
    [System.IO.Directory]::CreateDirectory($ev14) | Out-Null
    Write-JsonStateFile -FilePath (Join-Path $ev14 'session-state.json') -Data @{
        state     = 'RUNNING'
        completed = $false
    }
    $state14 = Get-GateExecutionState -EvidenceDir $ev14
    Assert-Test ($state14 -eq 'INCOMPLETE') "14.RUNNING残留 - 状態判定." "Get-GateExecutionState が INCOMPLETE を返すこと."

    # --------------------------------------------------------------------------
    # 15. 旧証跡ディレクトリ再利用の拒絶.
    # --------------------------------------------------------------------------
    Write-Host "Test 15: 既存証跡ディレクトリ再利用拒絶検証..."
    $ev15 = Join-Path $testBaseDir 'ev-existing'
    [System.IO.Directory]::CreateDirectory($ev15) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $ev15 'old.txt'), 'data')

    $proc15 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev15`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc15.ExitCode -eq 1) "15.ディレクトリ再利用拒絶 - 終了コード." "ExitCode が 1 であること."

    # --------------------------------------------------------------------------
    # 16. 空白を含む証跡パスの処理.
    # --------------------------------------------------------------------------
    Write-Host "Test 16: 空白を含む証跡パス検証..."
    $ev16 = Join-Path $testBaseDir 'evidence with spaces'
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $proc16 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev16`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc16.ExitCode -eq 0) "16.空白パス - 終了コード." "ExitCode が 0 であること."
    Assert-Test (Test-Path -LiteralPath (Join-Path $ev16 'session-state.json')) "16.空白パス - session-state存在." "空白パス配下に証跡が生成されること."

    # --------------------------------------------------------------------------
    # 17. 秘密情報漏洩スキャン検知.
    # --------------------------------------------------------------------------
    Write-Host "Test 17: 秘密情報漏洩スキャン検知検証..."
    $ev17 = Join-Path $testBaseDir 'ev-secret'
    [System.IO.Directory]::CreateDirectory($ev17) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $ev17 'leaked.txt'), 'contains src-password inside')
    $leakDetected = $false
    try {
        Scan-EvidenceDirectoryForSecrets -EvidenceDir $ev17
    } catch {
        if ($_.Exception.Message -like "*秘密情報が検出されました*") {
            $leakDetected = $true
        }
    }
    Assert-Test $leakDetected "17.秘密情報スキャン - 検知." "src-password の混入が検知されること."

    # --------------------------------------------------------------------------
    # 18. 正常無dump全通過 (Full Pass)
    # --------------------------------------------------------------------------
    Write-Host "Test 18: 正常全通過 (Full Pass) 検証..."
    [System.IO.File]::WriteAllText($mockCallsLog, '') # ログ初期化.
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'mvn-mode.txt'), 'pass')
    [System.IO.File]::WriteAllText((Join-Path $testBaseDir 'bash-mode.txt'), 'pass')

    $ev18 = Join-Path $testBaseDir 'ev-full-pass'
    $proc18 = Start-Process -FilePath $currentPsExe `
        -ArgumentList @("-ExecutionPolicy", "Bypass", "-File", "`"$runnerScript`"", "-MavenExecutable", "`"$mockMvnBat`"", "-BashExecutable", "`"$mockBashBat`"", "-EvidenceDir", "`"$ev18`"") `
        -NoNewWindow -Wait -PassThru

    Assert-Test ($proc18.ExitCode -eq 0) "18.全通過 - 終了コード." "ExitCode が 0 であること."
    $sess18 = [System.IO.File]::ReadAllText((Join-Path $ev18 'session-state.json'), [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($sess18.state -eq 'PASS') "18.全通過 - state判定." "state が PASS であること."
    Assert-Test ($sess18.completed -eq $true) "18.全通過 - completed判定." "completed が true であること."

    $calls18 = @(Get-Content -LiteralPath $mockCallsLog)
    $cleanCalls18 = @($calls18 | Where-Object { $_ -like '*clean*' })
    Assert-Test ($cleanCalls18.Count -eq 4) "18.全通過 - clean呼び出し回数." "全4スイート分 (4回) clean が呼び出されること."

    # 交付ツリー内に秘密情報が無いこと.
    $noSecrets = $true
    try {
        Scan-EvidenceDirectoryForSecrets -EvidenceDir $ev18
    } catch {
        $noSecrets = $false
    }
    Assert-Test $noSecrets "18.全通過 - 交付ツリー無秘密." "最終交付ツリーに秘密情報が存在しないこと."

    Write-Host "==============================================================================."
    Write-Host "全18項目の軽快故障・合取判定テストがすべて PASS しました (0 FAILURES)."
    Write-Host "==============================================================================."
} catch {
    Write-Error "テストハーネス実行中に致命的エラーが発生しました: $($_.Exception.Message)."
    Write-Host "検証失敗のため作業領域を保全します: $testBaseDir."
    exit 1
}

# 全断言通過時のみ一時ディレクトリをクリーンアップ.
Remove-Item -LiteralPath $testBaseDir -Recurse -Force -ErrorAction SilentlyContinue
exit 0
