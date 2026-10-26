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
    $prevEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $headOutput = @(& git -C $RepoRoot rev-parse HEAD 2>&1)
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($LASTEXITCODE -ne 0 -or $headOutput.Count -eq 0) {
        $errText = $headOutput -join "; "
        throw "FATAL: Git HEAD SHA の取得に失敗しました (exit=$LASTEXITCODE): $errText."
    }
    $head = [string]$headOutput[0]
    if ([string]::IsNullOrWhiteSpace($head) -or ($head.Trim().Length -ne 40) -or ($head.Trim() -notmatch '^[0-9a-f]{40}$')) {
        throw "FATAL: 有効な40文字のGit HEAD SHAを取得できませんでした (取得値: '$head')."
    }
    return $head.Trim()
}

function Test-IsSubpathOrSame {
    param(
        [string]$ChildPath,
        [string]$ParentPath
    )
    if ([string]::IsNullOrWhiteSpace($ChildPath) -or [string]::IsNullOrWhiteSpace($ParentPath)) {
        return $false
    }
    $normChild = [System.IO.Path]::GetFullPath($ChildPath)
    $normParent = [System.IO.Path]::GetFullPath($ParentPath)
    if ($normChild.Equals($normParent, [System.StringComparison]::OrdinalIgnoreCase)) {
        return $true
    }
    $parentWithSlash = $normParent
    if (-not $parentWithSlash.EndsWith([System.IO.Path]::DirectorySeparatorChar.ToString()) -and
        -not $parentWithSlash.EndsWith([System.IO.Path]::AltDirectorySeparatorChar.ToString())) {
        $parentWithSlash += [System.IO.Path]::DirectorySeparatorChar
    }
    return $normChild.StartsWith($parentWithSlash, [System.StringComparison]::OrdinalIgnoreCase)
}

function Assert-CleanWorkingTree {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir = ''
    )
    $prevEap = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $statusOutput = @(& git -C $RepoRoot status --porcelain 2>&1)
    } finally {
        $ErrorActionPreference = $prevEap
    }
    if ($LASTEXITCODE -ne 0) {
        $errDetail = $statusOutput -join "; "
        throw "FATAL: Git status の実行に失敗しました (exit=$LASTEXITCODE): $errDetail."
    }
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
        if ($line.Length -lt 3) {
            $dirtyItems += $line
            continue
        }

        $indexStatus = $line[0]
        $workStatus = $line[1]
        $isUntracked = ($indexStatus -eq '?' -and $workStatus -eq '?')

        $filePathRel = $line.Substring(2).Trim()
        $filePathRel = $filePathRel.Trim('"')
        $itemFullPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $filePathRel))

        # 追跡済み (tracked) ファイルの変更は target や EvidenceDir 配下であっても絶対に除外しない.
        if ($isUntracked -and (Test-IsSubpathOrSame -ChildPath $itemFullPath -ParentPath $targetFullPath)) {
            continue
        }
        if ($isUntracked -and -not [string]::IsNullOrWhiteSpace($evidenceFullPath) -and (Test-IsSubpathOrSame -ChildPath $itemFullPath -ParentPath $evidenceFullPath)) {
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

    $safeExactFlags = @(
        '-B', '--batch-mode',
        '-e', '--errors',
        '-X', '--debug',
        '-q', '--quiet',
        '-U', '--update-snapshots',
        '-C', '--strict-checksums',
        '-c', '--lax-checksums',
        '-V', '--show-version',
        '-ntp', '--no-transfer-progress'
    )

    $safePatterns = @(
        '^-Djava\.awt\.headless=(true|false)$',
        '^-Dfile\.encoding=[a-zA-Z0-9_\-]+$',
        '^-Duser\.(language|country|timezone)=[a-zA-Z0-9_\/]+$',
        '^-Dsurefire\.runOrder\.random\.seed=\d+$'
    )

    foreach ($arg in $ArgsList) {
        if ([string]::IsNullOrWhiteSpace($arg)) { continue }
        $t = $arg.Trim()
        $isAllowed = $false
        if ($safeExactFlags -contains $t) {
            $isAllowed = $true
        } else {
            foreach ($pat in $safePatterns) {
                if ($t -match $pat) {
                    $isAllowed = $true
                    break
                }
            }
        }
        if (-not $isAllowed) {
            throw "FATAL: 未許可または安全性が確認できないMaven引数は禁止されています: '$arg'."
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
    $repoPath = [System.IO.Path]::GetFullPath($RepoRoot)
    $targetPath = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot 'target'))

    if (Test-IsSubpathOrSame -ChildPath $fullEvidencePath -ParentPath $targetPath) {
        throw "FATAL: EvidenceDir は target 配下に設定できません (指定値: '$fullEvidencePath')."
    }

    if ($fullEvidencePath.Equals($repoPath, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "FATAL: リポジトリルートを EvidenceDir として指定することは禁止されています: '$fullEvidencePath'."
    }

    $protectedSourceDirs = @('src', 'scripts', 'ops', 'sql', 'db', '.git', 'templates', 'static', 'config', 'apache-maven-3.9.6')
    foreach ($sp in $protectedSourceDirs) {
        $spFull = [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $sp))
        if (Test-IsSubpathOrSame -ChildPath $fullEvidencePath -ParentPath $spFull) {
            throw "FATAL: ソース・管理ディレクトリ配下を EvidenceDir として使用することは禁止されています: '$fullEvidencePath'."
        }
    }

    if (Test-Path -LiteralPath $fullEvidencePath) {
        $existingItems = @(Get-ChildItem -LiteralPath $fullEvidencePath -Force -ErrorAction SilentlyContinue)
        if ($existingItems.Count -gt 0) {
            throw "FATAL: 既存の EvidenceDir の再利用は禁止されています (指定値: '$fullEvidencePath')."
        }
    }
}

