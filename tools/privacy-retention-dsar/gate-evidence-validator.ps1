[CmdletBinding()]
param(
    [ValidateSet('DEV_0_D0', 'FULL_FEATURE_PRODUCTION')]
    [string]$GateMode = 'DEV_0_D0',
    [string]$EvidencePath = '.kiro/specs/privacy-retention-dsar/gate-evidence.json',
    [string]$DevEvidencePath = '.kiro/specs/privacy-retention-dsar/dev-gate-evidence.json',
    [string]$InventoryPath = '.kiro/specs/privacy-retention-dsar/pii-inventory.md',
    [string]$SourceCoveragePath = '.kiro/specs/privacy-retention-dsar/source-coverage.md',
    [string]$CoverageScriptPath = 'tools/privacy-retention-dsar/inventory-coverage.ps1',
    [string]$PlanPath = '.kiro/specs/privacy-retention-dsar/plan.md',
    [string]$TasksPath = '.kiro/specs/privacy-retention-dsar/tasks.md',
    [string]$ReviewLedgerPath = '.kiro/specs/privacy-retention-dsar/review-ledger.md',
    [string]$WorktreePath = '.',
    [string]$ExpectedBranch = 'codex/privacy-retention-dsar',
    [string]$ExpectedRemote = 'origin'
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)] [AllowNull()] [object]$Object,
        [Parameter(Mandatory = $true)] [string]$Name
    )

    if ($null -eq $Object) {
        return $null
    }
    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Test-NonEmptyString {
    param([AllowNull()] [object]$Value)

    return $Value -is [string] -and -not [string]::IsNullOrWhiteSpace([string]$Value)
}

function Test-PlaceholderOrUnknown {
    param([AllowNull()] [object]$Value)

    if (-not (Test-NonEmptyString -Value $Value)) {
        return $true
    }
    $normalized = ([string]$Value).Trim().ToUpperInvariant()
    return $normalized -match '^(<[^>]+>|UNKNOWN(?:/BLOCKED)?|BLOCKED|NOT[_ -]?SET|NOT[_ -]?PROVIDED|TBD|TODO|CANDIDATE)$'
}

function Test-Sha256 {
    param([AllowNull()] [object]$Value)

    return (Test-NonEmptyString -Value $Value) -and ([string]$Value -match '^[0-9a-fA-F]{64}$')
}

function Test-StatusClosed {
    param([AllowNull()] [object]$Value)

    if (-not (Test-NonEmptyString -Value $Value)) {
        return $false
    }
    return ([string]$Value).Trim().ToUpperInvariant() -in @('APPROVED', 'CLOSED', 'PASS', 'VERIFIED_CLOSED')
}

function Get-RecordById {
    param(
        [AllowNull()] [object]$Evidence,
        [Parameter(Mandatory = $true)] [string]$Id
    )

    $records = Get-PropertyValue -Object $Evidence -Name 'evidence'
    if ($null -eq $records) {
        return $null
    }
    foreach ($record in @($records)) {
        if ([string](Get-PropertyValue -Object $record -Name 'id') -eq $Id) {
            return $record
        }
    }
    return $null
}

function Add-Blocker {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [System.Collections.Generic.List[object]]$Blockers,
        [Parameter(Mandatory = $true)] [string]$Code,
        [Parameter(Mandatory = $true)] [string]$Reason,
        [string]$EvidenceRequired = ''
    )

    $Blockers.Add([pscustomobject]@{
        code = $Code
        reason = $Reason
        evidenceRequired = $EvidenceRequired
    })
}

