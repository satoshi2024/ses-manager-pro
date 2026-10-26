# ==============================================================================
# verify-ci-common.ps1
# CI 互換検証ランナー共通関数ライブラリ (UTF-8 without BOM)
# ==============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Get-StrictGitHead {
    param([string]$RepoRoot)
    $head = (& git -C $RepoRoot rev-parse HEAD 2>$null)
    if ([string]::IsNullOrWhiteSpace($head) -or ($head.Trim().Length -ne 40) -or ($head -notmatch '^[0-9a-f]{40}$')) {
        throw "FATAL: 有効な40文字のGit HEAD SHAを取得できませんでした (取得値: '$head')"
    }
    return $head.Trim()
}

function Assert-ValidEvidenceDir {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir
    )
    if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
        throw "FATAL: EvidenceDir が指定されていません"
    }

    $fullEvidencePath = [System.IO.Path]::GetFullPath($EvidenceDir)
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot 'target'))

    if ($fullEvidencePath.StartsWith($targetPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "FATAL: EvidenceDir は target ディレクトリの配下に設定できません (指定値: $fullEvidencePath)"
    }

    if (Test-Path -LiteralPath $fullEvidencePath) {
        $existingItems = @(Get-ChildItem -LiteralPath $fullEvidencePath -Force -ErrorAction SilentlyContinue)
        if ($existingItems.Count -gt 0) {
            throw "FATAL: 既存の EvidenceDir の再利用は禁止されています (指定値: $fullEvidencePath)"
        }
    }
}

function Write-JsonStateFile {
    param(
        [string]$FilePath,
        [hashtable]$Data
    )
    $parentDir = Split-Path -Parent $FilePath
    if (-not (Test-Path -LiteralPath $parentDir)) {
        [System.IO.Directory]::CreateDirectory($parentDir) | Out-Null
    }
    $json = $Data | ConvertTo-Json -Depth 8
    [System.IO.File]::WriteAllText($FilePath, $json, $script:Utf8NoBom)
}