function Assert-ValidPitrWorkDir {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir,
        [string]$PitrWorkDir
    )
    if ([string]::IsNullOrWhiteSpace($PitrWorkDir)) {
        throw "FATAL: PITR私有作業ディレクトリが指定されていません."
    }
    $workFull = [System.IO.Path]::GetFullPath($PitrWorkDir)
    $repoFull = [System.IO.Path]::GetFullPath($RepoRoot)
    $evidenceFull = if (-not [string]::IsNullOrWhiteSpace($EvidenceDir)) {
        [System.IO.Path]::GetFullPath($EvidenceDir)
    } else { '' }

    # 1. ルートディレクトリ (C:\, /, など) の拒絶.
    $root = [System.IO.Path]::GetPathRoot($workFull)
    if ($workFull.Equals($root, [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "FATAL: ルートディレクトリをPITR作業ディレクトリとして指定することは禁止されています: '$workFull'."
    }

    # 2. システム保護領域 (Windows, ProgramFiles など) の拒絶.
    $sysDirs = @(
        $env:SystemRoot,
        $env:ProgramFiles,
        ${env:ProgramFiles(x86)}
    ) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }
    foreach ($sd in $sysDirs) {
        $sdFull = [System.IO.Path]::GetFullPath($sd)
        if (Test-IsSubpathOrSame -ChildPath $workFull -ParentPath $sdFull) {
            throw "FATAL: システム保護ディレクトリ配下をPITR作業ディレクトリに指定することは禁止されています: '$workFull'."
        }
    }

    # 3. リポジトリまたは証跡ディレクトリの祖先または配下の拒絶.
    if (Test-IsSubpathOrSame -ChildPath $workFull -ParentPath $repoFull) {
        throw "FATAL: PITR私有作業ディレクトリはリポジトリ配下に設定できません: '$workFull'."
    }
    if (Test-IsSubpathOrSame -ChildPath $repoFull -ParentPath $workFull) {
        throw "FATAL: PITR私有作業ディレクトリはリポジトリの祖先ディレクトリであってはなりません: '$workFull'."
    }
    if (-not [string]::IsNullOrWhiteSpace($evidenceFull)) {
        if (Test-IsSubpathOrSame -ChildPath $workFull -ParentPath $evidenceFull) {
            throw "FATAL: PITR私有作業ディレクトリは証跡ディレクトリ配下に設定できません: '$workFull'."
        }
        if (Test-IsSubpathOrSame -ChildPath $evidenceFull -ParentPath $workFull) {
            throw "FATAL: PITR私有作業ディレクトリは証跡ディレクトリの祖先ディレクトリであってはなりません: '$workFull'."
        }
    }

    # 4. 既存ディレクトリの拒絶 (本実行が新規独占作成しなければならない).
    if (Test-Path -LiteralPath $workFull) {
        throw "FATAL: PITR作業ディレクトリとして既存ディレクトリを指定することは禁止されています: '$workFull'."
    }

    # 5. 親ディレクトリ階層の reparse point / junction 検証.
    $curr = Split-Path -Parent $workFull
    while (-not [string]::IsNullOrWhiteSpace($curr) -and -not ($curr.Equals([System.IO.Path]::GetPathRoot($curr), [System.StringComparison]::OrdinalIgnoreCase))) {
        if (Test-Path -LiteralPath $curr) {
            $item = Get-Item -LiteralPath $curr -Force -ErrorAction Stop
            if ($item.Attributes.HasFlag([System.IO.FileAttributes]::ReparsePoint)) {
                throw "FATAL: PITR作業ディレクトリの祖先にReparsePoint/Junctionが検出されました: '$curr'."
            }
        }
        $curr = Split-Path -Parent $curr
    }
}

