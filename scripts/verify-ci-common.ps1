# ==============================================================================
# verify-ci-common.ps1.
# CI 互換検証ランナー共通関数ライブラリ (UTF-8 without BOM).
# ==============================================================================

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:Utf8NoBom = New-Object System.Text.UTF8Encoding $false

function Get-Sha256Hex {
    param([string]$FilePath)
    $stream = [System.IO.File]::OpenRead($FilePath)
    try {
        $sha = [System.Security.Cryptography.SHA256]::Create()
        try {
            $hashBytes = $sha.ComputeHash($stream)
            return [System.BitConverter]::ToString($hashBytes).Replace('-', '').ToLowerInvariant()
        } finally {
            $sha.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

function Get-StrictGitHead {
    param([string]$RepoRoot)
    $head = (& git -C $RepoRoot rev-parse HEAD 2>$null)
    if ([string]::IsNullOrWhiteSpace($head) -or ($head.Trim().Length -ne 40) -or ($head.Trim() -notmatch '^[0-9a-f]{40}$')) {
        throw "FATAL: 有効な40文字のGit HEAD SHAを取得できませんでした (取得値: '$head')."
    }
    return $head.Trim()
}

function Assert-CleanWorkingTree {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir = ''
    )
    $statusOutput = @(& git -C $RepoRoot status --porcelain 2>$null)
    if ($statusOutput.Count -eq 0) {
        return
    }

    $targetFullPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot 'target'))
    $evidenceFullPath = ''
    if (-not [string]::IsNullOrWhiteSpace($EvidenceDir)) {
        $evidenceFullPath = [System.IO.Path]::GetFullPath($EvidenceDir)
    }

    $dirtyItems = @()
    foreach ($line in $statusOutput) {
        if ([string]::IsNullOrWhiteSpace($line)) { continue }
        $filePathRel = $line.Substring(3).Trim()
        # カンマやクォートで囲まれている場合のトリム.
        $filePathRel = $filePathRel.Trim('"')
        $itemFullPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $filePathRel))

        if ($itemFullPath.StartsWith($targetFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        if (-not [string]::IsNullOrWhiteSpace($evidenceFullPath) -and $itemFullPath.StartsWith($evidenceFullPath, [System.StringComparison]::OrdinalIgnoreCase)) {
            continue
        }
        $dirtyItems += $line
    }

    if ($dirtyItems.Count -gt 0) {
        $dirtyList = $dirtyItems -join "; "
        throw "FATAL: 未コミットまたは未追跡の変更が検出されました: $dirtyList."
    }
}

function Assert-ValidMavenArgs {
    param([string[]]$ArgsList)
    if ($null -eq $ArgsList -or $ArgsList.Count -eq 0) {
        return
    }
    $forbiddenPatterns = @(
        '-Dtest=',
        '-Dtest',
        '-Dgroups=',
        '-Dexcluded-groups=',
        '-DskipTests',
        '-Dmaven.test.skip',
        '-P',
        '-pl',
        '-rf'
    )
    foreach ($arg in $ArgsList) {
        foreach ($pattern in $forbiddenPatterns) {
            if ($arg.StartsWith($pattern, [System.StringComparison]::OrdinalIgnoreCase) -or ($arg -eq $pattern)) {
                throw "FATAL: 門禁選択を縮減・変更するMaven引数は禁止されています: '$arg'."
            }
        }
    }
}

function Get-RequiredMysqlClasses {
    param([string]$RepoRoot)
    $shardFiles = @(
        (Join-Path $RepoRoot 'scripts\test-suites\mysql-shard-1.txt'),
        (Join-Path $RepoRoot 'scripts\test-suites\mysql-shard-2.txt'),
        (Join-Path $RepoRoot 'scripts\test-suites\mysql-shard-3.txt')
    )
    $classes = @()
    foreach ($sf in $shardFiles) {
        if (-not (Test-Path -LiteralPath $sf)) {
            throw "FATAL: MySQL shard 定義ファイルが存在しません: '$sf'."
        }
        $lines = [System.IO.File]::ReadAllLines($sf, [System.Text.Encoding]::UTF8)
        foreach ($line in $lines) {
            $t = $line.Trim()
            if (-not [string]::IsNullOrWhiteSpace($t) -and -not $t.StartsWith('#')) {
                $classes += $t
            }
        }
    }
    if ($classes.Count -eq 0) {
        throw "FATAL: MySQL shard 定義からクラスを読み込めませんでした."
    }
    return $classes
}

