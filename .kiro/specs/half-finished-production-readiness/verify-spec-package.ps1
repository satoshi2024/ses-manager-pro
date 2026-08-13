param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# 文書だけの計画でも、ID・trace・task契約・local link の崩れを機械的に拒否する。
$programDir = $PSScriptRoot
$specRoot = Split-Path -Parent $programDir
$errors = [System.Collections.Generic.List[string]]::new()

function Add-Error([string]$message) {
    $script:errors.Add($message)
}

function Get-Text([string]$path) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        Add-Error "必須ファイルがありません: $path"
        return ''
    }
    return [System.IO.File]::ReadAllText($path)
}

$packages = @(
    [pscustomobject]@{
        Id = 'HFP-01'
        Directory = 'payroll-management'
        AcPattern = 'HFP-01-AC\d{2}'
        TaskPattern = 'HFP-01-\d{3}'
        ExpectedAc = 15
        ExpectedTask = 11
        Required = @('requirements.md', 'design.md', 'tasks.md', 'research.md', 'start-conversation.md', 'review-conversation.md', 'review-ledger.md')
    },
    [pscustomobject]@{
        Id = 'HFP-02'
        Directory = 'contract-document-esign'
        AcPattern = 'HFP-02-AC-\d{2}-\d{2}'
        TaskPattern = 'HFP-02-\d{2}'
        ExpectedAc = 60
        ExpectedTask = 11
        Required = @('requirements.md', 'design.md', 'tasks.md', 'research.md', 'start-conversation.md', 'review-conversation.md', 'review-ledger.md')
    },
    [pscustomobject]@{
        Id = 'HFP-03'
        Directory = 'database-backup-recovery'
        AcPattern = 'HFP-03-AC-\d{3}-\d{2}'
        TaskPattern = 'HFP-03-\d{3}'
        ExpectedAc = 36
        ExpectedTask = 12
        Required = @('requirements.md', 'design.md', 'tasks.md', 'baseline.md', 'research.md', 'start-conversation.md', 'review-conversation.md', 'review-ledger.md')
    }
)

$requiredProgramFiles = @(
    'README.md',
    'audit-summary.md',
    'dependency-and-ownership.md',
    'execution-review-handbook.md',
    'execution-ledger.md',
    'start-conversations.md',
    'review-conversations.md',
    'spec-review-report.md',
    'verify-spec-package.ps1'
)
foreach ($file in $requiredProgramFiles) {
    [void](Get-Text (Join-Path $programDir $file))
}

$taskFields = [ordered]@{
    '依存' = '\*\*依存'
    '要件対応' = '\*\*(?:Requirements|対応要件|対応要求)'
    'Objective' = '\*\*Objective'
    '実装方法' = '\*\*(?:実装ガイダンス|Implementation|対象ファイル/方法|対象 file)'
    '自動test' = '\*\*(?:Test|テスト要件|Automated test)'
    'Demo' = '\*\*(?:Demo|隔離 Demo)'
    '証跡' = '\*\*(?:完了証拠|証跡|Evidence)'
    '失敗/rollback' = '\*\*(?:失敗/ロールバック判定|失敗/rollback)'
}

