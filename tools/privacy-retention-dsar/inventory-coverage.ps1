[CmdletBinding()]
param(
    [string]$InventoryPath = '.kiro/specs/privacy-retention-dsar/pii-inventory.md',
    [string]$SourceCoveragePath = '.kiro/specs/privacy-retention-dsar/source-coverage.md',
    [string]$MigrationRoot = 'src/main/resources/db/migration',
    [string]$EntityRoot = 'src/main/java',
    [string]$ProviderRoot = 'src/main/java'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-CanonicalSha256 {
    param([Parameter(Mandatory = $true)][string]$Text)

    $sha = [System.Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [System.Text.Encoding]::UTF8.GetBytes($Text)
        return ([System.BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    }
    finally {
        $sha.Dispose()
    }
}

function Get-RelativePath {
    param([Parameter(Mandatory = $true)][string]$Path)

    return [System.IO.Path]::GetRelativePath((Get-Location).Path, $Path).Replace('\', '/')
}

function Get-SqlLineNumber {
    param([string]$Text, [int]$Index)

    return 1 + ([regex]::Matches($Text.Substring(0, $Index), '\n')).Count
}

function Get-SqlParenthesizedBody {
    param([string]$Text, [int]$OpenIndex)

    $depth = 0
    $quote = [char]0
    $escaped = $false
    for ($i = $OpenIndex; $i -lt $Text.Length; $i++) {
        $ch = $Text[$i]
        if ($quote -ne [char]0) {
            if ($escaped) {
                $escaped = $false
                continue
            }
            if ($ch -eq '\') {
                $escaped = $true
                continue
            }
            if ($ch -eq $quote) {
                if ($i + 1 -lt $Text.Length -and $Text[$i + 1] -eq $quote) {
                    $i++
                    continue
                }
                $quote = [char]0
            }
            continue
        }

        if ($ch -eq "'" -or $ch -eq '"' -or $ch -eq [char]96) {
            $quote = $ch
            continue
        }
        if ($ch -eq '(') {
            $depth++
            continue
        }
        if ($ch -eq ')') {
            $depth--
            if ($depth -eq 0) {
                return [pscustomobject]@{
                    body = $Text.Substring($OpenIndex + 1, $i - $OpenIndex - 1)
                    endIndex = $i
                }
            }
        }
    }

    throw "unclosed CREATE TABLE parenthesis at index $OpenIndex"
}

function Split-SqlTopLevel {
    param([string]$Text)

    $segments = [System.Collections.Generic.List[string]]::new()
    $start = 0
    $depth = 0
    $quote = [char]0
    $escaped = $false
    for ($i = 0; $i -lt $Text.Length; $i++) {
        $ch = $Text[$i]
        if ($quote -ne [char]0) {
            if ($escaped) {
                $escaped = $false
                continue
            }
            if ($ch -eq '\') {
                $escaped = $true
                continue
            }
            if ($ch -eq $quote) {
                if ($i + 1 -lt $Text.Length -and $Text[$i + 1] -eq $quote) {
                    $i++
                    continue
                }
                $quote = [char]0
            }
            continue
        }

        if ($ch -eq "'" -or $ch -eq '"' -or $ch -eq [char]96) {
            $quote = $ch
            continue
        }
        if ($ch -eq '(') {
            $depth++
            continue
        }
        if ($ch -eq ')') {
            $depth--
            continue
        }
        if ($ch -eq ',' -and $depth -eq 0) {
            $segments.Add($Text.Substring($start, $i - $start))
            $start = $i + 1
        }
    }
    if ($start -lt $Text.Length) {
        $segments.Add($Text.Substring($start))
    }
    return @($segments)
}

function Get-SqlColumnName {
    param([string]$Segment)

    $trimmed = $Segment.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('--')) {
        return $null
    }
    if ($trimmed -match '^(?i)(CONSTRAINT|PRIMARY|UNIQUE|INDEX|KEY|CHECK|FOREIGN|FULLTEXT|SPATIAL|PARTITION|ENGINE|COMMENT|ON|REFERENCES|AND|OR|THEN|ELSE|END|IF|SET|CALL|DROP|ALTER|ADD)\b') {
        return $null
    }
    $match = [regex]::Match($trimmed, '^[\x60]?([A-Za-z0-9_]+)[\x60]?\s+[A-Za-z]')
    if ($match.Success) {
        return $match.Groups[1].Value
    }
    return $null
}

if (-not (Test-Path -LiteralPath $InventoryPath -PathType Leaf)) {
    throw "inventory not found: $InventoryPath"
}
if (-not (Test-Path -LiteralPath $MigrationRoot -PathType Container)) {
    throw "migration root not found: $MigrationRoot"
}
if (-not (Test-Path -LiteralPath $EntityRoot -PathType Container)) {
    throw "entity root not found: $EntityRoot"
}

$inventoryFullPath = (Resolve-Path -LiteralPath $InventoryPath).Path
$inventoryText = Get-Content -LiteralPath $inventoryFullPath -Raw
$inventoryHash = (Get-FileHash -LiteralPath $inventoryFullPath -Algorithm SHA256).Hash.ToLowerInvariant()
$sourceCoverageExists = Test-Path -LiteralPath $SourceCoveragePath -PathType Leaf
$sourceCoverageText = if ($sourceCoverageExists) { Get-Content -LiteralPath $SourceCoveragePath -Raw } else { '' }
$sourceCoverageHash = if ($sourceCoverageExists) { (Get-FileHash -LiteralPath $SourceCoveragePath -Algorithm SHA256).Hash.ToLowerInvariant() } else { $null }

$tableRecords = [System.Collections.Generic.List[object]]::new()
$alterColumnRecords = [System.Collections.Generic.List[object]]::new()
$tableNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$migrationFiles = Get-ChildItem -LiteralPath $MigrationRoot -Filter '*.sql' -File | Sort-Object FullName

foreach ($file in $migrationFiles) {
    $sql = Get-Content -LiteralPath $file.FullName -Raw
    foreach ($createMatch in [regex]::Matches($sql, '(?i)\bCREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+[\x60]?([A-Za-z0-9_]+)[\x60]?\s*\(')) {
        $bodyResult = Get-SqlParenthesizedBody -Text $sql -OpenIndex ($createMatch.Index + $createMatch.Length - 1)
        $columns = [System.Collections.Generic.List[string]]::new()
        foreach ($segment in Split-SqlTopLevel -Text $bodyResult.body) {
            $column = Get-SqlColumnName -Segment $segment
            if ($null -ne $column) {
                $columns.Add($column)
            }
        }
        $tableRecords.Add([pscustomobject]@{
            table = $createMatch.Groups[1].Value
            columns = @($columns | Sort-Object -Unique)
            source = "$(Get-RelativePath -Path $file.FullName):$(Get-SqlLineNumber -Text $sql -Index $createMatch.Index)"
        })
        [void]$tableNames.Add($createMatch.Groups[1].Value)
    }

    foreach ($alterMatch in [regex]::Matches($sql, '(?i)\bALTER\s+TABLE\s+[\x60]?([A-Za-z0-9_]+)[\x60]?\s+ADD\s+COLUMN\s+[\x60]?([A-Za-z0-9_]+)[\x60]?')) {
        $alterColumnRecords.Add([pscustomobject]@{
            table = $alterMatch.Groups[1].Value
            column = $alterMatch.Groups[2].Value
            source = "$(Get-RelativePath -Path $file.FullName):$(Get-SqlLineNumber -Text $sql -Index $alterMatch.Index)"
        })
        [void]$tableNames.Add($alterMatch.Groups[1].Value)
    }
}

$entityRecords = [System.Collections.Generic.List[object]]::new()
Get-ChildItem -LiteralPath $EntityRoot -Recurse -Filter '*.java' -File | Sort-Object FullName | ForEach-Object {
    $path = $_.FullName
    $lineNumber = 0
    $currentTable = $null
    foreach ($line in Get-Content -LiteralPath $path) {
        $lineNumber++
        if ($line -match '@TableName\("([^"]+)"\)') {
            $currentTable = $Matches[1]
            $entityRecords.Add([pscustomobject]@{
                table = $currentTable
                source = "$(Get-RelativePath -Path $path):$lineNumber"
            })
        }
    }
}

$providerRecords = Get-ChildItem -LiteralPath $ProviderRoot -Recurse -Filter '*.java' -File |
    Where-Object { $_.Name -match '(?i)(provider|gateway|filereference|backup|restore|export|search|cache|audit|integration|cloudsign|freee|gemini|filestorage|filecleanup|storage|cleanup|replica|snapshot|tombstone|redact|anonym|dispos|retention|dsar|privacy|ai|outbound)' } |
    Sort-Object FullName |
    ForEach-Object { Get-RelativePath -Path $_.FullName }

$inventoryMatchedTables = @($tableNames | Where-Object {
        $inventoryText -match ([regex]::Escape('`' + $_) + '(?:`|\.)')
    } | Sort-Object)
$unmappedTables = @($tableNames | Where-Object {
        $inventoryText -notmatch ([regex]::Escape('`' + $_) + '(?:`|\.)')
    } | Sort-Object)
$entityOnlyTables = @($entityRecords.table | Where-Object {
        -not $tableNames.Contains($_)
    } | Sort-Object -Unique)
$allTableRecords = @($tableRecords | Group-Object table | Sort-Object Name | ForEach-Object {
        $group = @($_.Group)
        $columns = [System.Collections.Generic.List[string]]::new()
        $sources = [System.Collections.Generic.List[string]]::new()
        foreach ($member in $group) {
            foreach ($column in @($member.columns)) { $columns.Add($column) }
            $sources.Add($member.source)
        }
        [pscustomobject]@{
            table = $_.Name
            columns = @($columns | Sort-Object -Unique)
            source = @($sources | Sort-Object -Unique) -join '; '
        }
    })
$unmappedTableRecords = @($tableRecords | Where-Object {
        $unmappedTables -contains $_.table
    } | Group-Object table | Sort-Object Name | ForEach-Object {
        $group = @($_.Group)
        $columns = [System.Collections.Generic.List[string]]::new()
        $sources = [System.Collections.Generic.List[string]]::new()
        foreach ($member in $group) {
            foreach ($column in @($member.columns)) { $columns.Add($column) }
            $sources.Add($member.source)
        }
        [pscustomobject]@{
            table = $_.Name
            columns = @($columns | Sort-Object -Unique)
            source = @($sources | Sort-Object -Unique) -join '; '
        }
    })
$sourceCoverageMatchedTables = @($tableNames | Where-Object {
        $sourceCoverageText -match ([regex]::Escape('`' + $_) + '(?:`|\.)')
    } | Sort-Object)
$sourceCoverageUnmappedTables = @($tableNames | Where-Object {
        $sourceCoverageText -notmatch ([regex]::Escape('`' + $_) + '(?:`|\.)')
    } | Sort-Object)
$sourceCoverageMissingColumns = [System.Collections.Generic.List[object]]::new()
foreach ($record in $allTableRecords) {
    foreach ($column in $record.columns) {
        $columnRef = '`' + $record.table + '.' + $column + '`'
        if ($sourceCoverageText -notmatch [regex]::Escape($columnRef)) {
            $sourceCoverageMissingColumns.Add([pscustomobject]@{
                table = $record.table
                column = $column
            })
        }
    }
}
$providerCoverageMissing = @($providerRecords | Where-Object {
        $sourceCoverageText -notmatch [regex]::Escape('`' + $_ + '`')
    })
$entityCoverageMissing = @($entityRecords | Where-Object {
        $sourceCoverageText -notmatch [regex]::Escape('`' + $_.table + '`')
    } | Sort-Object table, source)
$privacyCatalogUnclassifiedTables = $unmappedTables
$privacyCatalogUnknownTableCount = @([regex]::Matches($inventoryText, '(?m)^\| DB-[0-9]{3}.*catalogState=UNKNOWN/BLOCKED')).Count

$canonicalLines = [System.Collections.Generic.List[string]]::new()
foreach ($record in ($tableRecords | Sort-Object table, source)) {
    $canonicalLines.Add("SQL|$($record.table)|$([string]::Join(',', $record.columns))|$($record.source)")
}
foreach ($record in ($alterColumnRecords | Sort-Object table, column, source)) {
    $canonicalLines.Add("SQL_ALTER|$($record.table)|$($record.column)|$($record.source)")
}
foreach ($record in ($entityRecords | Sort-Object table, source)) {
    $canonicalLines.Add("ENTITY|$($record.table)|$($record.source)")
}
foreach ($provider in $providerRecords) {
    $canonicalLines.Add("PROVIDER|$provider")
}
$sourceManifestHash = Get-CanonicalSha256 -Text ($canonicalLines -join "`n")

$dbIdCount = @([regex]::Matches($inventoryText, '(?m)^\| DB-[0-9]{3}\b')).Count
$fileIdCount = @([regex]::Matches($inventoryText, '(?m)^\| FILE-[0-9]{3}\b')).Count
$aiIdCount = @([regex]::Matches($inventoryText, '(?m)^\| AI-[0-9]{3}\b')).Count
$status = if ($unmappedTables.Count -gt 0 -or $entityOnlyTables.Count -gt 0 -or $sourceCoverageUnmappedTables.Count -gt 0 -or $sourceCoverageMissingColumns.Count -gt 0 -or $providerCoverageMissing.Count -gt 0 -or $entityCoverageMissing.Count -gt 0) { 'BLOCKED_COVERAGE_INCOMPLETE' } elseif ($privacyCatalogUnknownTableCount -gt 0) { 'COVERAGE_EXPLICIT_POLICY_UNKNOWN' } else { 'COVERAGE_EXPLICIT' }
$sourceCoverageDisplayPath = if ($sourceCoverageExists) { Get-RelativePath -Path (Resolve-Path -LiteralPath $SourceCoveragePath).Path } else { $SourceCoveragePath }

$result = [ordered]@{
    mode = 'READ_ONLY_SOURCE_COVERAGE'
    status = $status
    exitCode = if ($status -eq 'BLOCKED_COVERAGE_INCOMPLETE') { 2 } else { 0 }
    inventoryPath = Get-RelativePath -Path $inventoryFullPath
    inventorySha256 = $inventoryHash
    sourceCoveragePath = $sourceCoverageDisplayPath
    sourceCoverageSha256 = $sourceCoverageHash
    sourceManifestSha256 = $sourceManifestHash
    migrationFileCount = $migrationFiles.Count
    migrationTableCount = $tableNames.Count
    migrationColumnRecordCount = @($tableRecords.columns | ForEach-Object { $_ }).Count
    migrationAlterColumnRecordCount = $alterColumnRecords.Count
    entityTableCount = @($entityRecords.table | Sort-Object -Unique).Count
    providerCandidateFileCount = @($providerRecords).Count
    explicitInventoryRecordCount = $dbIdCount + $fileIdCount + $aiIdCount
    explicitDbRecordCount = $dbIdCount
    explicitFileRecordCount = $fileIdCount
    explicitAiRecordCount = $aiIdCount
    inventoryMatchedTableCount = $inventoryMatchedTables.Count
    unmappedTableCount = $sourceCoverageUnmappedTables.Count
    unmappedTables = $sourceCoverageUnmappedTables
    unmappedTableRecords = @($sourceCoverageUnmappedTables)
    privacyCatalogExplicitTableCount = $inventoryMatchedTables.Count
    privacyCatalogUnclassifiedTableCount = $privacyCatalogUnclassifiedTables.Count
    privacyCatalogUnclassifiedTables = $privacyCatalogUnclassifiedTables
    privacyCatalogUnclassifiedTableRecords = $unmappedTableRecords
    privacyCatalogUnknownTableCount = $privacyCatalogUnknownTableCount
    sourceTableRecords = $allTableRecords
    entityRecords = @($entityRecords)
    providerCandidateFiles = @($providerRecords)
    sourceCoverageMatchedTableCount = $sourceCoverageMatchedTables.Count
    sourceCoverageUnmappedTableCount = $sourceCoverageUnmappedTables.Count
    sourceCoverageUnmappedTables = $sourceCoverageUnmappedTables
    sourceCoverageColumnCount = @($allTableRecords.columns | ForEach-Object { $_ }).Count
    sourceCoverageMissingColumnCount = $sourceCoverageMissingColumns.Count
    sourceCoverageMissingColumns = @($sourceCoverageMissingColumns | Select-Object -First 100)
    providerCoverageMissingCount = $providerCoverageMissing.Count
    providerCoverageMissing = $providerCoverageMissing
    entityCoverageMissingCount = $entityCoverageMissing.Count
    entityCoverageMissing = @($entityCoverageMissing)
    entityOnlyTableCount = $entityOnlyTables.Count
    entityOnlyTables = $entityOnlyTables
    providerCallCount = 0
    writeCount = 0
}

$result | ConvertTo-Json -Depth 5

if ($status -eq 'BLOCKED_COVERAGE_INCOMPLETE') {
    exit 2
}
