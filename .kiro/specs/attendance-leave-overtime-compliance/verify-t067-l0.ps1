[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BaseCommit,

    [Parameter(Mandatory = $true)]
    [string]$HeadCommit
)

$ErrorActionPreference = 'Stop'

function Assert-Condition {
    param(
        [bool]$Condition,
        [string]$Message
    )

    if (-not $Condition) {
        throw "T067 L0 assertion failed: $Message"
    }
}

function Read-AtCommit {
    param([string]$Path)

    $content = @(git show ("{0}:{1}" -f $HeadCommit, $Path) 2>&1)
    if ($LASTEXITCODE -ne 0) {
        throw "T067 L0 cannot read $Path at $HeadCommit"
    }
    return ($content -join "`n")
}

$repoRoot = (git rev-parse --show-toplevel).Trim()
Set-Location -LiteralPath $repoRoot
$resolvedHead = (git rev-parse $HeadCommit).Trim()

$diffOutput = @(git diff --check "$BaseCommit..$HeadCommit" 2>&1)
$diffExit = $LASTEXITCODE
Assert-Condition ($diffExit -eq 0 -and $diffOutput.Count -eq 0) "git diff --check exit=$diffExit output=$($diffOutput -join ' ')"

$changedFiles = @(git diff --name-only "$BaseCommit..$HeadCommit")
$forbiddenFiles = @($changedFiles | Where-Object { $_ -match '^(src/|pom\.xml)' })
Assert-Condition ($forbiddenFiles.Count -eq 0) "production/test files changed: $($forbiddenFiles -join ', ')"

$tasks = Read-AtCommit '.kiro/specs/attendance-leave-overtime-compliance/tasks.md'
$ledger = Read-AtCommit '.kiro/specs/customer-product-expansion-2026/spec-execution-ledger.md'
$reviewLedger = Read-AtCommit '.kiro/specs/attendance-leave-overtime-compliance/review-ledger.md'
$sourceMatrix = Read-AtCommit '.kiro/specs/attendance-leave-overtime-compliance/source-matrix-and-agreement-inventory.md'
$audit = Read-AtCommit '.kiro/audits/2026-08-01-attendance-parallel-track-plan.md'

$taskLines = @($tasks -split "`r?`n" | Where-Object { $_ -match '^- \[[ x]\] ' })
$checkedTasks = @($taskLines | Where-Object { $_ -match '^- \[x\] ' })
Assert-Condition ($checkedTasks.Count -eq 1 -and $checkedTasks[0] -match '^- \[x\] 0\.') 'task checkbox state is invalid'
Assert-Condition ($tasks -match 'V83.*overtime\.\*.*config') 'V83 config seed instruction is missing'
Assert-Condition ($tasks -notmatch 'V82.*overtime\.\*.*config') 'obsolete V82 config seed instruction remains'

Assert-Condition ($ledger -match '4488ba8' -and $ledger -match 'V81' -and $ledger -match 'V82' -and $ledger -match 'V83') 'ledger provenance/version contract is missing'
Assert-Condition ($ledger -match 'superseded') 'audit superseded marker is missing from active ledger'
Assert-Condition ($sourceMatrix -match 'V5__create_work_record_billing\.sql:1-16.*actual_hours DECIMAL\(5,1\).*V39__unify_work_hour_precision\.sql:4.*DECIMAL\(6,2\)') 'work-hour precision inventory is incomplete'
Assert-Condition ($reviewLedger -match 'Packet/current merged Head' -and $reviewLedger -match 'finding') 'review packet or finding contract is incomplete'
$gate5 = (($reviewLedger -split "`r?`n") | Where-Object { $_ -match 'ATT-GATE-05' }) -join "`n"
$gate6 = (($reviewLedger -split "`r?`n") | Where-Object { $_ -match 'ATT-GATE-06' }) -join "`n"
Assert-Condition ($gate5 -match 'release' -and $gate6 -match 'release') 'ATT-GATE-05/06 release deadline is missing'
Assert-Condition ($audit -match 'SUPERSEDED') 'audit superseded marker is missing'

Write-Output "T067 R1 fix delta L0: PASS"
Write-Output "base=$BaseCommit head=$resolvedHead"
Write-Output "assertions=10 tests=1 failures=0 errors=0 skipped=0 exit=0"
Write-Output "production/test files changed=0"
