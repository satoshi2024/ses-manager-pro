# ============================================================
# SES Manager Pro - V80 Pre-Repair Boundary Verification Script
# ============================================================
# Purpose: V80 marker作成前に失敗したDBで、発注者が証明できた旧契約IDだけを
# durable markerへ固定する。証明できない契約は一切backfill対象へ入れない。

param (
    [string]$DbHost = "localhost",
    [string]$DbPort = "3306",
    [string]$DbName = "ses_manager_db",
    [string]$DbUser = "root",
    [string]$DbPass = "root",
    [long[]]$ApprovedLegacyContractIds = @()
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Invoke-V80Mysql([string]$Sql) {
    $previousPassword = $env:MYSQL_PWD
    try {
        $env:MYSQL_PWD = $DbPass
        $output = & mysql --host=$DbHost --port=$DbPort --user=$DbUser --database=$DbName `
            --batch --skip-column-names --execute=$Sql 2>&1
        if ($LASTEXITCODE -ne 0) {
            throw "mysql実行失敗: $output"
        }
        return @($output)
    } finally {
        if ($null -eq $previousPassword) {
            Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
        } else {
            $env:MYSQL_PWD = $previousPassword
        }
    }
}

if (-not (Get-Command mysql -ErrorAction SilentlyContinue)) {
    throw "mysql CLIがPATHにありません。production DBと同じMySQL 8 clientを導入して再実行してください。"
}

$approved = @($ApprovedLegacyContractIds | Sort-Object -Unique)
if ($approved | Where-Object { $_ -le 0 }) {
    throw "ApprovedLegacyContractIdsには正のcontract IDだけを指定してください。"
}

Write-Host "V80 marker前失敗の境界を確認します: $DbHost`:$DbPort/$DbName"

$failedV80 = [int](Invoke-V80Mysql @"
SELECT COUNT(*)
FROM flyway_schema_history
WHERE version = '80' AND success = 0;
"@ | Select-Object -Last 1)
if ($failedV80 -ne 1) {
    throw "failed V80 historyが1件ではありません（actual=$failedV80）。このrunbookの対象外です。"
}

$contractTable = [int](Invoke-V80Mysql @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 't_contract';
"@ | Select-Object -Last 1)
if ($contractTable -ne 1) {
    throw "t_contractが存在しません。処理を中止します。"
}

$markerTable = [int](Invoke-V80Mysql @"
SELECT COUNT(*)
FROM information_schema.tables
WHERE table_schema = DATABASE() AND table_name = 't_contract_acceptance_backfill';
"@ | Select-Object -Last 1)
if ($markerTable -eq 1) {
    $sentinel = [int](Invoke-V80Mysql "SELECT COUNT(*) FROM t_contract_acceptance_backfill WHERE contract_id = 0;" | Select-Object -Last 1)
    if ($sentinel -eq 1) {
        Write-Host "sentinelは既にdurableです。markerを変更せず終了します。"
        exit 0
    }
}

if ($approved.Count -gt 0) {
    $approvedSql = ($approved -join ',')
    $existingCount = [int](Invoke-V80Mysql "SELECT COUNT(*) FROM t_contract WHERE id IN ($approvedSql);" | Select-Object -Last 1)
    if ($existingCount -ne $approved.Count) {
        throw "承認済み旧契約IDの一部が存在しません（expected=$($approved.Count), actual=$existingCount）。"
    }
} else {
    $approvedSql = $null
    Write-Host "証明済み旧契約IDは0件です。fail-closedとして全契約をacceptance_required=1のまま保持します。"
}

# CREATE TABLEの暗黙COMMITとsentinel INSERTの明示COMMITにより、flyway repair前に境界をdurable化する。
Invoke-V80Mysql @"
CREATE TABLE IF NOT EXISTS t_contract_acceptance_backfill (
  contract_id BIGINT PRIMARY KEY,
  backfilled_at DATETIME DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
INSERT IGNORE INTO t_contract_acceptance_backfill (contract_id) VALUES (0);
COMMIT;
"@ | Out-Null

if ($approved.Count -gt 0) {
    Invoke-V80Mysql "INSERT IGNORE INTO t_contract_acceptance_backfill (contract_id) SELECT id FROM t_contract WHERE id IN ($approvedSql); COMMIT;" | Out-Null
}

$markerCount = [int](Invoke-V80Mysql "SELECT COUNT(*) FROM t_contract_acceptance_backfill;" | Select-Object -Last 1)
$expectedMarkerCount = $approved.Count + 1
if ($markerCount -ne $expectedMarkerCount) {
    throw "marker件数が一致しません（expected=$expectedMarkerCount, actual=$markerCount）。flyway repairを実行しないでください。"
}

Write-Host "境界固定完了: sentinel=1, approvedLegacy=$($approved.Count)。"
Write-Host "次にrunbookの順序どおり、backup取得→flyway repair→V80/V81 migrate→post-assertを実行してください。"
Write-Host "Runbook: .kiro/runbooks/v80-pre-repair-runbook.md"