function Invoke-ReadOnlyCoverage {
    param(
        [Parameter(Mandatory = $true)] [string]$ScriptPath,
        [Parameter(Mandatory = $true)] [string]$CurrentWorktree
    )

    $previousLocation = (Get-Location).Path
    try {
        Set-Location -LiteralPath $CurrentWorktree
        $output = & pwsh -NoProfile -File $ScriptPath 2>&1 | Out-String
    }
    finally {
        Set-Location -LiteralPath $previousLocation
    }
    $exitCode = $LASTEXITCODE
    $jsonLines = @($output -split "`r?`n" | Where-Object { -not [string]::IsNullOrWhiteSpace($_) })
    $json = $jsonLines -join "`n"
    $parsed = $null
    try {
        $parsed = $json | ConvertFrom-Json
    }
    catch {
        return [pscustomobject]@{
            exitCode = if ($null -eq $exitCode) { 2 } else { $exitCode }
            parseError = $_.Exception.Message
            rawOutput = $output.Trim()
            result = $null
        }
    }
    return [pscustomobject]@{
        exitCode = if ($null -eq $exitCode) { 0 } else { $exitCode }
        parseError = $null
        rawOutput = ''
        result = $parsed
    }
}

function Invoke-Git {
    param(
        [Parameter(Mandatory = $true)] [string]$CurrentWorktree,
        [Parameter(Mandatory = $true)] [string[]]$Arguments
    )

    $value = & git -C $CurrentWorktree @Arguments 2>&1
    if ($LASTEXITCODE -ne 0) {
        throw "git command failed: git -C $CurrentWorktree $($Arguments -join ' ')`n$($value -join "`n")"
    }
    return (($value -join "`n").Trim())
}

$blockers = [System.Collections.Generic.List[object]]::new()
$resolvedWorktree = (Resolve-Path -LiteralPath $WorktreePath).Path
function Resolve-WorktreeArtifactPath {
    param([Parameter(Mandatory = $true)] [string]$Path)

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    return (Join-Path $resolvedWorktree $Path)
}
$EvidencePath = Resolve-WorktreeArtifactPath -Path $EvidencePath
$DevEvidencePath = Resolve-WorktreeArtifactPath -Path $DevEvidencePath
$InventoryPath = Resolve-WorktreeArtifactPath -Path $InventoryPath
$SourceCoveragePath = Resolve-WorktreeArtifactPath -Path $SourceCoveragePath
$CoverageScriptPath = Resolve-WorktreeArtifactPath -Path $CoverageScriptPath
$PlanPath = Resolve-WorktreeArtifactPath -Path $PlanPath
$TasksPath = Resolve-WorktreeArtifactPath -Path $TasksPath
$ReviewLedgerPath = Resolve-WorktreeArtifactPath -Path $ReviewLedgerPath

foreach ($requiredPath in @($InventoryPath, $SourceCoveragePath, $CoverageScriptPath, $PlanPath, $TasksPath, $ReviewLedgerPath)) {
    if (-not (Test-Path -LiteralPath $requiredPath -PathType Leaf)) {
        Add-Blocker -Blockers $blockers -Code 'REQUIRED_ARTIFACT_MISSING' -Reason "required artifact is missing: $requiredPath"
    }
}

$activeEvidencePath = if ($GateMode -eq 'DEV_0_D0') { $DevEvidencePath } else { $EvidencePath }
$evidence = $null
$evidenceExists = Test-Path -LiteralPath $activeEvidencePath -PathType Leaf
if ($evidenceExists) {
    try {
        $evidence = Get-Content -LiteralPath $activeEvidencePath -Raw | ConvertFrom-Json
    }
    catch {
        Add-Blocker -Blockers $blockers -Code 'EVIDENCE_INVALID_JSON' -Reason "evidence file is not valid JSON: $activeEvidencePath"
    }
}
else {
    $missingCode = if ($GateMode -eq 'DEV_0_D0') { 'DEV_GATE_EVIDENCE_MISSING' } else { 'DECISION_EVIDENCE_MISSING' }
    $missingReason = if ($GateMode -eq 'DEV_0_D0') { 'development-only authorization evidence file is absent' } else { 'approved policy/scope, Privacy owner, and approved Base decision evidence file is absent' }
    Add-Blocker -Blockers $blockers -Code $missingCode -Reason $missingReason -EvidenceRequired 'decisionId, decisionSource, ownerRole, formalPrivacyOwner, exact gate status/scope, data boundary, external I/O, providerCallCount, writeCount, destructive operation'
    $evidence = [pscustomobject]@{ schemaVersion = 1; evidence = @() }
}

$schemaVersion = Get-PropertyValue -Object $evidence -Name 'schemaVersion'
if ($schemaVersion -ne 1) {
    Add-Blocker -Blockers $blockers -Code 'EVIDENCE_SCHEMA_INVALID' -Reason 'evidence schemaVersion must be 1'
}

if ($GateMode -eq 'DEV_0_D0') {
    $devGate = Get-PropertyValue -Object $evidence -Name 'devGate'
    $fullGate = Get-PropertyValue -Object $evidence -Name 'fullFeatureProductionGate'
    $expectedDecisionId = 'NF07-DEV-GATE-20260828'
    if ([string](Get-PropertyValue -Object $evidence -Name 'decisionId') -ne $expectedDecisionId) {
        Add-Blocker -Blockers $blockers -Code 'DEV_GATE_DECISION_ID_MISMATCH' -Reason "expected decisionId $expectedDecisionId"
    }
    $expectedAuthority = [ordered]@{
        decisionSource = '依頼者によるdevelopment-only technical authorization'
        ownerRole = 'Project Maintainer（development-only）'
        formalPrivacyOwner = 'UNASSIGNED_UNTIL_PRE_PRODUCTION'
    }
    foreach ($field in $expectedAuthority.Keys) {
        $actualValue = Get-PropertyValue -Object $evidence -Name $field
        if (Test-PlaceholderOrUnknown $actualValue) {
            Add-Blocker -Blockers $blockers -Code 'DEV_GATE_AUTHORITY_FIELD_MISSING' -Reason "development gate authority field is missing or unknown: $field"
        }
        elseif ([string]$actualValue -ne [string]$expectedAuthority[$field]) {
            Add-Blocker -Blockers $blockers -Code 'DEV_GATE_AUTHORITY_FIELD_MISMATCH' -Reason "development gate authority field does not match decision evidence: $field"
        }
    }
    if ($null -eq $devGate -or (Get-PropertyValue -Object $devGate -Name 'status') -ne 'APPROVED_DEV_ONLY') {
        Add-Blocker -Blockers $blockers -Code 'DEV_GATE_NOT_APPROVED' -Reason 'DEV-0/D0 gate is not APPROVED_DEV_ONLY'
    }
    else {
        $scope = @((Get-PropertyValue -Object $devGate -Name 'scope'))
        foreach ($requiredScope in @('Task 0', 'D0', '0.3', '0.5')) {
            if ($scope -notcontains $requiredScope) {
                Add-Blocker -Blockers $blockers -Code 'DEV_GATE_SCOPE_MISSING' -Reason "DEV-0/D0 scope is missing: $requiredScope"
            }
        }
        foreach ($requiredAllowed in @('spec', 'PII inventory', 'offline dry-run', 'coverage/gate validator', 'synthetic/redacted fixture', 'DEV-0/D0 scope-only independent Implementation Review')) {
            if (@((Get-PropertyValue -Object $devGate -Name 'allowed')) -notcontains $requiredAllowed) {
                Add-Blocker -Blockers $blockers -Code 'DEV_GATE_ALLOWED_ACTION_MISSING' -Reason "DEV-0/D0 allowed action is missing: $requiredAllowed"
            }
        }
        foreach ($field in @('data', 'externalIo', 'providerCallCount', 'writeCount', 'destructiveOperation')) {
            if ($null -eq (Get-PropertyValue -Object $devGate -Name $field)) {
                Add-Blocker -Blockers $blockers -Code 'DEV_GATE_BOUNDARY_FIELD_MISSING' -Reason "DEV-0/D0 boundary field is missing: $field"
            }
        }
        if ((Get-PropertyValue -Object $devGate -Name 'data') -ne 'synthetic/redacted only' -or (Get-PropertyValue -Object $devGate -Name 'externalIo') -ne '禁止' -or [int](Get-PropertyValue -Object $devGate -Name 'providerCallCount') -ne 0 -or [int](Get-PropertyValue -Object $devGate -Name 'writeCount') -ne 0 -or (Get-PropertyValue -Object $devGate -Name 'destructiveOperation') -ne '禁止') {
            Add-Blocker -Blockers $blockers -Code 'DEV_GATE_BOUNDARY_NOT_FAIL_CLOSED' -Reason 'DEV-0/D0 data, I/O, provider, write, or destructive boundary is not the authorized safe value'
        }
    }
    if ($null -eq $fullGate -or (Get-PropertyValue -Object $fullGate -Name 'status') -ne 'BLOCKED') {
        Add-Blocker -Blockers $blockers -Code 'FULL_GATE_NOT_BLOCKED' -Reason 'Full Feature / Production Gate must remain BLOCKED'
    }
    else {
        foreach ($prohibited in @('F1-M', '実PII接続', 'DSAR実配布', 'delete/anonymize/restrict writer', '外部provider', 'production flag', 'PR/release')) {
            if (@((Get-PropertyValue -Object $fullGate -Name 'prohibited')) -notcontains $prohibited) {
                Add-Blocker -Blockers $blockers -Code 'FULL_GATE_PROHIBITION_MISSING' -Reason "Full Feature / Production prohibition is missing: $prohibited"
            }
        }
        foreach ($blockingEvidence in @('78 table policy', 'DG-07', 'legal-document-ledger-archive', 'database-backup-recovery', 'enterprise-identity-security', 'recruiting-pipeline', 'ai-feedback-learning G10', '本番運用gate')) {
            if (@((Get-PropertyValue -Object $fullGate -Name 'blockingEvidence')) -notcontains $blockingEvidence) {
                Add-Blocker -Blockers $blockers -Code 'FULL_GATE_BLOCKING_EVIDENCE_MISSING' -Reason "Full Feature / Production blocking evidence is missing: $blockingEvidence"
            }
        }
    }
    if ((Get-PropertyValue -Object $evidence -Name 'legalConclusion') -ne $false -or (Get-PropertyValue -Object $evidence -Name 'productionApproval') -ne $false) {
        Add-Blocker -Blockers $blockers -Code 'DEV_GATE_OVERREACH' -Reason 'development decision must not assert legal conclusion or production approval'
    }
}
else {
    $policyRecord = Get-RecordById -Evidence $evidence -Id 'approved-policy-scope'
    if ($null -eq $policyRecord -or -not (Test-StatusClosed (Get-PropertyValue -Object $policyRecord -Name 'status'))) {
        Add-Blocker -Blockers $blockers -Code 'APPROVED_POLICY_SCOPE_MISSING' -Reason 'approved policy/scope record is missing or not closed' -EvidenceRequired 'status, exact scope, policy version, owner, purpose/legal basis, decisionAt, authority, evidenceRef, evidenceSha256'
    }

    $ownerRecord = Get-RecordById -Evidence $evidence -Id 'privacy-owner'
    if ($null -eq $ownerRecord -or (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $ownerRecord -Name 'owner'))) {
        Add-Blocker -Blockers $blockers -Code 'PRIVACY_OWNER_MISSING' -Reason 'Privacy owner is missing or placeholder/unknown' -EvidenceRequired 'named accountable owner, role, authority, decisionAt, evidenceRef, evidenceSha256'
    }

    $baseRecord = Get-RecordById -Evidence $evidence -Id 'approved-base'
    if ($null -eq $baseRecord -or -not (Test-StatusClosed (Get-PropertyValue -Object $baseRecord -Name 'status')) -or (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $baseRecord -Name 'branch')) -or -not (Test-Sha256 (Get-PropertyValue -Object $baseRecord -Name 'sha'))) {
        Add-Blocker -Blockers $blockers -Code 'APPROVED_BASE_MISSING' -Reason 'approved Base branch/SHA decision evidence is missing, placeholder, or malformed' -EvidenceRequired 'status, exact branch, 64-hex SHA, decisionAt, authority, evidenceRef, evidenceSha256'
    }
}

