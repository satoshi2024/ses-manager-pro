[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
$validator = Join-Path $PSScriptRoot 'gate-evidence-validator.ps1'
$fixture = Join-Path $PSScriptRoot 'gate-evidence-missing-fixture.json'
$output = & pwsh -NoProfile -File $validator `
    -EvidencePath $fixture `
    -WorktreePath $root 2>&1 | Out-String
$exitCode = $LASTEXITCODE

if ($exitCode -ne 2) {
    throw "expected hard-stop exit code 2, got $exitCode`n$output"
}
$result = $output | ConvertFrom-Json
if ($result.status -ne 'HARD_STOP') {
    throw "expected HARD_STOP, got $($result.status)"
}
if ($result.canStartF1M -ne $false -or $result.canEnableProductionDisposition -ne $false -or $result.canCallExternalProvider -ne $false -or $result.canCreatePullRequest -ne $false) {
    throw 'a blocked evidence packet must not authorize F1-M, production disposition, provider calls, or PR creation'
}
if ($result.providerCallCount -ne 0 -or $result.writeCount -ne 0) {
    throw 'gate validator must be read-only'
}
$requiredCodes = @('APPROVED_POLICY_SCOPE_MISSING', 'PRIVACY_OWNER_MISSING', 'APPROVED_BASE_MISSING', 'POLICY_CATALOG_NOT_CLOSED')
foreach ($code in $requiredCodes) {
    if (@($result.blockers.code) -notcontains $code) {
        throw "missing expected blocker code: $code"
    }
}

$missingEvidencePath = Join-Path $PSScriptRoot 'gate-evidence-not-present.json'
if (Test-Path -LiteralPath $missingEvidencePath) {
    throw "test requires an absent evidence path: $missingEvidencePath"
}
$missingOutput = & pwsh -NoProfile -File $validator `
    -EvidencePath $missingEvidencePath `
    -WorktreePath $root 2>&1 | Out-String
$missingExitCode = $LASTEXITCODE
if ($missingExitCode -ne 2) {
    throw "expected missing-evidence hard-stop exit code 2, got $missingExitCode`n$missingOutput"
}
$missingResult = $missingOutput | ConvertFrom-Json
if (@($missingResult.blockers.code) -notcontains 'DECISION_EVIDENCE_MISSING') {
    throw 'missing evidence file must produce DECISION_EVIDENCE_MISSING'
}

Write-Output 'gate-evidence-validator: HARD_STOP fixture PASS'
