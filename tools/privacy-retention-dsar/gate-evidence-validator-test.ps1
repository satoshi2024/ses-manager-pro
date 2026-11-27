[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$validator = Join-Path $PSScriptRoot 'gate-evidence-validator.ps1'
$specDir = Join-Path $root '.kiro\specs\privacy-retention-dsar'

$tempBase = Join-Path ([System.IO.Path]::GetTempPath()) ("ses-nf07-gate-test-" + [guid]::NewGuid().ToString('N'))
$tempOrigin = Join-Path $tempBase 'origin.git'
$tempWorktree = Join-Path $tempBase 'worktree'
$stubCoverage = Join-Path $tempBase 'stub-coverage.ps1'

try {
    New-Item -ItemType Directory -Force -Path $tempWorktree | Out-Null
    & git init --bare $tempOrigin *> $null
    if ($LASTEXITCODE -ne 0) { throw 'failed to init temp bare origin' }

    & git -C $tempWorktree init *> $null
    & git -C $tempWorktree config user.email 'fixture@example.invalid'
    & git -C $tempWorktree config user.name 'Fixture'
    & git -C $tempWorktree checkout -b 'codex/privacy-retention-dsar' *> $null
    [System.IO.File]::WriteAllText((Join-Path $tempWorktree 'README.txt'), "fixture`n", [System.Text.Encoding]::ASCII)
    & git -C $tempWorktree add README.txt *> $null
    & git -C $tempWorktree commit -m 'fixture' *> $null
    & git -C $tempWorktree remote add origin $tempOrigin *> $null
    & git -C $tempWorktree push -u origin 'codex/privacy-retention-dsar' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'failed to push fixture branch to temp origin' }

    $stubScript = @'
$obj = [ordered]@{
    status = 'COVERAGE_EXPLICIT_POLICY_UNKNOWN'
    privacyCatalogUnclassifiedTableCount = 0
    privacyCatalogUnknownTableCount = 86
    sourceCoverageUnmappedTableCount = 0
    sourceCoverageMissingColumnCount = 0
    sourceCoverageExtraColumnCount = 0
    providerCoverageMissingCount = 0
    sourceCoverageExtraProviderCount = 0
    entityCoverageMissingCount = 0
}
$obj | ConvertTo-Json -Depth 5
'@
    [System.IO.File]::WriteAllText($stubCoverage, $stubScript + "`n", [System.Text.Encoding]::ASCII)

    # 1. Closed structural coverage + matching DEV branch fixture -> exit 0 DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW
    $output = & pwsh -NoProfile -File $validator `
        -GateMode DEV_0_D0 `
        -WorktreePath $tempWorktree `
        -DevEvidencePath (Join-Path $specDir 'dev-gate-evidence.json') `
        -InventoryPath (Join-Path $specDir 'pii-inventory.md') `
        -SourceCoveragePath (Join-Path $specDir 'source-coverage.md') `
        -CoverageScriptPath $stubCoverage `
        -PlanPath (Join-Path $specDir 'plan.md') `
        -TasksPath (Join-Path $specDir 'tasks.md') `
        -ReviewLedgerPath (Join-Path $specDir 'review-ledger.md') 2>&1 | Out-String
    $exitCode = $LASTEXITCODE

    if ($exitCode -ne 0) {
        throw "expected DEV-only authorization exit code 0, got $exitCode`n$output"
    }
    $result = $output | ConvertFrom-Json
    if ($result.status -ne 'DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW') {
        throw "expected DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW, got $($result.status)"
    }
    if ($result.devScopeAuthorized -ne $true -or $result.canStartDevOnlyScope -ne $true) {
        throw 'DEV-only decision must authorize only the explicitly scoped development work'
    }
    if ($result.canStartF1M -ne $false -or $result.canEnableProductionDisposition -ne $false -or $result.canCallExternalProvider -ne $false -or $result.canCreatePullRequest -ne $false) {
        throw 'a blocked evidence packet must not authorize F1-M, production disposition, provider calls, or PR creation'
    }
    if ($result.providerCallCount -ne 0 -or $result.writeCount -ne 0 -or $result.coverage.privacyCatalogUnknownTableCount -ne 86) {
        throw 'DEV-only validator must remain read-only and report the Full policy blocker'
    }
    if (@($result.blockers).Count -ne 0) {
        throw "unexpected DEV-only blockers: $($result.blockers | ConvertTo-Json -Compress)"
    }

    # 2. Live repository read-only check -> exit 2 HARD_STOP while structural coverage is incomplete, unknown=86
    $liveOutput = & pwsh -NoProfile -File $validator `
        -GateMode DEV_0_D0 `
        -AllowDetachedReviewWorktree `
        -WorktreePath $root 2>&1 | Out-String
    $liveExitCode = $LASTEXITCODE
    if ($liveExitCode -ne 2) {
        throw "expected live incomplete-coverage hard-stop exit code 2, got $liveExitCode`n$liveOutput"
    }
    $liveResult = $liveOutput | ConvertFrom-Json
    if (@($liveResult.blockers.code) -notcontains 'STRUCTURAL_COVERAGE_NOT_CLOSED') {
        throw 'live repository with unmapped tables must produce STRUCTURAL_COVERAGE_NOT_CLOSED'
    }
    if ($liveResult.providerCallCount -ne 0 -or $liveResult.writeCount -ne 0 -or $liveResult.coverage.privacyCatalogUnknownTableCount -ne 86) {
        throw "expected live read-only unknown table count 86, got $($liveResult.coverage.privacyCatalogUnknownTableCount)"
    }
}
finally {
    if (Test-Path -LiteralPath $tempBase) {
        Remove-Item -LiteralPath $tempBase -Recurse -Force -ErrorAction SilentlyContinue
    }
}