$coverageInvocation = $null
if (Test-Path -LiteralPath $CoverageScriptPath -PathType Leaf) {
    $coverageInvocation = Invoke-ReadOnlyCoverage -ScriptPath $CoverageScriptPath -CurrentWorktree $resolvedWorktree
    if ($null -eq $coverageInvocation.result) {
        Add-Blocker -Blockers $blockers -Code 'COVERAGE_RESULT_UNREADABLE' -Reason 'read-only coverage scanner did not return parseable JSON'
    }
    else {
        $coverage = $coverageInvocation.result
        $unclassified = [int](Get-PropertyValue -Object $coverage -Name 'privacyCatalogUnclassifiedTableCount')
        $unknown = [int](Get-PropertyValue -Object $coverage -Name 'privacyCatalogUnknownTableCount')
        $coverageStatus = [string](Get-PropertyValue -Object $coverage -Name 'status')
        $missingOrExtra = @(
            'sourceCoverageUnmappedTableCount', 'sourceCoverageMissingColumnCount', 'sourceCoverageExtraColumnCount',
            'providerCoverageMissingCount', 'sourceCoverageExtraProviderCount', 'entityCoverageMissingCount'
        ) | Where-Object { [int](Get-PropertyValue -Object $coverage -Name $_) -ne 0 }
        $missingOrExtra = @($missingOrExtra)
        $structuralCoverageClosed = $unclassified -eq 0 -and $missingOrExtra.Count -eq 0
        if (-not $structuralCoverageClosed) {
            Add-Blocker -Blockers $blockers -Code 'STRUCTURAL_COVERAGE_NOT_CLOSED' -Reason "structural catalog coverage is not eligible: status=$coverageStatus, unclassified=$unclassified, structuralGaps=$($missingOrExtra.Count)" -EvidenceRequired 'unmapped/missing/extra table, column, entity, provider must be zero'
        }
        if ($GateMode -eq 'FULL_FEATURE_PRODUCTION' -and ($coverageStatus -ne 'COVERAGE_EXPLICIT' -or $unknown -ne 0)) {
            Add-Blocker -Blockers $blockers -Code 'POLICY_CATALOG_NOT_CLOSED' -Reason "full feature catalog is not eligible: status=$coverageStatus, policyUnknown=$unknown" -EvidenceRequired 'each table/column/provider owner, purpose, trigger, retention, policy version, hold, disposition, DSAR provider, result evidence; approved policy evidence'
        }
    }
}

