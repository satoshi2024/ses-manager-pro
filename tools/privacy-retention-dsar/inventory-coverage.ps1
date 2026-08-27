[CmdletBinding()]
param(
    [string]$InventoryPath = '.kiro/specs/privacy-retention-dsar/pii-inventory.md',
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

function Test-SqlColumnLine {
    param([string]$Line)

    $trimmed = $Line.Trim()
    if ([string]::IsNullOrWhiteSpace($trimmed) -or $trimmed.StartsWith('--')) {
        return $false
    }
    if ($trimmed -match '^(?i)(CONSTRAINT|PRIMARY|UNIQUE|INDEX|KEY|CHECK|FOREIGN|FULLTEXT|SPATIAL|PARTITION|ENGINE|COMMENT)\b') {
        return $false
    }
    return $trimmed -match '^[`]?([A-Za-z0-9_]+)[`]?\s+[A-Za-z]'
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

$tableRecords = [System.Collections.Generic.List[object]]::new()
$alterColumnRecords = [System.Collections.Generic.List[object]]::new()
$tableNames = [System.Collections.Generic.HashSet[string]]::new([System.StringComparer]::OrdinalIgnoreCase)
$migrationFiles = Get-ChildItem -LiteralPath $MigrationRoot -Filter '*.sql' -File | Sort-Object FullName

foreach ($file in $migrationFiles) {
    $lines = Get-Content -LiteralPath $file.FullName
    $currentTable = $null
    $currentColumns = [System.Collections.Generic.List[string]]::new()
    $createLine = 0
    $lineNumber = 0

    foreach ($line in $lines) {
        $lineNumber++
        if ($line -match '(?i)\bCREATE\s+TABLE(?:\s+IF\s+NOT\s+EXISTS)?\s+[`]?([A-Za-z0-9_]+)[`]?') {
            if ($null -ne $currentTable) {
                $record = [pscustomobject]@{
                    table = $currentTable
                    columns = @($currentColumns | Sort-Object -Unique)
                    source = "$(Get-RelativePath -Path $file.FullName):$createLine"
                }
                $tableRecords.Add($record)
                [void]$tableNames.Add($currentTable)
            }
            $currentTable = $Matches[1]
            $currentColumns = [System.Collections.Generic.List[string]]::new()
            $createLine = $lineNumber
            continue
        }

        foreach ($alterMatch in [regex]::Matches($line, '(?i)\bALTER\s+TABLE\s+[`]?([A-Za-z0-9_]+)[`]?\s+ADD\s+COLUMN\s+[`]?([A-Za-z0-9_]+)[`]?')) {
            $alterColumnRecords.Add([pscustomobject]@{
                table = $alterMatch.Groups[1].Value
                column = $alterMatch.Groups[2].Value
                source = "$(Get-RelativePath -Path $file.FullName):$lineNumber"
            })
            [void]$tableNames.Add($alterMatch.Groups[1].Value)
        }

        if ($null -ne $currentTable -and (Test-SqlColumnLine -Line $line)) {
            $columnMatch = [regex]::Match($line.Trim(), '^[`]?([A-Za-z0-9_]+)[`]?\s+')
            if ($columnMatch.Success) {
                $currentColumns.Add($columnMatch.Groups[1].Value)
            }
        }

        if ($null -ne $currentTable -and $line -match '^\s*\)\s*ENGINE') {
            $record = [pscustomobject]@{
                table = $currentTable
                columns = @($currentColumns | Sort-Object -Unique)
                source = "$(Get-RelativePath -Path $file.FullName):$createLine"
            }
            $tableRecords.Add($record)
            [void]$tableNames.Add($currentTable)
            $currentTable = $null
            $currentColumns = [System.Collections.Generic.List[string]]::new()
            $createLine = 0
        }
    }

    if ($null -ne $currentTable) {
        $record = [pscustomobject]@{
            table = $currentTable
            columns = @($currentColumns | Sort-Object -Unique)
            source = "$(Get-RelativePath -Path $file.FullName):$createLine"
        }
        $tableRecords.Add($record)
        [void]$tableNames.Add($currentTable)
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
    Where-Object { $_.Name -match '(?i)(provider|gateway|filereference|backup|restore|export|search|cache|audit|integration|cloudsign|freee)' } |
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
$status = if ($unmappedTables.Count -gt 0 -or $entityOnlyTables.Count -gt 0) { 'BLOCKED_COVERAGE_INCOMPLETE' } else { 'COVERAGE_EXPLICIT' }

$result = [ordered]@{
    mode = 'READ_ONLY_SOURCE_COVERAGE'
    status = $status
    inventoryPath = Get-RelativePath -Path $inventoryFullPath
    inventorySha256 = $inventoryHash
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
    unmappedTableCount = $unmappedTables.Count
    unmappedTables = $unmappedTables
    entityOnlyTableCount = $entityOnlyTables.Count
    entityOnlyTables = $entityOnlyTables
    providerCallCount = 0
    writeCount = 0
}

$result | ConvertTo-Json -Depth 5

if ($status -eq 'BLOCKED_COVERAGE_INCOMPLETE') {
    exit 2
}