function New-ControlledPitrWorkDir {
    param(
        [string]$RepoRoot,
        [string]$EvidenceDir,
        [string]$CustomPitrWorkDir = ''
    )
    $controlledTempRoot = [System.IO.Path]::GetFullPath([System.IO.Path]::GetTempPath())
    $candidate = ''
    if (-not [string]::IsNullOrWhiteSpace($CustomPitrWorkDir)) {
        $candidate = $CustomPitrWorkDir
    } else {
        $rndSuffix = [System.Guid]::NewGuid().ToString('N').Substring(0, 8)
        $candidate = Join-Path $controlledTempRoot ("ses-pitr-run-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + "-$rndSuffix")
    }

    Assert-ValidPitrWorkDir -RepoRoot $RepoRoot -EvidenceDir $EvidenceDir -PitrWorkDir $candidate

    $workFull = [System.IO.Path]::GetFullPath($candidate)
    [System.IO.Directory]::CreateDirectory($workFull) | Out-Null

    # 作成直後の再検証 (ジャンクションでないこと、空であること).
    $dirItem = Get-Item -LiteralPath $workFull -Force -ErrorAction Stop
    if ($dirItem.Attributes.HasFlag([System.IO.FileAttributes]::ReparsePoint)) {
        Remove-Item -LiteralPath $workFull -Force -ErrorAction SilentlyContinue
        throw "FATAL: 作成されたPITRディレクトリがReparsePoint/Junctionです: '$workFull'."
    }
    $contents = @(Get-ChildItem -LiteralPath $workFull -Force -ErrorAction Stop)
    if ($contents.Count -gt 0) {
        Remove-Item -LiteralPath $workFull -Recurse -Force -ErrorAction SilentlyContinue
        throw "FATAL: 作成されたPITRディレクトリが空ではありません: '$workFull'."
    }

    return $workFull
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

function Archive-SuiteArtifacts {
    param(
        [string]$RepoRoot,
        [string]$SuiteId,
        [string]$SuiteLogPath,
        [string]$DestinationDir
    )
    if (-not (Test-Path -LiteralPath $DestinationDir)) {
        [System.IO.Directory]::CreateDirectory($DestinationDir) | Out-Null
    }

    $archivedItems = @()

    # 1. Surefire reports (*.xml, *.txt, *.dump, *.dumpstream)
    $surefireDir = Join-Path $RepoRoot 'target\surefire-reports'
    if (Test-Path -LiteralPath $surefireDir) {
        $surefireFiles = @(Get-ChildItem -LiteralPath $surefireDir -File -ErrorAction Stop |
            Where-Object { $_.Extension.ToLowerInvariant() -in @('.xml', '.txt', '.dump', '.dumpstream') })
        foreach ($f in $surefireFiles) {
            $destFile = Join-Path $DestinationDir $f.Name
            Copy-Item -LiteralPath $f.FullName -Destination $destFile -Force -ErrorAction Stop
            $srcH = Get-Sha256Hex -FilePath $f.FullName
            $dstH = Get-Sha256Hex -FilePath $destFile
            if ($srcH -ne $dstH) {
                throw "FATAL: Surefire 報告ファイルコピー時のハッシュ不一致: '$($f.Name)' (src=$srcH, dst=$dstH)."
            }
            $archivedItems += [PSCustomObject]@{ RelPath = $f.Name; Hash = $dstH }
        }
    }

    # 2. jacoco.exec (存在する場合)
    $jacocoExec = Join-Path $RepoRoot 'target\jacoco.exec'
    if (Test-Path -LiteralPath $jacocoExec) {
        $destExec = Join-Path $DestinationDir 'jacoco.exec'
        Copy-Item -LiteralPath $jacocoExec -Destination $destExec -Force -ErrorAction Stop
        $srcH = Get-Sha256Hex -FilePath $jacocoExec
        $dstH = Get-Sha256Hex -FilePath $destExec
        if ($srcH -ne $dstH) {
            throw "FATAL: jacoco.exec コピー時のハッシュ不一致."
        }
        $archivedItems += [PSCustomObject]@{ RelPath = 'jacoco.exec'; Hash = $dstH }
    }

    # 3. JaCoCo site report directory (存在する場合、再帰コピーとハッシュ検証)
    $jacocoSite = Join-Path $RepoRoot 'target\site\jacoco'
    if (Test-Path -LiteralPath $jacocoSite) {
        $destJacocoDir = Join-Path $DestinationDir 'jacoco'
        if (-not (Test-Path -LiteralPath $destJacocoDir)) {
            [System.IO.Directory]::CreateDirectory($destJacocoDir) | Out-Null
        }
        $jFiles = @(Get-ChildItem -LiteralPath $jacocoSite -File -Recurse -ErrorAction Stop)
        foreach ($jf in $jFiles) {
            $rel = $jf.FullName.Substring($jacocoSite.Length).TrimStart('\', '/')
            $destP = Join-Path $destJacocoDir $rel
            $pDir = Split-Path -Parent $destP
            if (-not (Test-Path -LiteralPath $pDir)) {
                [System.IO.Directory]::CreateDirectory($pDir) | Out-Null
            }
            Copy-Item -LiteralPath $jf.FullName -Destination $destP -Force -ErrorAction Stop
            $srcH = Get-Sha256Hex -FilePath $jf.FullName
            $dstH = Get-Sha256Hex -FilePath $destP
            if ($srcH -ne $dstH) {
                throw "FATAL: JaCoCoレポートファイルコピー時のハッシュ不一致: '$rel'."
            }
            $normRel = ('jacoco/' + ($rel -replace '\\', '/'))
            $archivedItems += [PSCustomObject]@{ RelPath = $normRel; Hash = $dstH }
        }
    }

    # 4. browser-m-evidence (存在する場合、再帰コピーとハッシュ検証)
    $browserEvidence = Join-Path $RepoRoot 'target\browser-m-evidence'
    if (Test-Path -LiteralPath $browserEvidence) {
        $destBrowserDir = Join-Path $DestinationDir 'browser-m-evidence'
        if (-not (Test-Path -LiteralPath $destBrowserDir)) {
            [System.IO.Directory]::CreateDirectory($destBrowserDir) | Out-Null
        }
        $bFiles = @(Get-ChildItem -LiteralPath $browserEvidence -File -Recurse -ErrorAction Stop)
        foreach ($bf in $bFiles) {
            $rel = $bf.FullName.Substring($browserEvidence.Length).TrimStart('\', '/')
            $destP = Join-Path $destBrowserDir $rel
            $pDir = Split-Path -Parent $destP
            if (-not (Test-Path -LiteralPath $pDir)) {
                [System.IO.Directory]::CreateDirectory($pDir) | Out-Null
            }
            Copy-Item -LiteralPath $bf.FullName -Destination $destP -Force -ErrorAction Stop
            $srcH = Get-Sha256Hex -FilePath $bf.FullName
            $dstH = Get-Sha256Hex -FilePath $destP
            if ($srcH -ne $dstH) {
                throw "FATAL: browser-m-evidence ファイルコピー時のハッシュ不一致: '$rel'."
            }
            $normRel = ('browser-m-evidence/' + ($rel -replace '\\', '/'))
            $archivedItems += [PSCustomObject]@{ RelPath = $normRel; Hash = $dstH }
        }
    }

    # 5. suite 実行ログのコピーとハッシュ検証.
    if (Test-Path -LiteralPath $SuiteLogPath) {
        $destLog = Join-Path $DestinationDir (Split-Path -Leaf $SuiteLogPath)
        Copy-Item -LiteralPath $SuiteLogPath -Destination $destLog -Force -ErrorAction Stop
        $srcH = Get-Sha256Hex -FilePath $SuiteLogPath
        $dstH = Get-Sha256Hex -FilePath $destLog
        if ($srcH -ne $dstH) {
            throw "FATAL: スイートログコピー時のハッシュ不一致: '$(Split-Path -Leaf $SuiteLogPath)'."
        }
        $archivedItems += [PSCustomObject]@{ RelPath = (Split-Path -Leaf $SuiteLogPath); Hash = $dstH }
    }

    # 6. sha256sum.txt マニフェストの生成と検証.
    $shaLines = @()
    foreach ($item in ($archivedItems | Sort-Object -Property RelPath)) {
        $shaLines += "$($item.Hash)  $($item.RelPath)"
    }
    $manifestPath = Join-Path $DestinationDir 'sha256sum.txt'
    [System.IO.File]::WriteAllLines($manifestPath, $shaLines, $script:Utf8NoBom)

    return @{
        ArchivedCount = $archivedItems.Count
        ManifestPath  = $manifestPath
        Items         = $archivedItems
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