$planText = if (Test-Path -LiteralPath $PlanPath -PathType Leaf) { Get-Content -LiteralPath $PlanPath -Raw } else { '' }
$tasksText = if (Test-Path -LiteralPath $TasksPath -PathType Leaf) { Get-Content -LiteralPath $TasksPath -Raw } else { '' }
$reviewLedgerText = if (Test-Path -LiteralPath $ReviewLedgerPath -PathType Leaf) { Get-Content -LiteralPath $ReviewLedgerPath -Raw } else { '' }

if ($planText -notmatch '(?m)DEV-0/D0 PLAN\s*:\s*(?:\x60)?APPROVED_DEV_ONLY') {
    Add-Blocker -Blockers $blockers -Code 'DEV_PLAN_AUTHORIZATION_MISSING' -Reason 'plan does not explicitly bind the DEV-0/D0 APPROVED_DEV_ONLY decision'
}
if ($planText -notmatch '(?m)FULL_FEATURE_PRODUCTION PLAN\s*:\s*(?:\x60)?BLOCKED') {
    Add-Blocker -Blockers $blockers -Code 'FULL_PLAN_NOT_FAIL_CLOSED' -Reason 'plan does not explicitly keep Full Feature / Production BLOCKED'
}
if ($tasksText -notmatch '(?m)^- \[ \] \*\*0\.4 coverage closure') {
    Add-Blocker -Blockers $blockers -Code 'INVENTORY_CLOSURE_NOT_FAIL_CLOSED' -Reason 'task 0.4 is not explicitly incomplete'
}
foreach ($phase in @('F1', 'F2', 'A1', 'A2', 'B1', 'B2', 'M')) {
    if ($tasksText -match "(?m)^- \[x\].*\*\*$phase(?:\s|[^0-9])") {
        Add-Blocker -Blockers $blockers -Code 'IMPLEMENTATION_PHASE_PREMATURELY_CLOSED' -Reason "$phase is marked complete before gates pass"
    }
}
if ($reviewLedgerText -match '(?m)^\|\s*(PLAN|IMPLEMENTATION)\s*\|[^|]*(PASS|FAIL|CONDITIONAL)') {
    Add-Blocker -Blockers $blockers -Code 'IMPLEMENTATION_SELF_RECORDED_REVIEW' -Reason 'implementation review verdict appears in the implementation-owned ledger'
}