function Archive-DirectoryArtifacts {
    param(
        [string]$SourceDir,
        [string]$DestinationDir,
        [string[]]$Extensions = @('.xml', '.txt', '.dump', '.dumpstream'),
        [bool]$FailOnEmpty = $true
    )
    if (-not (Test-Path -LiteralPath $DestinationDir)) {
        [System.IO.Directory]::CreateDirectory($DestinationDir) | Out-Null
    }

    if (-not (Test-Path -LiteralPath $SourceDir)) {
        if ($FailOnEmpty) {
            throw "FATAL: アーカイブ元ディレクトリが存在しません ($SourceDir)"
        }
        return @{
            ArchivedFiles = @()
            ManifestFile  = ''
        }
    }

    $srcFiles = @(Get-ChildItem -LiteralPath $SourceDir -File -ErrorAction Stop |
        Where-Object { $Extensions -contains $_.Extension.ToLowerInvariant() })

    if ($srcFiles.Count -eq 0 -and $FailOnEmpty) {
        throw "FATAL: アーカイブ対象のファイルが存在しません ($SourceDir)"
    }

    $archivedItems = @()
    foreach ($f in $srcFiles) {
        $destPath = Join-Path $DestinationDir $f.Name
        Copy-Item -LiteralPath $f.FullName -Destination $destPath -Force -ErrorAction Stop

        if (-not (Test-Path -LiteralPath $destPath)) {
            throw "FATAL: ファイルのコピーに失敗しました ($($f.FullName) -> $destPath)"
        }

        $srcHash = (Get-FileHash -LiteralPath $f.FullName -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
        $destHash = (Get-FileHash -LiteralPath $destPath -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()

        if ($srcHash -ne $destHash) {
            throw "FATAL: アーカイブ前後のSHA-256不一致を検知しました ($($f.Name): src=$srcHash, dest=$destHash)"
        }

        $archivedItems += [PSCustomObject]@{
            Name     = $f.Name
            Hash     = $destHash
            FullName = $destPath
        }
    }

    $destCheckFiles = @(Get-ChildItem -LiteralPath $DestinationDir -File -ErrorAction Stop |
        Where-Object { $Extensions -contains $_.Extension.ToLowerInvariant() })
    if ($destCheckFiles.Count -ne $srcFiles.Count) {
        throw "FATAL: アーカイブ件数不一致 (元: $($srcFiles.Count), 先: $($destCheckFiles.Count))"
    }

    $shaLines = @()
    foreach ($item in ($archivedItems | Sort-Object -Property Name)) {
        $shaLines += "$($item.Hash)  $($item.Name)"
    }
    $manifestPath = Join-Path $DestinationDir 'sha256sum.txt'
    [System.IO.File]::WriteAllLines($manifestPath, $shaLines, $script:Utf8NoBom)

    return @{
        ArchivedFiles = $archivedItems
        ManifestFile  = $manifestPath
    }
}

function Evaluate-MavenSurefireArtifacts {
    param(
        [string]$SuiteId,
        [int]$ProcessExitCode,
        [string]$ArtifactsDir,
        [string[]]$ExpectedClasses = @()
    )
    $reasons = @()

    if ($ProcessExitCode -ne 0) {
        $reasons += "PROCESS_NON_ZERO_EXIT (exit=$ProcessExitCode)"
    }

    $dumpFiles = @(Get-ChildItem -LiteralPath $ArtifactsDir -File -ErrorAction Stop |
        Where-Object { $_.Extension -in @('.dump', '.dumpstream') })
    if ($dumpFiles.Count -gt 0) {
        $reasons += "SUREFIRE_JVM_DUMP_DETECTED (count=$($dumpFiles.Count))"
    }

    $xmlFiles = @(Get-ChildItem -LiteralPath $ArtifactsDir -File -Filter 'TEST-*.xml' -ErrorAction Stop)
    if ($xmlFiles.Count -eq 0) {
        $reasons += "MISSING_SUREFIRE_XML_REPORTS"
    }

    $totalTests = 0
    $totalFailures = 0
    $totalErrors = 0
    $totalSkipped = 0

    foreach ($xmlFile in $xmlFiles) {
        try {
            $rawContent = [System.IO.File]::ReadAllText($xmlFile.FullName, [System.Text.Encoding]::UTF8)
            [xml]$xmlDoc = $rawContent
            if ($null -eq $xmlDoc.testsuite) {
                $reasons += "INVALID_XML_ROOT_NOT_TESTSUITE ($($xmlFile.Name))"
                continue
            }
            $totalTests += [int]$xmlDoc.testsuite.tests
            $totalFailures += [int]$xmlDoc.testsuite.failures
            $totalErrors += [int]$xmlDoc.testsuite.errors
            $totalSkipped += [int]$xmlDoc.testsuite.skipped
        } catch {
            $reasons += "CORRUPTED_XML_REPORT ($($xmlFile.Name))"
        }
    }

    if ($xmlFiles.Count -gt 0 -and $totalTests -le 0) {
        $reasons += "ZERO_TESTS_EXECUTED"
    }
    if ($totalFailures -gt 0) {
        $reasons += "FAILURES_DETECTED (count=$totalFailures)"
    }
    if ($totalErrors -gt 0) {
        $reasons += "ERRORS_DETECTED (count=$totalErrors)"
    }
    if ($totalSkipped -gt 0) {
        $reasons += "SKIPPED_TESTS_DETECTED (count=$totalSkipped)"
    }

    if ($SuiteId -eq 'fast-tests') {
        $inventoryXml = Join-Path $ArtifactsDir 'TEST-com.ses.migration.MySqlTestShardInventoryTest.xml'
        if (-not (Test-Path -LiteralPath $inventoryXml)) {
            $reasons += "MISSING_REQUIRED_FAST_INVENTORY_REPORT (TEST-com.ses.migration.MySqlTestShardInventoryTest.xml)"
        }
    }

    if ($ExpectedClasses.Count -gt 0) {
        foreach ($expected in $ExpectedClasses) {
            $matched = $false
            foreach ($x in $xmlFiles) {
                if ($x.Name -like "*$expected.xml") {
                    $matched = $true
                    break
                }
            }
            if (-not $matched) {
                $reasons += "MISSING_REQUIRED_TEST_CLASS ($expected)"
            }
        }
    }

    return @{
        Pass       = ($reasons.Count -eq 0)
        Reasons    = $reasons
        Tests      = $totalTests
        Failures   = $totalFailures
        Errors     = $totalErrors
        Skipped    = $totalSkipped
        DumpsCount = $dumpFiles.Count
    }
}

function Evaluate-BashUnitOutput {
    param(
        [int]$ProcessExitCode,
        [string]$LogPath
    )
    $reasons = @()
    if ($ProcessExitCode -ne 0) {
        $reasons += "BASH_UNIT_EXIT_NON_ZERO (exit=$ProcessExitCode)"
    }
    if (-not (Test-Path -LiteralPath $LogPath)) {
        $reasons += "MISSING_BASH_UNIT_LOG ($LogPath)"
    } else {
        $logContent = [System.IO.File]::ReadAllText($LogPath, [System.Text.Encoding]::UTF8)
        if ($logContent -match 'BLOCKED:') {
            $reasons += "BASH_UNIT_BLOCKED_DETECTED"
        }
        if ($logContent -match 'FAIL:') {
            $reasons += "BASH_UNIT_FAIL_DETECTED"
        }
    }
    return @{
        Pass    = ($reasons.Count -eq 0)
        Reasons = $reasons
    }
}

function Evaluate-BackupIntegrationEvidenceDir {
    param(
        [int]$ProcessExitCode,
        [string]$EvidenceDir
    )
    $reasons = @()
    if ($ProcessExitCode -ne 0) {
        $reasons += "BACKUP_INTEGRATION_EXIT_NON_ZERO (exit=$ProcessExitCode)"
    }

    $drillJsonPath = Join-Path $EvidenceDir 'drill-report.json'
    $summaryJsonPath = Join-Path $EvidenceDir 'integration-summary.json'
    $shaPath = Join-Path $EvidenceDir 'evidence-sha.txt'

    if (-not (Test-Path -LiteralPath $drillJsonPath)) {
        $reasons += "MISSING_DRILL_REPORT_JSON"
    }
    if (-not (Test-Path -LiteralPath $summaryJsonPath)) {
        $reasons += "MISSING_INTEGRATION_SUMMARY_JSON"
    }
    if (-not (Test-Path -LiteralPath $shaPath)) {
        $reasons += "MISSING_EVIDENCE_SHA_TXT"
    }

    if (Test-Path -LiteralPath $shaPath) {
        $shaLines = [System.IO.File]::ReadAllLines($shaPath, [System.Text.Encoding]::UTF8)
        if ($shaLines.Count -eq 0) {
            $reasons += "EMPTY_EVIDENCE_SHA_TXT"
        }
        foreach ($line in $shaLines) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $parts = -split $line
            if ($parts.Count -ge 2) {
                $expectedHash = $parts[0].ToLowerInvariant()
                $fileName = $parts[1]
                $targetFile = Join-Path $EvidenceDir $fileName
                if (-not (Test-Path -LiteralPath $targetFile)) {
                    $reasons += "EVIDENCE_SHA_FILE_NOT_FOUND ($fileName)"
                } else {
                    $actualHash = (Get-FileHash -LiteralPath $targetFile -Algorithm SHA256 -ErrorAction Stop).Hash.ToLowerInvariant()
                    if ($actualHash -ne $expectedHash) {
                        $reasons += "EVIDENCE_SHA_MISMATCH (${fileName}: exp=$expectedHash, act=$actualHash)"
                    }
                }
            }
        }
    }

    if (Test-Path -LiteralPath $drillJsonPath) {
        try {
            $drill = [System.IO.File]::ReadAllText($drillJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            if ($drill.rto_ok -ne $true -or $drill.rpo_ok -ne $true) {
                $reasons += "DRILL_RPO_RTO_NOT_OK (rto_ok=$($drill.rto_ok), rpo_ok=$($drill.rpo_ok))"
            }
        } catch {
            $reasons += "CORRUPTED_DRILL_REPORT_JSON"
        }
    }

    if (Test-Path -LiteralPath $summaryJsonPath) {
        try {
            $summary = [System.IO.File]::ReadAllText($summaryJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            if ($summary.state -ne 'SUCCESS') {
                $reasons += "INTEGRATION_STATE_NOT_SUCCESS (state=$($summary.state))"
            }
            if ($summary.target_db.before_marker -ne 1 -and $summary.target_db.before_marker -ne '1') {
                $reasons += "MARKER_MISMATCH (target_db.before_marker=$($summary.target_db.before_marker))"
            }
            if ($summary.target_db.mid_dml_replayed -ne 1 -and $summary.target_db.mid_dml_replayed -ne '1') {
                $reasons += "MARKER_MISMATCH (target_db.mid_dml_replayed=$($summary.target_db.mid_dml_replayed))"
            }
            if ($summary.target_db.after_marker -ne 0 -and $summary.target_db.after_marker -ne '0') {
                $reasons += "MARKER_MISMATCH (target_db.after_marker=$($summary.target_db.after_marker))"
            }
            if ($summary.uploads_staging.before_marker -ne 1 -and $summary.uploads_staging.before_marker -ne '1') {
                $reasons += "MARKER_MISMATCH (uploads_staging.before_marker=$($summary.uploads_staging.before_marker))"
            }
            if ($summary.uploads_staging.after_marker -ne 0 -and $summary.uploads_staging.after_marker -ne '0') {
                $reasons += "MARKER_MISMATCH (uploads_staging.after_marker=$($summary.uploads_staging.after_marker))"
            }
        } catch {
            $reasons += "CORRUPTED_INTEGRATION_SUMMARY_JSON"
        }
    }

    return @{
        Pass    = ($reasons.Count -eq 0)
        Reasons = $reasons
    }
}
