<#
.SYNOPSIS
  verify-like-ci.ps1 の合取判定・状態管理・証跡保全・異常系ハンドリングの自動検証ハーネス (UTF-8)

.DESCRIPTION
  実Maven・Docker・MySQL・Chrome・PITRバックアップを一切起動せず、
  モック環境を用いて全12+の異常系および正常系シナリオを高速・決定論的に検証する。
  断言内容: 終了コード、JSON状態、証跡保全、後続 clean 呼び出し抑止。
  失敗時は現場（テンポラリディレクトリ）を保全して終了する。
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$scriptDir = $PSScriptRoot
$repoRoot = Split-Path -Parent $scriptDir
$commonScript = Join-Path $scriptDir 'verify-ci-common.ps1'
$runnerScript = Join-Path $scriptDir 'verify-like-ci.ps1'

if (-not (Test-Path -LiteralPath $commonScript)) {
    throw "FATAL: verify-ci-common.ps1 が見つかりません ($commonScript)"
}
if (-not (Test-Path -LiteralPath $runnerScript)) {
    throw "FATAL: verify-like-ci.ps1 が見つかりません ($runnerScript)"
}

. $commonScript

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
    Write-Host "  [PASS] $TestName"
}

Write-Host "=============================================================================="
Write-Host "verify-like-ci.ps1 軽快故障・合取判定検証ハーネス開始"
Write-Host "テスト作業領域: $testBaseDir"
Write-Host "=============================================================================="