$requiredGateIds = @('DG-07', 'legal-document-ledger-archive', 'database-backup-recovery', 'enterprise-identity-security', 'recruiting-pipeline', 'ai-feedback-learning', 'production-disposition-release')
if ($GateMode -eq 'FULL_FEATURE_PRODUCTION') {
    foreach ($gateId in $requiredGateIds) {
        $gate = Get-RecordById -Evidence $evidence -Id $gateId
        if ($null -eq $gate -or -not (Test-StatusClosed (Get-PropertyValue -Object $gate -Name 'status'))) {
            Add-Blocker -Blockers $blockers -Code ("GATE_NOT_CLOSED_" + ($gateId -replace '[^A-Za-z0-9]+', '_').ToUpperInvariant()) -Reason "$gateId evidence is missing or not closed" -EvidenceRequired 'gate status, authority/owner, decisionAt, evidenceRef, evidenceSha256, gate-specific required fields'
        }
    }
}

$dg07 = Get-RecordById -Evidence $evidence -Id 'DG-07'
if ($GateMode -eq 'FULL_FEATURE_PRODUCTION' -and $null -ne $dg07 -and (Test-StatusClosed (Get-PropertyValue -Object $dg07 -Name 'status'))) {
    $dg07Required = @('owner', 'purposeLegalBasis', 'retention', 'policyVersionTrigger', 'holdStartAuthority', 'holdReleaseAuthority', 'separationOfDuties', 'dispositionByTarget', 'dsarIdentityVerification', 'sameNameResolution', 'thirdPartyRedaction', 'scope', 'delivery', 'deadline', 'reopen')
    foreach ($field in $dg07Required) {
        if (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $dg07 -Name $field)) {
            Add-Blocker -Blockers $blockers -Code 'DG07_FIELD_MISSING' -Reason "DG-07 required field is missing or unknown: $field" -EvidenceRequired "DG-07.$field"
        }
    }
}