function Assert-ValidEvidenceDir {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir
    )
    if ([string]::IsNullOrWhiteSpace($EvidenceDir)) {
        throw "FATAL: EvidenceDir が指定されていません."
    }

    $fullEvidencePath = [System.IO.Path]::GetFullPath($EvidenceDir)
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot 'target'))

    if ($fullEvidencePath.StartsWith($targetPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "FATAL: EvidenceDir は target 配下に設定できません (指定値: $fullEvidencePath)."
    }

    if (Test-Path -LiteralPath $fullEvidencePath) {
        $existingItems = @(Get-ChildItem -LiteralPath $fullEvidencePath -Force -ErrorAction SilentlyContinue)
        if ($existingItems.Count -gt 0) {
            throw "FATAL: 既存の EvidenceDir の再利用は禁止されています (指定値: $fullEvidencePath)."
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

function Preserve-InitialTarget {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir
    )
    $targetDir = Join-Path $RepoRoot 'target'
    if (-not (Test-Path -LiteralPath $targetDir)) {
        return @{ Preserved = $false; Count = 0 }
    }
    $targetFiles = @(Get-ChildItem -LiteralPath $targetDir -File -Recurse -ErrorAction Stop)
    if ($targetFiles.Count -eq 0) {
        return @{ Preserved = $false; Count = 0 }
    }

    $preservationDir = Join-Path $EvidenceDir 'pre-run-target-preservation'
    [System.IO.Directory]::CreateDirectory($preservationDir) | Out-Null

    $shaLines = @()
    foreach ($file in $targetFiles) {
        $relPath = $file.FullName.Substring($targetDir.Length).TrimStart('\', '/')
        $destPath = Join-Path $preservationDir $relPath
        $destFileParent = Split-Path -Parent $destPath
        if (-not (Test-Path -LiteralPath $destFileParent)) {
            [System.IO.Directory]::CreateDirectory($destFileParent) | Out-Null
        }
        Copy-Item -LiteralPath $file.FullName -Destination $destPath -Force -ErrorAction Stop

        $srcHash = Get-Sha256Hex -FilePath $file.FullName
        $destHash = Get-Sha256Hex -FilePath $destPath
        if ($srcHash -ne $destHash) {
            throw "FATAL: 初期 target 保全時のハッシュ不一致を検知しました: '$relPath'."
        }
        $normRel = $relPath -replace '\\', '/'
        $shaLines += "$destHash  $normRel"
    }

    $preservedFiles = @(Get-ChildItem -LiteralPath $preservationDir -File -Recurse -ErrorAction Stop)
    if ($preservedFiles.Count -ne $targetFiles.Count) {
        throw "FATAL: 初期 target 保全件数不一致 (元: $($targetFiles.Count), 先: $($preservedFiles.Count))."
    }

    $manifestPath = Join-Path $preservationDir 'sha256sum.txt'
    [System.IO.File]::WriteAllLines($manifestPath, ($shaLines | Sort-Object), $script:Utf8NoBom)

    return @{
        Preserved = $true
        Count     = $preservedFiles.Count
        Directory = $preservationDir
    }
}

function Archive-DirectoryArtifacts {
    param(
        [string]$SourceDir,
        [string]$DestinationDir,
        [string[]]$Extensions = @('.xml', '.txt', '.dump', '.dumpstream', '.exec'),
        [bool]$FailOnEmpty = $true
    )
    if (-not (Test-Path -LiteralPath $DestinationDir)) {
        [System.IO.Directory]::CreateDirectory($DestinationDir) | Out-Null
    }

    if (-not (Test-Path -LiteralPath $SourceDir)) {
        if ($FailOnEmpty) {
            throw "FATAL: アーカイブ元ディレクトリが存在しません: '$SourceDir'."
        }
        return @{
            ArchivedFiles = @()
            ManifestFile  = ''
        }
    }

    $srcFiles = @(Get-ChildItem -LiteralPath $SourceDir -File -ErrorAction Stop |
        Where-Object { $Extensions -contains $_.Extension.ToLowerInvariant() })

    if ($srcFiles.Count -eq 0 -and $FailOnEmpty) {
        throw "FATAL: アーカイブ対象のファイルが存在しません: '$SourceDir'."
    }

    $archivedItems = @()
    foreach ($f in $srcFiles) {
        $destPath = Join-Path $DestinationDir $f.Name
        Copy-Item -LiteralPath $f.FullName -Destination $destPath -Force -ErrorAction Stop

        if (-not (Test-Path -LiteralPath $destPath)) {
            throw "FATAL: ファイルのコピーに失敗しました: '$($f.FullName)' -> '$destPath'."
        }

        $srcHash = Get-Sha256Hex -FilePath $f.FullName
        $destHash = Get-Sha256Hex -FilePath $destPath

        if ($srcHash -ne $destHash) {
            throw "FATAL: アーカイブ前後のSHA-256不一致を検知しました: '$($f.Name)' (src=$srcHash, dest=$destHash)."
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
        throw "FATAL: アーカイブ件数不一致 (元: $($srcFiles.Count), 先: $($destCheckFiles.Count))."
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
        $reasons += "PROCESS_NON_ZERO_EXIT (exit=$ProcessExitCode)."
    }

    $dumpFiles = @(Get-ChildItem -LiteralPath $ArtifactsDir -File -ErrorAction Stop |
        Where-Object { $_.Extension -in @('.dump', '.dumpstream') })
    if ($dumpFiles.Count -gt 0) {
        $reasons += "SUREFIRE_JVM_DUMP_DETECTED (count=$($dumpFiles.Count))."
    }

    $xmlFiles = @(Get-ChildItem -LiteralPath $ArtifactsDir -File -Filter 'TEST-*.xml' -ErrorAction Stop)
    if ($xmlFiles.Count -eq 0) {
        $reasons += "MISSING_SUREFIRE_XML_REPORTS."
    }

    $totalTests = 0
    $totalFailures = 0
    $totalErrors = 0
    $totalSkipped = 0
    $executedClassNames = @{}

    foreach ($xmlFile in $xmlFiles) {
        try {
            $rawContent = [System.IO.File]::ReadAllText($xmlFile.FullName, [System.Text.Encoding]::UTF8)
            [xml]$xmlDoc = $rawContent
            if ($null -eq $xmlDoc.testsuite) {
                $reasons += "INVALID_XML_ROOT_NOT_TESTSUITE ($($xmlFile.Name))."
                continue
            }
            $suiteName = [string]$xmlDoc.testsuite.name
            if ([string]::IsNullOrWhiteSpace($suiteName)) {
                $reasons += "MISSING_TESTSUITE_NAME ($($xmlFile.Name))."
                continue
            }
            $testsCount = [int]$xmlDoc.testsuite.tests
            $failCount = [int]$xmlDoc.testsuite.failures
            $errCount = [int]$xmlDoc.testsuite.errors
            $skipCount = [int]$xmlDoc.testsuite.skipped

            $totalTests += $testsCount
            $totalFailures += $failCount
            $totalErrors += $errCount
            $totalSkipped += $skipCount

            # クラス名の記録 (完全修飾および単純クラス名)
            $simpleName = $suiteName.Split('.')[-1]
            $executedClassNames[$suiteName] = $testsCount
            $executedClassNames[$simpleName] = $testsCount
        } catch {
            $reasons += "CORRUPTED_XML_REPORT ($($xmlFile.Name))."
        }
    }

    if ($xmlFiles.Count -gt 0 -and $totalTests -le 0) {
        $reasons += "ZERO_TESTS_EXECUTED."
    }
    if ($totalFailures -gt 0) {
        $reasons += "FAILURES_DETECTED (count=$totalFailures)."
    }
    if ($totalErrors -gt 0) {
        $reasons += "ERRORS_DETECTED (count=$totalErrors)."
    }
    if ($totalSkipped -gt 0) {
        $reasons += "SKIPPED_TESTS_DETECTED (count=$totalSkipped)."
    }

    if ($SuiteId -eq 'fast-tests') {
        if (-not $executedClassNames.ContainsKey('com.ses.migration.MySqlTestShardInventoryTest')) {
            $reasons += "MISSING_REQUIRED_FAST_INVENTORY_REPORT (com.ses.migration.MySqlTestShardInventoryTest)."
        }
    }

    if ($ExpectedClasses.Count -gt 0) {
        foreach ($expected in $ExpectedClasses) {
            if (-not $executedClassNames.ContainsKey($expected) -or ($executedClassNames[$expected] -le 0)) {
                $reasons += "MISSING_REQUIRED_TEST_CLASS ($expected)."
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
        $reasons += "BASH_UNIT_EXIT_NON_ZERO (exit=$ProcessExitCode)."
    }
    if (-not (Test-Path -LiteralPath $LogPath)) {
        $reasons += "MISSING_BASH_UNIT_LOG ($LogPath)."
    } else {
        $logContent = [System.IO.File]::ReadAllText($LogPath, [System.Text.Encoding]::UTF8)
        if ($logContent -match 'BLOCKED:') {
            $reasons += "BASH_UNIT_BLOCKED_DETECTED."
        }
        if ($logContent -match 'FAIL:') {
            $reasons += "BASH_UNIT_FAIL_DETECTED."
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
        $reasons += "BACKUP_INTEGRATION_EXIT_NON_ZERO (exit=$ProcessExitCode)."
    }

    $requiredSevenFiles = @(
        'integration-summary.json',
        'validate.json',
        'restore.log',
        'target-markers.txt',
        'uploads-markers.txt',
        'source-state.txt',
        'report-restore-contract.txt'
    )

    $drillJsonPath = Join-Path $EvidenceDir 'drill-report.json'
    $summaryJsonPath = Join-Path $EvidenceDir 'integration-summary.json'
    $shaPath = Join-Path $EvidenceDir 'evidence-sha.txt'

    if (-not (Test-Path -LiteralPath $drillJsonPath)) {
        $reasons += "MISSING_DRILL_REPORT_JSON."
    }
    if (-not (Test-Path -LiteralPath $summaryJsonPath)) {
        $reasons += "MISSING_INTEGRATION_SUMMARY_JSON."
    }
    if (-not (Test-Path -LiteralPath $shaPath)) {
        $reasons += "MISSING_EVIDENCE_SHA_TXT."
    }

    # 7ファイルの存在チェック.
    foreach ($req in $requiredSevenFiles) {
        $targetReq = Join-Path $EvidenceDir $req
        if (-not (Test-Path -LiteralPath $targetReq)) {
            $reasons += "MISSING_REQUIRED_EVIDENCE_FILE ($req)."
        }
    }

    # evidence-sha.txt の厳格検証.
    if (Test-Path -LiteralPath $shaPath) {
        $shaLines = @([System.IO.File]::ReadAllLines($shaPath, [System.Text.Encoding]::UTF8))
        if ($shaLines.Count -eq 0) {
            $reasons += "EMPTY_EVIDENCE_SHA_TXT."
        }
        $manifestFiles = @{}
        $validNonEmptyCount = 0

        foreach ($line in $shaLines) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            $validNonEmptyCount++
            $trimmed = $line.Trim()
            # 形式チェック: 64文字hex + 空白 + ファイル名.
            if ($trimmed -notmatch '^([0-9a-fA-F]{64})\s+(\S+)$') {
                $reasons += "INVALID_MANIFEST_LINE_FORMAT ($trimmed)."
                continue
            }
            $expectedHash = $Matches[1].ToLowerInvariant()
            $fileName = $Matches[2]

            # 境界外・不正ファイル名チェック.
            if ($fileName.Contains('..') -or $fileName.Contains('/') -or $fileName.Contains('\') -or $fileName.Contains(':')) {
                $reasons += "OUT_OF_BOUNDS_MANIFEST_PATH ($fileName)."
                continue
            }

            # 重複チェック.
            if ($manifestFiles.ContainsKey($fileName)) {
                $reasons += "DUPLICATE_MANIFEST_ENTRY ($fileName)."
                continue
            }
            $manifestFiles[$fileName] = $expectedHash

            $targetFile = Join-Path $EvidenceDir $fileName
            if (-not (Test-Path -LiteralPath $targetFile)) {
                $reasons += "EVIDENCE_SHA_FILE_NOT_FOUND ($fileName)."
            } else {
                $actualHash = Get-Sha256Hex -FilePath $targetFile
                if ($actualHash -ne $expectedHash) {
                    $reasons += "EVIDENCE_SHA_MISMATCH (${fileName}: exp=$expectedHash, act=$actualHash)."
                }
            }
        }

        if ($validNonEmptyCount -eq 0) {
            $reasons += "EMPTY_EVIDENCE_SHA_TXT."
        }

        # 7ファイルがすべて manifest に含まれているか照合.
        foreach ($req in $requiredSevenFiles) {
            if (-not $manifestFiles.ContainsKey($req)) {
                $reasons += "MANIFEST_MISSING_REQUIRED_FILE ($req)."
            }
        }
    }

    if (Test-Path -LiteralPath $drillJsonPath) {
        try {
            $drill = [System.IO.File]::ReadAllText($drillJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            if ($drill.rto_ok -ne $true -or $drill.rpo_ok -ne $true) {
                $reasons += "DRILL_RPO_RTO_NOT_OK (rto_ok=$($drill.rto_ok), rpo_ok=$($drill.rpo_ok))."
            }
        } catch {
            $reasons += "CORRUPTED_DRILL_REPORT_JSON."
        }
    }

    if (Test-Path -LiteralPath $summaryJsonPath) {
        try {
            $summary = [System.IO.File]::ReadAllText($summaryJsonPath, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
            if ($summary.state -ne 'SUCCESS') {
                $reasons += "INTEGRATION_STATE_NOT_SUCCESS (state=$($summary.state))."
            }
            if ($summary.target_db.before_marker -ne 1 -and $summary.target_db.before_marker -ne '1') {
                $reasons += "MARKER_MISMATCH (target_db.before_marker=$($summary.target_db.before_marker))."
            }
            if ($summary.target_db.mid_dml_replayed -ne 1 -and $summary.target_db.mid_dml_replayed -ne '1') {
                $reasons += "MARKER_MISMATCH (target_db.mid_dml_replayed=$($summary.target_db.mid_dml_replayed))."
            }
            if ($summary.target_db.after_marker -ne 0 -and $summary.target_db.after_marker -ne '0') {
                $reasons += "MARKER_MISMATCH (target_db.after_marker=$($summary.target_db.after_marker))."
            }
            if ($summary.uploads_staging.before_marker -ne 1 -and $summary.uploads_staging.before_marker -ne '1') {
                $reasons += "MARKER_MISMATCH (uploads_staging.before_marker=$($summary.uploads_staging.before_marker))."
            }
            if ($summary.uploads_staging.after_marker -ne 0 -and $summary.uploads_staging.after_marker -ne '0') {
                $reasons += "MARKER_MISMATCH (uploads_staging.after_marker=$($summary.uploads_staging.after_marker))."
            }
        } catch {
            $reasons += "CORRUPTED_INTEGRATION_SUMMARY_JSON."
        }
    }

    return @{
        Pass    = ($reasons.Count -eq 0)
        Reasons = $reasons
    }
}

function Scan-EvidenceDirectoryForSecrets {
    param([string]$EvidenceDir)
    if (-not (Test-Path -LiteralPath $EvidenceDir)) {
        return
    }
    $secretPatterns = @(
        'src-password',
        'tgt-password',
        'repo-password',
        'BEGIN PRIVATE KEY',
        'BEGIN RSA PRIVATE KEY',
        'BEGIN EC PRIVATE KEY'
    )
    $files = @(Get-ChildItem -LiteralPath $EvidenceDir -File -Recurse -ErrorAction Stop)
    foreach ($file in $files) {
        $content = [System.IO.File]::ReadAllText($file.FullName, [System.Text.Encoding]::UTF8)
        foreach ($pat in $secretPatterns) {
            if ($content.Contains($pat)) {
                throw "FATAL: 証跡ディレクトリから秘密情報が検出されました: '$pat' in '$($file.FullName)'."
            }
        }
    }
}

function Get-GateExecutionState {
    param([string]$EvidenceDir)
    $sessionFile = Join-Path $EvidenceDir 'session-state.json'
    if (-not (Test-Path -LiteralPath $sessionFile)) {
        return 'UNKNOWN'
    }
    try {
        $stateObj = [System.IO.File]::ReadAllText($sessionFile, [System.Text.Encoding]::UTF8) | ConvertFrom-Json
        if ($stateObj.state -eq 'RUNNING' -or $stateObj.completed -ne $true) {
            return 'INCOMPLETE'
        }
        return [string]$stateObj.state
    } catch {
        return 'CORRUPTED'
    }
}
