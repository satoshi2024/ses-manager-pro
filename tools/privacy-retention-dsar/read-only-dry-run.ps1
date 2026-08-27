[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [ValidateNotNullOrEmpty()]
    [string]$InputPath
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Get-PropertyValue {
    param(
        [Parameter(Mandatory = $true)] [object]$Object,
        [Parameter(Mandatory = $true)] [string]$Name
    )

    $property = $Object.PSObject.Properties[$Name]
    if ($null -eq $property) {
        return $null
    }
    return $property.Value
}

function Assert-NoRawPiiProperty {
    param(
        [Parameter(Mandatory = $true)] [AllowNull()] [object]$Value,
        [Parameter(Mandatory = $true)] [string]$Path
    )

    $forbiddenNamePattern = 'email|phone|address|name|birth|national|gender|photo|body|content|raw|prompt|untrusted|token|secret|password|extract|parsed|remark|comment|originalfile|storedfile|storagekey|resume|skillsummary|description|worklocation|neareststation|useragent|(^|ip)hash|^ip|subject|recipient|account|bank|private|employeevisible|note|topic|reason'

    if ($null -eq $Value) {
        return
    }

    if ($Value -is [System.Collections.IDictionary]) {
        foreach ($key in $Value.Keys) {
            $normalizedKey = ([string]$key).ToLowerInvariant() -replace '[_-]', ''
            if ($normalizedKey -match $forbiddenNamePattern) {
                throw "raw PII property is not allowed: $Path.$key"
            }
            Assert-NoRawPiiProperty -Value $Value[$key] -Path "$Path.$key"
        }
        return
    }

    if ($Value -is [System.Array]) {
        for ($i = 0; $i -lt $Value.Count; $i++) {
            Assert-NoRawPiiProperty -Value $Value[$i] -Path "$Path[$i]"
        }
        return
    }

    if ($Value -is [pscustomobject]) {
        foreach ($property in $Value.PSObject.Properties) {
            $normalizedName = $property.Name.ToLowerInvariant() -replace '[_-]', ''
            if ($normalizedName -match $forbiddenNamePattern) {
                throw "raw PII property is not allowed: $Path.$($property.Name)"
            }
            Assert-NoRawPiiProperty -Value $property.Value -Path "$Path.$($property.Name)"
        }
    }
}

function Add-Reason {
    param(
        [Parameter(Mandatory = $true)] [AllowEmptyCollection()] [System.Collections.Generic.List[string]]$Reasons,
        [Parameter(Mandatory = $true)] [string]$Reason
    )
    if (-not $Reasons.Contains($Reason)) {
        [void]$Reasons.Add($Reason)
    }
}

if (-not (Test-Path -LiteralPath $InputPath -PathType Leaf)) {
    throw "redacted dry-run input does not exist: $InputPath"
}

$rawInput = Get-Content -LiteralPath $InputPath -Raw -Encoding UTF8
$inputObject = $rawInput | ConvertFrom-Json
Assert-NoRawPiiProperty -Value $inputObject -Path '$'

$asOfText = Get-PropertyValue -Object $inputObject -Name 'asOf'
if ([string]::IsNullOrWhiteSpace([string]$asOfText)) {
    throw 'asOf is required for a reproducible dry-run'
}
try {
    $asOf = [DateTimeOffset]::Parse([string]$asOfText)
} catch {
    throw "asOf is not a valid timestamp: $asOfText"
}

$recordsValue = Get-PropertyValue -Object $inputObject -Name 'records'
if ($null -eq $recordsValue) {
    throw 'records is required'
}
$records = @($recordsValue)

$results = [System.Collections.Generic.List[object]]::new()
$candidateCount = 0
$blockedCount = 0
$unknownCount = 0

for ($index = 0; $index -lt $records.Count; $index++) {
    $record = $records[$index]
    $blockingReasons = [System.Collections.Generic.List[string]]::new()
    $unknownReasons = [System.Collections.Generic.List[string]]::new()

    $candidateKey = [string](Get-PropertyValue -Object $record -Name 'candidateKey')
    if ([string]::IsNullOrWhiteSpace($candidateKey)) {
        $candidateKey = "record-$($index + 1)"
    }
    $dataElementId = [string](Get-PropertyValue -Object $record -Name 'dataElementId')
    if ([string]::IsNullOrWhiteSpace($dataElementId)) {
        Add-Reason -Reasons $unknownReasons -Reason 'DATA_ELEMENT_ID_MISSING'
        $dataElementId = 'unknown'
    }

    $scopeStatus = [string](Get-PropertyValue -Object $record -Name 'scopeStatus')
    $providerScope = [string](Get-PropertyValue -Object $record -Name 'providerScope')
    if ($scopeStatus -eq 'OUT_OF_SCOPE' -or $providerScope -eq 'OUT_OF_SCOPE') {
        Add-Reason -Reasons $blockingReasons -Reason 'SCOPE_OUT_OF_SCOPE_PROVIDER_NOT_CALLED'
    } elseif ($scopeStatus -ne 'IN_SCOPE' -or $providerScope -ne 'IN_SCOPE') {
        Add-Reason -Reasons $unknownReasons -Reason 'SCOPE_OR_PROVIDER_UNKNOWN'
    }

    $identityResolution = [string](Get-PropertyValue -Object $record -Name 'identityResolution')
    if ($identityResolution -eq 'AMBIGUOUS' -or $identityResolution -eq 'UNVERIFIED') {
        Add-Reason -Reasons $blockingReasons -Reason 'IDENTITY_AMBIGUOUS_HUMAN_RESOLUTION_REQUIRED'
    } elseif ($identityResolution -ne 'VERIFIED') {
        Add-Reason -Reasons $unknownReasons -Reason 'IDENTITY_NOT_VERIFIED'
    }

    foreach ($stateName in @('ownerState', 'purposeState', 'triggerState')) {
        $state = [string](Get-PropertyValue -Object $record -Name $stateName)
        if ($state -ne 'CONFIRMED') {
            Add-Reason -Reasons $unknownReasons -Reason "$($stateName.ToUpperInvariant())_UNKNOWN"
        }
    }

    $policyState = [string](Get-PropertyValue -Object $record -Name 'policyState')
    if ($policyState -ne 'APPROVED') {
        Add-Reason -Reasons $unknownReasons -Reason 'RETENTION_POLICY_NOT_APPROVED'
    }

    $retentionUntilValue = Get-PropertyValue -Object $record -Name 'retentionUntil'
    if ($null -eq $retentionUntilValue -or [string]::IsNullOrWhiteSpace([string]$retentionUntilValue)) {
        Add-Reason -Reasons $unknownReasons -Reason 'RETENTION_UNTIL_UNKNOWN'
    } else {
        try {
            $retentionUntil = [DateTime]::ParseExact([string]$retentionUntilValue, 'yyyy-MM-dd', [Globalization.CultureInfo]::InvariantCulture)
            if ($retentionUntil.Date -gt $asOf.Date) {
                Add-Reason -Reasons $unknownReasons -Reason 'RETENTION_NOT_DUE'
            }
        } catch {
            Add-Reason -Reasons $unknownReasons -Reason 'RETENTION_UNTIL_INVALID'
        }
    }

    $holdStatus = [string](Get-PropertyValue -Object $record -Name 'holdStatus')
    if ($holdStatus -eq 'ACTIVE') {
        Add-Reason -Reasons $blockingReasons -Reason 'LEGAL_HOLD_ACTIVE'
    } elseif ($holdStatus -ne 'NONE') {
        Add-Reason -Reasons $unknownReasons -Reason 'HOLD_STATUS_UNKNOWN'
    }

    $legalRetentionStatus = [string](Get-PropertyValue -Object $record -Name 'legalRetentionStatus')
    if ($legalRetentionStatus -eq 'BLOCKED') {
        Add-Reason -Reasons $blockingReasons -Reason 'LEGAL_RETENTION_BLOCKS_DISPOSITION'
    } elseif ($legalRetentionStatus -ne 'CLEAR') {
        Add-Reason -Reasons $unknownReasons -Reason 'LEGAL_RETENTION_UNKNOWN'
    }

    $auditStatus = [string](Get-PropertyValue -Object $record -Name 'auditStatus')
    if ($auditStatus -eq 'PROTECTED') {
        Add-Reason -Reasons $blockingReasons -Reason 'IMMUTABLE_AUDIT_PROTECTED'
    } elseif ($auditStatus -ne 'CLEAR') {
        Add-Reason -Reasons $unknownReasons -Reason 'AUDIT_STATUS_UNKNOWN'
    }

    $activeBusinessBlockerProperty = $record.PSObject.Properties['activeBusinessBlocker']
    if ($null -eq $activeBusinessBlockerProperty) {
        Add-Reason -Reasons $unknownReasons -Reason 'ACTIVE_BUSINESS_BLOCKER_UNKNOWN'
    } elseif ($activeBusinessBlockerProperty.Value -eq $true) {
        Add-Reason -Reasons $blockingReasons -Reason 'ACTIVE_BUSINESS_BLOCKER'
    } elseif ($activeBusinessBlockerProperty.Value -ne $false) {
        Add-Reason -Reasons $unknownReasons -Reason 'ACTIVE_BUSINESS_BLOCKER_INVALID'
    }

    $dispositionMethod = [string](Get-PropertyValue -Object $record -Name 'dispositionMethod')
    $knownDispositionMethods = @(
        'PENDING_HUMAN_APPROVAL_ONLY', 'NO_ACTION', 'LOGIC_DELETE_AFTER_APPROVAL',
        'ANONYMIZE_AFTER_APPROVAL', 'PHYSICAL_DELETE_AFTER_APPROVAL',
        'RESTRICT_AFTER_APPROVAL', 'BINARY_PURGE_AFTER_APPROVAL',
        'REDACT_EXPORT_ONLY'
    )
    if ([string]::IsNullOrWhiteSpace($dispositionMethod)) {
        Add-Reason -Reasons $unknownReasons -Reason 'DISPOSITION_METHOD_UNKNOWN'
    } elseif ($knownDispositionMethods -notcontains $dispositionMethod) {
        Add-Reason -Reasons $unknownReasons -Reason 'DISPOSITION_METHOD_UNSUPPORTED'
    }

    if ($blockingReasons.Count -gt 0) {
        $status = 'BLOCKED'
        $blockedCount++
        $reasons = @($blockingReasons + $unknownReasons)
    } elseif ($unknownReasons.Count -gt 0) {
        $status = 'UNKNOWN'
        $unknownCount++
        $reasons = @($unknownReasons)
    } else {
        $status = 'CANDIDATE'
        $candidateCount++
        $reasons = @('READ_ONLY_ELIGIBLE_CANDIDATE_NO_ACTION_PERFORMED')
    }

    [void]$results.Add([ordered]@{
        candidateKey = $candidateKey
        dataElementId = $dataElementId
        status = $status
        reasons = $reasons
        providerCallCount = 0
        writeCount = 0
    })
}

$sourceHash = (Get-FileHash -LiteralPath $InputPath -Algorithm SHA256).Hash.ToLowerInvariant()
$report = [ordered]@{
    mode = 'NO_WRITE_OFFLINE'
    asOf = $asOf.ToString('o')
    sourceSha256 = $sourceHash
    summary = [ordered]@{
        candidate = $candidateCount
        blocked = $blockedCount
        unknown = $unknownCount
        providerCallCount = 0
        writeCount = 0
    }
    results = @($results)
}

Write-Output ($report | ConvertTo-Json -Depth 8)