$backup = Get-RecordById -Evidence $evidence -Id 'database-backup-recovery'
if ($GateMode -eq 'FULL_FEATURE_PRODUCTION' -and $null -ne $backup -and (Test-StatusClosed (Get-PropertyValue -Object $backup -Name 'status'))) {
    foreach ($prodId in 1..8) {
        $field = 'PROD-{0:d3}' -f $prodId
        if (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $backup -Name $field)) {
            Add-Blocker -Blockers $blockers -Code 'BACKUP_PRODUCTION_GATE_MISSING' -Reason "database-backup-recovery $field is missing or unknown" -EvidenceRequired $field
        }
    }
    if (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $backup -Name 'restoreTombstoneReappliedEvidence')) {
        Add-Blocker -Blockers $blockers -Code 'RESTORE_TOMBSTONE_EVIDENCE_MISSING' -Reason 'restore後tombstone再適用のevidenceがない' -EvidenceRequired 'restoreTombstoneReappliedEvidence'
    }
}

$production = Get-RecordById -Evidence $evidence -Id 'production-disposition-release'
if ($GateMode -eq 'FULL_FEATURE_PRODUCTION' -and $null -ne $production -and (Test-StatusClosed (Get-PropertyValue -Object $production -Name 'status'))) {
    foreach ($field in @('featureFlagDefaultOff', 'approvedPolicyAllowList', 'legalOwner', 'runbook', 'monitoring', 'emergencyStop')) {
        if (Test-PlaceholderOrUnknown (Get-PropertyValue -Object $production -Name $field)) {
            Add-Blocker -Blockers $blockers -Code 'PRODUCTION_RELEASE_FIELD_MISSING' -Reason "production release gate field is missing or unknown: $field" -EvidenceRequired "production-disposition-release.$field"
        }
    }
}

