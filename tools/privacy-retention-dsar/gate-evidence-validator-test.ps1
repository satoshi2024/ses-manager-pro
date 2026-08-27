[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$validator = Join-Path $PSScriptRoot 'gate-evidence-validator.ps1'
$fixture = Join-Path $PSScriptRoot 'gate-evidence-missing-fixture.json'
$output = & pwsh -NoProfile -File $validator `
    -GateMode DEV_0_D0 `
    -WorktreePath $root 2>&1 | Out-String
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
if ($result.providerCallCount -ne 0 -or $result.writeCount -ne 0 -or $result.coverage.privacyCatalogUnknownTableCount -ne 78) {
    throw 'DEV-only validator must remain read-only and report the Full policy blocker'
}
if (@($result.blockers).Count -ne 0) {
    throw "unexpected DEV-only blockers: $($result.blockers | ConvertTo-Json -Compress)"
}

$missingEvidencePath = Join-Path $PSScriptRoot 'gate-evidence-not-present.json'
if (Test-Path -LiteralPath $missingEvidencePath) {
    throw "test requires an absent evidence path: $missingEvidencePath"
}
$missingOutput = & pwsh -NoProfile -File $validator `
    -GateMode DEV_0_D0 `
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