$missingEvidencePath = Join-Path $PSScriptRoot 'gate-evidence-not-present.json'
if (Test-Path -LiteralPath $missingEvidencePath) {
    throw "test requires an absent evidence path: $missingEvidencePath"
}
$missingOutput = & pwsh -NoProfile -File $validator `
    -GateMode DEV_0_D0 `
    -AllowDetachedReviewWorktree `
    -DevEvidencePath $missingEvidencePath `
    -WorktreePath $root 2>&1 | Out-String
$missingExitCode = $LASTEXITCODE
if ($missingExitCode -ne 2) {
    throw "expected missing-DEV-evidence hard-stop exit code 2, got $missingExitCode`n$missingOutput"
}
$missingResult = $missingOutput | ConvertFrom-Json
if (@($missingResult.blockers.code) -notcontains 'DEV_GATE_EVIDENCE_MISSING') {
    throw 'missing DEV evidence file must produce DEV_GATE_EVIDENCE_MISSING'
}

$fullOutput = & pwsh -NoProfile -File $validator `
    -GateMode FULL_FEATURE_PRODUCTION `
    -AllowDetachedReviewWorktree `
    -EvidencePath $missingEvidencePath `
    -WorktreePath $root 2>&1 | Out-String
$fullExitCode = $LASTEXITCODE
if ($fullExitCode -ne 2) {
    throw "expected missing-Full-evidence hard-stop exit code 2, got $fullExitCode`n$fullOutput"
}
$fullResult = $fullOutput | ConvertFrom-Json
foreach ($code in @('DECISION_EVIDENCE_MISSING', 'APPROVED_POLICY_SCOPE_MISSING', 'PRIVACY_OWNER_MISSING', 'APPROVED_BASE_MISSING', 'POLICY_CATALOG_NOT_CLOSED')) {
    if (@($fullResult.blockers.code) -notcontains $code) {
        throw "missing expected Full blocker code: $code"
    }
}

Write-Output 'gate-evidence-validator: DEV_ONLY_AUTHORIZED fixture PASS'