$branch = ''
$localHead = ''
$remoteHead = ''
$worktreeStatus = ''
$remoteUrl = ''
try {
    $branch = Invoke-Git -CurrentWorktree $resolvedWorktree -Arguments @('branch', '--show-current')
    $localHead = Invoke-Git -CurrentWorktree $resolvedWorktree -Arguments @('rev-parse', 'HEAD')
    $worktreeStatus = Invoke-Git -CurrentWorktree $resolvedWorktree -Arguments @('status', '--porcelain')
    $remoteUrl = Invoke-Git -CurrentWorktree $resolvedWorktree -Arguments @('config', '--get', "remote.$ExpectedRemote.url")
    $remoteLine = Invoke-Git -CurrentWorktree $resolvedWorktree -Arguments @('ls-remote', $ExpectedRemote, "refs/heads/$ExpectedBranch")
    $remoteHead = ($remoteLine -split "`t")[0].Trim()
    if ($branch -ne $ExpectedBranch) {
        Add-Blocker -Blockers $blockers -Code 'WORKTREE_BRANCH_MISMATCH' -Reason "expected $ExpectedBranch but found $branch"
    }
    if (-not [string]::IsNullOrWhiteSpace($worktreeStatus)) {
        Add-Blocker -Blockers $blockers -Code 'WORKTREE_DIRTY' -Reason 'dedicated worktree has uncommitted changes'
    }
    if ($localHead -ne $remoteHead) {
        Add-Blocker -Blockers $blockers -Code 'REMOTE_HEAD_MISMATCH' -Reason "local HEAD $localHead differs from remote Head $remoteHead"
    }
}
catch {
    Add-Blocker -Blockers $blockers -Code 'GIT_BOUNDARY_UNVERIFIED' -Reason $_.Exception.Message
}

$result = [ordered]@{
    mode = "READ_ONLY_GATE_EVIDENCE_VALIDATION_$GateMode"
    status = if ($blockers.Count -gt 0) { 'HARD_STOP' } elseif ($GateMode -eq 'DEV_0_D0') { 'DEV_ONLY_AUTHORIZED_REQUIRES_INDEPENDENT_REVIEW' } else { 'EVIDENCE_PRESENT_REQUIRES_INDEPENDENT_REVIEW' }
    exitCode = if ($blockers.Count -gt 0) { 2 } else { 0 }
    gateMode = $GateMode
    devScopeAuthorized = $GateMode -eq 'DEV_0_D0' -and $blockers.Count -eq 0
    canStartDevOnlyScope = $GateMode -eq 'DEV_0_D0' -and $blockers.Count -eq 0
    canStartF1M = $false
    canEnableProductionDisposition = $false
    canCallExternalProvider = $false
    canCreatePullRequest = $false
    evidencePath = $activeEvidencePath
    evidencePresent = $evidenceExists
    blockers = @($blockers)
    coverageExitCode = if ($null -eq $coverageInvocation) { $null } else { $coverageInvocation.exitCode }
    coverage = if ($null -eq $coverageInvocation) { $null } else { $coverageInvocation.result }
    git = [ordered]@{
        worktree = $resolvedWorktree
        branch = $branch
        localHead = $localHead
        remote = $ExpectedRemote
        remoteUrl = $remoteUrl
        remoteHead = $remoteHead
        clean = [string]::IsNullOrWhiteSpace($worktreeStatus)
    }
    providerCallCount = 0
    writeCount = 0
}

$result | ConvertTo-Json -Depth 10
if ($blockers.Count -gt 0) {
    exit 2
}