foreach ($package in $packages) {
    $dir = Join-Path $specRoot $package.Directory
    foreach ($file in $package.Required) {
        [void](Get-Text (Join-Path $dir $file))
    }

    $requirementsPath = Join-Path $dir 'requirements.md'
    $tasksPath = Join-Path $dir 'tasks.md'
    $ledgerPath = Join-Path $dir 'review-ledger.md'
    $requirements = Get-Text $requirementsPath
    $tasks = Get-Text $tasksPath
    $ledger = Get-Text $ledgerPath

    $acDefinitionRegex = [regex]::new("(?m)^- \*\*(?<id>$($package.AcPattern))\*\*[: ]")
    $acDefinitions = @($acDefinitionRegex.Matches($requirements) | ForEach-Object { $_.Groups['id'].Value })
    $uniqueAc = @($acDefinitions | Sort-Object -Unique)
    if ($acDefinitions.Count -ne $package.ExpectedAc -or $uniqueAc.Count -ne $package.ExpectedAc) {
        Add-Error "$($package.Id): AC定義数/一意数が期待値と不一致です（定義=$($acDefinitions.Count), 一意=$($uniqueAc.Count), 期待=$($package.ExpectedAc)）"
    }
    foreach ($ac in $uniqueAc) {
        if (-not [regex]::IsMatch($ledger, "(?<![A-Z0-9-])$([regex]::Escape($ac))(?![A-Z0-9-])")) {
            Add-Error "$($package.Id): review-ledger.md に $ac がありません"
        }
    }

    $taskRegex = [regex]::new("(?m)^- \[[ xX]\] \*\*(?<id>$($package.TaskPattern))\b")
    $taskMatches = @($taskRegex.Matches($tasks))
    $taskIds = @($taskMatches | ForEach-Object { $_.Groups['id'].Value })
    $uniqueTasks = @($taskIds | Sort-Object -Unique)
    if ($taskIds.Count -ne $package.ExpectedTask -or $uniqueTasks.Count -ne $package.ExpectedTask) {
        Add-Error "$($package.Id): task定義数/一意数が期待値と不一致です（定義=$($taskIds.Count), 一意=$($uniqueTasks.Count), 期待=$($package.ExpectedTask)）"
    }

    for ($index = 0; $index -lt $taskMatches.Count; $index++) {
        $match = $taskMatches[$index]
        $end = if ($index + 1 -lt $taskMatches.Count) { $taskMatches[$index + 1].Index } else { $tasks.Length }
        $block = $tasks.Substring($match.Index, $end - $match.Index)
        $taskId = $match.Groups['id'].Value
        foreach ($field in $taskFields.GetEnumerator()) {
            if (-not [regex]::IsMatch($block, $field.Value)) {
                Add-Error "${taskId}: task契約の「$($field.Key)」がありません"
            }
        }
        if (-not [regex]::IsMatch($ledger, "(?<![A-Z0-9-])$([regex]::Escape($taskId))(?![A-Z0-9-])")) {
            Add-Error "${taskId}: review-ledger.md にtask行がありません"
        }
    }
}

# 変更対象の Markdown にある相対 link が実在することを確認する。外部URLとanchorは対象外。
$markdownFiles = Get-ChildItem -LiteralPath $specRoot -Recurse -File -Filter '*.md' |
    Where-Object {
        $_.FullName.StartsWith($programDir, [System.StringComparison]::OrdinalIgnoreCase) -or
        $packages.Directory -contains $_.Directory.Name
    }
$linkRegex = [regex]::new('\[[^\]]+\]\((?<target>[^)]+)\)')
foreach ($file in $markdownFiles) {
    $content = Get-Text $file.FullName
    foreach ($match in $linkRegex.Matches($content)) {
        $target = $match.Groups['target'].Value.Trim('<', '>')
        if ($target -match '^(?:https?://|mailto:|#)') { continue }
        $pathPart = ($target -split '#', 2)[0]
        if ([string]::IsNullOrWhiteSpace($pathPart)) { continue }
        $resolved = [System.IO.Path]::GetFullPath((Join-Path $file.DirectoryName $pathPart))
        if (-not (Test-Path -LiteralPath $resolved)) {
            Add-Error "local link切れ: $($file.FullName) -> $target"
        }
    }
}

# 日本語文書へ混入しやすい簡体字の運用語を拒否する。
$forbiddenTerms = @('回退', '阻断', '脱敏')
foreach ($file in $markdownFiles) {
    $content = Get-Text $file.FullName
    foreach ($term in $forbiddenTerms) {
        if ($content.Contains($term)) {
            Add-Error "日本語規約違反の語「$term」: $($file.FullName)"
        }
    }
}

if ($errors.Count -gt 0) {
    $errors | ForEach-Object { Write-Error $_ }
    exit 1
}

$summary = $packages | ForEach-Object { "$($_.Id): AC=$($_.ExpectedAc), Task=$($_.ExpectedTask)" }
Write-Output "PASS: 必須ファイル、AC trace、task契約、local link、日本語規約を確認しました。"
Write-Output ($summary -join '; ')