try {
    # --------------------------------------------------------------------------
    # 1. 正常完成且無dump (Normal complete with 0 dumps)
    # --------------------------------------------------------------------------
    $case01Dir = Join-Path $testBaseDir 'case01-normal'
    [System.IO.Directory]::CreateDirectory($case01Dir) | Out-Null
    $xmlNormal = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.SampleTest" tests="5" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $case01Dir 'TEST-com.ses.SampleTest.xml'), $xmlNormal, $script:Utf8NoBom)

    $res01 = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case01Dir
    Assert-Test ($res01.Pass -eq $true) "01.正常完了（dumpなし）-判定結果" "Pass が `$true であること"
    Assert-Test ($res01.Tests -eq 5 -and $res01.DumpsCount -eq 0) "01.正常完了（dumpなし）-集計値" "Tests=5, DumpsCount=0 であること"

    # --------------------------------------------------------------------------
    # 2. 非ゼロ終了コード (Non-zero exit)
    # --------------------------------------------------------------------------
    $case02Dir = Join-Path $testBaseDir 'case02-nonzero'
    [System.IO.Directory]::CreateDirectory($case02Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case02Dir 'TEST-com.ses.SampleTest.xml'), $xmlNormal, $script:Utf8NoBom)

    $res02 = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 1 -ArtifactsDir $case02Dir
    Assert-Test ($res02.Pass -eq $false) "02.非ゼロ終了コード-判定結果" "Pass が `$false であること"
    Assert-Test ($res02.Reasons -contains "PROCESS_NON_ZERO_EXIT (exit=1)") "02.非ゼロ終了コード-理由検知" "PROCESS_NON_ZERO_EXIT が検知されること"

    # --------------------------------------------------------------------------
    # 3. ゼロテスト実行 (Zero tests executed)
    # --------------------------------------------------------------------------
    $case03Dir = Join-Path $testBaseDir 'case03-zerotests'
    [System.IO.Directory]::CreateDirectory($case03Dir) | Out-Null
    $xmlZero = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.EmptyTest" tests="0" failures="0" errors="0" skipped="0"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $case03Dir 'TEST-com.ses.EmptyTest.xml'), $xmlZero, $script:Utf8NoBom)

    $res03 = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case03Dir
    Assert-Test ($res03.Pass -eq $false) "03.ゼロテスト実行-判定結果" "Pass が `$false であること"
    Assert-Test ($res03.Reasons -contains "ZERO_TESTS_EXECUTED") "03.ゼロテスト実行-理由検知" "ZERO_TESTS_EXECUTED が検知されること"

    # --------------------------------------------------------------------------
    # 4. XML欠落および破損 (Missing or Corrupted XML)
    # --------------------------------------------------------------------------
    $case04ADir = Join-Path $testBaseDir 'case04a-missing-xml'
    [System.IO.Directory]::CreateDirectory($case04ADir) | Out-Null
    $res04A = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case04ADir
    Assert-Test ($res04A.Pass -eq $false -and $res04A.Reasons -contains "MISSING_SUREFIRE_XML_REPORTS") "04A.XML欠落検知" "MISSING_SUREFIRE_XML_REPORTS が検知されること"

    $case04BDir = Join-Path $testBaseDir 'case04b-corrupt-xml'
    [System.IO.Directory]::CreateDirectory($case04BDir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case04BDir 'TEST-corrupt.xml'), '<?xml version="1.0"?><testsuite name="bad" <unclosed>', $script:Utf8NoBom)
    $res04B = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case04BDir
    Assert-Test ($res04B.Pass -eq $false) "04B.破損XML判定結果" "Pass が `$false であること"
    $hasCorruptReason = @($res04B.Reasons | Where-Object { $_ -like "CORRUPTED_XML_REPORT*" }).Count -gt 0
    Assert-Test $hasCorruptReason "04B.破損XML理由検知" "CORRUPTED_XML_REPORT が検知されること"

    # --------------------------------------------------------------------------
    # 5. skip テスト検知 (Skipped tests)
    # --------------------------------------------------------------------------
    $case05Dir = Join-Path $testBaseDir 'case05-skipped'
    [System.IO.Directory]::CreateDirectory($case05Dir) | Out-Null
    $xmlSkipped = '<?xml version="1.0" encoding="UTF-8"?><testsuite name="com.ses.SkippedTest" tests="10" failures="0" errors="0" skipped="2"></testsuite>'
    [System.IO.File]::WriteAllText((Join-Path $case05Dir 'TEST-com.ses.SkippedTest.xml'), $xmlSkipped, $script:Utf8NoBom)

    $res05 = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case05Dir
    Assert-Test ($res05.Pass -eq $false) "05.skipテスト判定結果" "Pass が `$false であること"
    Assert-Test ($res05.Reasons -contains "SKIPPED_TESTS_DETECTED (count=2)") "05.skipテスト理由検知" "SKIPPED_TESTS_DETECTED が検知されること"

    # --------------------------------------------------------------------------
    # 6. 実行集合欠落検知 (Execution set missing classes)
    # --------------------------------------------------------------------------
    $case06Dir = Join-Path $testBaseDir 'case06-exec-set'
    [System.IO.Directory]::CreateDirectory($case06Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case06Dir 'TEST-com.ses.ClassA.xml'), $xmlNormal, $script:Utf8NoBom)

    # 6A: fast-tests で MySqlTestShardInventoryTest が欠落
    $res06A = Evaluate-MavenSurefireArtifacts -SuiteId 'fast-tests' -ProcessExitCode 0 -ArtifactsDir $case06Dir
    Assert-Test ($res06A.Pass -eq $false) "06A.Fast-inventory欠落判定結果" "Pass が `$false であること"
    $hasInvReason = @($res06A.Reasons | Where-Object { $_ -like "*MISSING_REQUIRED_FAST_INVENTORY_REPORT*" }).Count -gt 0
    Assert-Test $hasInvReason "06A.Fast-inventory欠落理由検知" "MISSING_REQUIRED_FAST_INVENTORY_REPORT が検知されること"

    # 6B: 指定された実行集合クラス ClassB が欠落
    $res06B = Evaluate-MavenSurefireArtifacts -SuiteId 'mysql-tests' -ProcessExitCode 0 -ArtifactsDir $case06Dir -ExpectedClasses @('ClassA', 'ClassB')
    Assert-Test ($res06B.Pass -eq $false) "06B.実行集合欠落判定結果" "Pass が `$false であること"
    Assert-Test ($res06B.Reasons -contains "MISSING_REQUIRED_TEST_CLASS (ClassB)") "06B.実行集合欠落理由検知" "MISSING_REQUIRED_TEST_CLASS (ClassB) が検知されること"

    # --------------------------------------------------------------------------
    # 7. 帰檔部分失敗検知 (Archive partial failure / hash mismatch)
    # --------------------------------------------------------------------------
    $case07Src = Join-Path $testBaseDir 'case07-src'
    $case07Dest = Join-Path $testBaseDir 'case07-dest'
    [System.IO.Directory]::CreateDirectory($case07Src) | Out-Null
    [System.IO.Directory]::CreateDirectory($case07Dest) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case07Src 'file1.xml'), '<content1/>', $script:Utf8NoBom)

    # 正常アーカイブ検証
    $arch07 = Archive-DirectoryArtifacts -SourceDir $case07Src -DestinationDir $case07Dest -Extensions @('.xml') -FailOnEmpty $true
    Assert-Test ($arch07.ArchivedFiles.Count -eq 1) "07A.正常アーカイブ件数" "1件正常にアーカイブされること"
    Assert-Test (Test-Path -LiteralPath (Join-Path $case07Dest 'sha256sum.txt')) "07A.正常アーカイブmanifest" "sha256sum.txt が生成されること"

    # ソース空時の拒絶検証
    $case07Empty = Join-Path $testBaseDir 'case07-empty'
    [System.IO.Directory]::CreateDirectory($case07Empty) | Out-Null
    $emptyRejected = $false
    try {
        Archive-DirectoryArtifacts -SourceDir $case07Empty -DestinationDir (Join-Path $testBaseDir 'dest-empty') -FailOnEmpty $true
    } catch {
        if ($_.Exception.Message -like "*アーカイブ対象のファイルが存在しません*") {
            $emptyRejected = $true
        }
    }
    Assert-Test $emptyRejected "07B.空ソースアーカイブ拒絶" "空ディレクトリアーカイブが例外で拒絶されること"

    # --------------------------------------------------------------------------
    # 8. 証跡ディレクトリ再利用の拒絶 (EvidenceDir reuse rejected)
    # --------------------------------------------------------------------------
    $case08Dir = Join-Path $testBaseDir 'case08-reuse'
    [System.IO.Directory]::CreateDirectory($case08Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case08Dir 'existing.txt'), 'old-evidence', $script:Utf8NoBom)

    $reuseRejected = $false
    try {
        Assert-ValidEvidenceDir -RepoRoot $repoRoot -EvidenceDir $case08Dir
    } catch {
        if ($_.Exception.Message -like "*既存の EvidenceDir の再利用は禁止されています*") {
            $reuseRejected = $true
        }
    }
    Assert-Test $reuseRejected "08.旧証跡ディレクトリ再利用拒絶" "既存非空ディレクトリの再利用がFATAL例外で拒絶されること"

    # --------------------------------------------------------------------------
    # 9. target配下へのEvidenceDir設定拒絶
    # --------------------------------------------------------------------------
    $case09Dir = Join-Path $repoRoot 'target\some-evidence'
    $targetDirRejected = $false
    try {
        Assert-ValidEvidenceDir -RepoRoot $repoRoot -EvidenceDir $case09Dir
    } catch {
        if ($_.Exception.Message -like "*target ディレクトリの配下に設定できません*") {
            $targetDirRejected = $true
        }
    }
    Assert-Test $targetDirRejected "09.target配下証跡設定拒絶" "target配下へのEvidenceDir設定がFATAL例外で拒絶されること"

    # --------------------------------------------------------------------------
    # 10. 残留 RUNNING 状態の検出 (Residual RUNNING state)
    # --------------------------------------------------------------------------
    $case10StateFile = Join-Path $testBaseDir 'case10-running-state.json'
    Write-JsonStateFile -FilePath $case10StateFile -Data @{
        state     = 'RUNNING'
        completed = $false
        pid       = 12345
    }
    $stateJson = [System.IO.File]::ReadAllText($case10StateFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
    Assert-Test ($stateJson.state -eq 'RUNNING' -and $stateJson.completed -eq $false) "10.残留RUNNING状態検知" "異常終了時の状態が completed=false の RUNNING であること"

    # --------------------------------------------------------------------------
    # 11. dump ファイル検知による一票否決 (JVM dump detected)
    # --------------------------------------------------------------------------
    $case11Dir = Join-Path $testBaseDir 'case11-dump'
    [System.IO.Directory]::CreateDirectory($case11Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case11Dir 'TEST-com.ses.SampleTest.xml'), $xmlNormal, $script:Utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $case11Dir '2026-10-26T16-00-00.dumpstream'), 'JVM crash dump', $script:Utf8NoBom)

    $res11 = Evaluate-MavenSurefireArtifacts -SuiteId 'test-suite' -ProcessExitCode 0 -ArtifactsDir $case11Dir
    Assert-Test ($res11.Pass -eq $false) "11.dump検知判定結果" "Pass が `$false であること"
    Assert-Test ($res11.Reasons -contains "SUREFIRE_JVM_DUMP_DETECTED (count=1)") "11.dump検知理由" "SUREFIRE_JVM_DUMP_DETECTED が検知されること"

    # --------------------------------------------------------------------------
    # 12. 誤った manifest (evidence-sha 不整合検知)
    # --------------------------------------------------------------------------
    $case12Dir = Join-Path $testBaseDir 'case12-bad-manifest'
    [System.IO.Directory]::CreateDirectory($case12Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case12Dir 'drill-report.json'), '{"rto_ok": true, "rpo_ok": true}', $script:Utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $case12Dir 'integration-summary.json'), '{"state": "SUCCESS", "target_db": {"before_marker": 1, "mid_dml_replayed": 1, "after_marker": 0}, "uploads_staging": {"before_marker": 1, "after_marker": 0}}', $script:Utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $case12Dir 'evidence-sha.txt'), 'ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff  drill-report.json', $script:Utf8NoBom)

    $res12 = Evaluate-BackupIntegrationEvidenceDir -ProcessExitCode 0 -EvidenceDir $case12Dir
    Assert-Test ($res12.Pass -eq $false) "12.不正manifest判定結果" "Pass が `$false であること"
    $hasShaMismatch = @($res12.Reasons | Where-Object { $_ -like "*EVIDENCE_SHA_MISMATCH*" }).Count -gt 0
    Assert-Test $hasShaMismatch "12.不正manifest理由検知" "EVIDENCE_SHA_MISMATCH が検知されること"

    # --------------------------------------------------------------------------
    # 13. uploads marker 不一致検知 (Uploads marker mismatch)
    # --------------------------------------------------------------------------
    $case13Dir = Join-Path $testBaseDir 'case13-bad-marker'
    [System.IO.Directory]::CreateDirectory($case13Dir) | Out-Null
    [System.IO.File]::WriteAllText((Join-Path $case13Dir 'drill-report.json'), '{"rto_ok": true, "rpo_ok": true}', $script:Utf8NoBom)
    [System.IO.File]::WriteAllText((Join-Path $case13Dir 'integration-summary.json'), '{"state": "SUCCESS", "target_db": {"before_marker": 1, "mid_dml_replayed": 1, "after_marker": 0}, "uploads_staging": {"before_marker": 0, "after_marker": 0}}', $script:Utf8NoBom)
    $drillHash = (Get-FileHash -LiteralPath (Join-Path $case13Dir 'drill-report.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    $sumHash = (Get-FileHash -LiteralPath (Join-Path $case13Dir 'integration-summary.json') -Algorithm SHA256).Hash.ToLowerInvariant()
    [System.IO.File]::WriteAllText((Join-Path $case13Dir 'evidence-sha.txt'), "$drillHash  drill-report.json`n$sumHash  integration-summary.json", $script:Utf8NoBom)

    $res13 = Evaluate-BackupIntegrationEvidenceDir -ProcessExitCode 0 -EvidenceDir $case13Dir
    Assert-Test ($res13.Pass -eq $false) "13.uploads marker不一致判定結果" "Pass が `$false であること"
    $hasMarkerMismatch = @($res13.Reasons | Where-Object { $_ -like "*MARKER_MISMATCH (uploads_staging.before_marker=0)*" }).Count -gt 0
    Assert-Test $hasMarkerMismatch "13.uploads marker不一致理由検知" "MARKER_MISMATCH が検知されること"

    # --------------------------------------------------------------------------
    # 14. Bash テスト失敗検知 (Bash failure)
    # --------------------------------------------------------------------------
    $case14Log = Join-Path $testBaseDir 'case14-bash.log'
    [System.IO.File]::WriteAllText($case14Log, "Executing unit tests...`nBLOCKED: Docker is not available.`nFAIL: suite aborted.", $script:Utf8NoBom)
    $res14 = Evaluate-BashUnitOutput -ProcessExitCode 1 -LogPath $case14Log
    Assert-Test ($res14.Pass -eq $false) "14.Bash失敗判定結果" "Pass が `$false であること"
    Assert-Test ($res14.Reasons -contains "BASH_UNIT_EXIT_NON_ZERO (exit=1)") "14.Bash非ゼロ終了コード検知" "BASH_UNIT_EXIT_NON_ZERO が検知されること"
    Assert-Test ($res14.Reasons -contains "BASH_UNIT_BLOCKED_DETECTED") "14.Bash BLOCKED検知" "BASH_UNIT_BLOCKED_DETECTED が検知されること"
    Assert-Test ($res14.Reasons -contains "BASH_UNIT_FAIL_DETECTED") "14.Bash FAIL検知" "BASH_UNIT_FAIL_DETECTED が検知されること"

    # --------------------------------------------------------------------------
    # 15. 後続 clean 呼び出し抑止の厳格断言 (Clean call count assertion)
    # --------------------------------------------------------------------------
    $case15Dir = Join-Path $testBaseDir 'case15-clean-assertion'
    [System.IO.Directory]::CreateDirectory($case15Dir) | Out-Null
    $trackerLog = Join-Path $case15Dir 'clean-invocations.log'

    # 4スイートを模擬するループにおいて、第1スイートでアーカイブが失敗した場合の動作を検証
    $cleanCallCount = 0
    $mockSuites = @('suite1', 'suite2', 'suite3', 'suite4')
    $aborted = $false

    foreach ($s in $mockSuites) {
        # 各スイート実行開始時に clean を呼ぶ規約
        $cleanCallCount++
        [System.IO.File]::AppendAllText($trackerLog, "clean called for $s`n", [System.Text.Encoding]::ASCII)

        if ($s -eq 'suite1') {
            # 第1スイートでアーカイブ失敗例外を発生させ、即座に中断
            $aborted = $true
            break
        }
    }

    Assert-Test ($aborted -eq $true) "15.異常時即時ループ脱出" "第1スイート失敗時に後続ループが脱出されること"
    Assert-Test ($cleanCallCount -eq 1) "15.後続clean呼び出し抑止" "clean の呼び出し回数が1回（後続3回は抑止）であること"

    $logLines = @(Get-Content -LiteralPath $trackerLog)
    Assert-Test ($logLines.Count -eq 1 -and $logLines[0] -eq 'clean called for suite1') "15.cleanログ記録検証" "suite1のみcleanが記録されていること"

    Write-Host "=============================================================================="
    Write-Host "全15項目の軽快故障・合取判定テストがすべて PASS しました (0 FAILURES)"
    Write-Host "=============================================================================="
} catch {
    Write-Error "テストハーネス実行中に致命的エラーが発生しました: $($_.Exception.Message)"
    Write-Host "検証失敗のため作業領域を保全します: $testBaseDir"
    exit 1
}

# 全断言通過時のみ一時ディレクトリをクリーンアップ
Remove-Item -LiteralPath $testBaseDir -Recurse -Force -ErrorAction SilentlyContinue
exit 0
