[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = $(if ($env:LOADTEST_USERNAME) { $env:LOADTEST_USERNAME } else { 'admin' }),
    [string]$Password = $(if ($env:LOADTEST_PASSWORD) { $env:LOADTEST_PASSWORD } else { 'admin123' }),
    [int[]]$Stages = @(20, 50, 100),
    [int]$StageDurationSeconds = 1800,
    [int]$ThinkTimeMs = 250,
    [long]$EngineerId = 1,
    [string]$ExportPath = '/api/engineers/export',
    [ValidateRange(1, 2)]
    [int]$ExportConcurrency = 2,
    [string]$OutputDirectory = (Join-Path (Get-Location) 'capacity-baseline-results'),
    [switch]$SkipExport,
    [switch]$SkipUpdates,
    [int]$AppPid = 0,
    [string]$MySqlCli = '',
    [string]$DbHost = '127.0.0.1',
    [int]$DbPort = 3306,
    [string]$DbName = 'ses_manager_db',
    [string]$DbUsername = $(if ($env:DB_USERNAME) { $env:DB_USERNAME } else { 'root' }),
    [string]$DbPassword = $(if ($env:DB_PASSWORD) { $env:DB_PASSWORD } else { '' }),
    [switch]$CheckOnly
)

$ErrorActionPreference = 'Stop'
$baseUri = [Uri]($BaseUrl.TrimEnd('/') + '/')
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDirectory = Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

function Get-EndpointStatus {
    param([string]$Path)

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $handler = New-Object System.Net.Http.HttpClientHandler
    $handler.AllowAutoRedirect = $false
    $client = New-Object System.Net.Http.HttpClient($handler)
    $client.Timeout = [TimeSpan]::FromSeconds(3)
    try {
        $response = $client.GetAsync([Uri]::new($baseUri, $Path)).GetAwaiter().GetResult()
        $sw.Stop()
        return [pscustomobject]@{ Path = $Path; Status = [int]$response.StatusCode; LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); Error = '' }
    } catch {
        $sw.Stop()
        $status = 0
        return [pscustomobject]@{ Path = $Path; Status = $status; LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); Error = $_.Exception.Message }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
}

function Get-AppProcessSnapshot {
    param([int]$ProcessId)

    if ($ProcessId -le 0) {
        return [pscustomobject]@{ Available = $false; Reason = '未指定 -AppPid'; Pid = $null; WorkingSetMb = $null; PrivateMemoryMb = $null; CpuSeconds = $null }
    }
    try {
        $process = Get-Process -Id $ProcessId
        return [pscustomobject]@{
            Available = $true
            Reason = ''
            Pid = $process.Id
            WorkingSetMb = [math]::Round($process.WorkingSet64 / 1MB, 2)
            PrivateMemoryMb = [math]::Round($process.PrivateMemorySize64 / 1MB, 2)
            CpuSeconds = [math]::Round($process.TotalProcessorTime.TotalSeconds, 2)
        }
    } catch {
        return [pscustomobject]@{ Available = $false; Reason = $_.Exception.Message; Pid = $ProcessId; WorkingSetMb = $null; PrivateMemoryMb = $null; CpuSeconds = $null }
    }
}

function Get-MySqlSnapshot {
    param([string]$CliPath)

    if ([string]::IsNullOrWhiteSpace($CliPath)) {
        return [pscustomobject]@{ Available = $false; Reason = '未指定 -MySqlCli'; Values = @{} }
    }
    if (-not (Test-Path -LiteralPath $CliPath)) {
        return [pscustomobject]@{ Available = $false; Reason = "mysql CLI不存在: $CliPath"; Values = @{} }
    }

    $query = @"
SHOW GLOBAL STATUS WHERE Variable_name IN ('Threads_connected','Threads_running','Threads_created','Slow_queries','Questions','Queries');
SHOW GLOBAL VARIABLES WHERE Variable_name IN ('max_connections','long_query_time');
"@
    $oldMysqlPwd = $env:MYSQL_PWD
    try {
        if (-not [string]::IsNullOrEmpty($DbPassword)) {
            $env:MYSQL_PWD = $DbPassword
        }
        $raw = & $CliPath --protocol=TCP -h $DbHost -P $DbPort -u $DbUsername --batch --skip-column-names -e $query 2>&1
        if ($LASTEXITCODE -ne 0) {
            return [pscustomobject]@{ Available = $false; Reason = (($raw | Out-String).Trim()); Values = @{} }
        }
        $values = @{}
        foreach ($line in $raw) {
            $parts = ([string]$line).Split("`t", 2)
            if ($parts.Count -eq 2) {
                $values[$parts[0]] = $parts[1]
            }
        }
        return [pscustomobject]@{ Available = $true; Reason = ''; Values = $values }
    } catch {
        return [pscustomobject]@{ Available = $false; Reason = $_.Exception.Message; Values = @{} }
    } finally {
        $env:MYSQL_PWD = $oldMysqlPwd
    }
}

function Get-MonitorSnapshot {
    param([string]$Label)

    $actuatorPaths = @(
        '/actuator/health',
        '/actuator/metrics/hikaricp.connections.active',
        '/actuator/metrics/hikaricp.connections.idle',
        '/actuator/metrics/hikaricp.connections.pending',
        '/actuator/metrics/tomcat.threads.busy'
    )
    $probes = @($actuatorPaths | ForEach-Object { Get-EndpointStatus $_ })
    return [pscustomobject]@{
        Label = $Label
        TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        AppProcess = Get-AppProcessSnapshot $AppPid
        MySql = Get-MySqlSnapshot $MySqlCli
        Actuator = $probes
    }
}

function Get-Percentile {
    param([double[]]$Values, [double]$Percentile)

    if (-not $Values -or $Values.Count -eq 0) { return $null }
    $sorted = @($Values | Sort-Object)
    if ($sorted.Count -eq 1) { return [math]::Round([double]$sorted[0], 2) }
    $rank = ($Percentile / 100.0) * ($sorted.Count - 1)
    $lower = [math]::Floor($rank)
    $upper = [math]::Ceiling($rank)
    if ($lower -eq $upper) { return [math]::Round([double]$sorted[$lower], 2) }
    $weight = $rank - $lower
    return [math]::Round(([double]$sorted[$lower] + (($sorted[$upper] - $sorted[$lower]) * $weight)), 2)
}

function Get-RequestSummary {
    param(
        [object[]]$Records,
        [string]$Scenario,
        [int]$Stage,
        [int]$Concurrency,
        [double]$ElapsedSeconds
    )

    $requests = @($Records | Where-Object { $_.Kind -eq 'request' })
    $latencies = @($requests | ForEach-Object { [double]$_.LatencyMs })
    $errors = @($requests | Where-Object { $_.ErrorClass -ne 'success-2xx' })
    $errorGroups = @($errors | Group-Object ErrorClass | Sort-Object Name | ForEach-Object { "$($_.Name)=$($_.Count)" })
    $rps = if ($ElapsedSeconds -gt 0) { [math]::Round($requests.Count / $ElapsedSeconds, 2) } else { $null }
    return [pscustomobject]@{
        Scenario = $Scenario
        StageUsers = $Stage
        ExportConcurrency = $Concurrency
        Requests = $requests.Count
        P50Ms = Get-Percentile $latencies 50
        P95Ms = Get-Percentile $latencies 95
        P99Ms = Get-Percentile $latencies 99
        ReqPerSec = $rps
        Errors = $errors.Count
        ErrorClassification = ($errorGroups -join ';')
        ElapsedSeconds = [math]::Round($ElapsedSeconds, 2)
    }
}

$worker = {
    param(
        [string]$WorkerBaseUrl,
        [string]$WorkerUsername,
        [string]$WorkerPassword,
        [int]$WorkerDurationSeconds,
        [int]$WorkerThinkTimeMs,
        [long]$WorkerEngineerId,
        [bool]$WorkerSkipUpdates,
        [string]$WorkerScenario,
        [string]$WorkerExportPath,
        [int]$WorkerId
    )

    $ErrorActionPreference = 'Stop'
    $workerBaseUri = [Uri]($WorkerBaseUrl.TrimEnd('/') + '/')

    function Get-WorkerXsrfToken {
        param($Session)
        $cookie = @($Session.Cookies.GetCookies($workerBaseUri) | Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1)
        if ($cookie.Count -eq 0) { return '' }
        return [Uri]::UnescapeDataString($cookie[0].Value)
    }

    function Convert-WorkerStatus {
        param([int]$Status, [string]$Path)
        if ($Status -ge 200 -and $Status -lt 300) { return 'success-2xx' }
        if ($Status -eq 301 -or $Status -eq 302 -or $Status -eq 303 -or $Status -eq 307 -or $Status -eq 308) { return 'redirect' }
        if ($Status -eq 401) { return 'http-401' }
        if ($Status -eq 403) { return 'http-403-csrf-or-permission' }
        if ($Status -ge 400 -and $Status -lt 500) { return 'http-4xx' }
        if ($Status -ge 500) { return 'http-5xx' }
        return 'unknown-status'
    }

    function Invoke-WorkerRequest {
        param(
            $Session,
            [string]$Method,
            [string]$Path,
            [string]$Body = ''
        )
        $url = [Uri]::new($workerBaseUri, $Path)
        $headers = @{ 'Accept' = 'application/json'; 'X-Requested-With' = 'XMLHttpRequest' }
        $token = Get-WorkerXsrfToken $Session
        if (-not [string]::IsNullOrWhiteSpace($token)) { $headers['X-XSRF-TOKEN'] = $token }
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $status = 0
        $errorClass = 'transport-error'
        $errorMessage = ''
        try {
            if ($Method -eq 'GET') {
                $response = Invoke-WebRequest -UseBasicParsing -Uri $url -Method Get -WebSession $Session -Headers $headers -MaximumRedirection 0
            } else {
                $headers['Content-Type'] = 'application/json'
                $response = Invoke-WebRequest -UseBasicParsing -Uri $url -Method $Method -WebSession $Session -Headers $headers -Body $Body -MaximumRedirection 0
            }
            $status = [int]$response.StatusCode
            $errorClass = Convert-WorkerStatus $status $Path
        } catch {
            $errorMessage = $_.Exception.Message
            if ($_.Exception.Response) {
                $status = [int]$_.Exception.Response.StatusCode
                $errorClass = Convert-WorkerStatus $status $Path
            }
        } finally {
            $sw.Stop()
        }
        return [pscustomobject]@{
            Kind = 'request'
            Scenario = $WorkerScenario
            WorkerId = $WorkerId
            Path = $Path
            Method = $Method
            Status = $status
            ErrorClass = $errorClass
            ErrorMessage = $errorMessage
            LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
            TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        }
    }

    function New-WorkerSession {
        $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
        $sw = [Diagnostics.Stopwatch]::StartNew()
        try {
            $null = Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new($workerBaseUri, '/login')) -Method Get -WebSession $session
            $token = Get-WorkerXsrfToken $session
            if ([string]::IsNullOrWhiteSpace($token)) { throw 'XSRF-TOKEN Cookieが発行されませんでした' }
            $headers = @{ 'X-XSRF-TOKEN' = $token; 'Accept' = 'text/html' }
            $body = @{ username = $WorkerUsername; password = $WorkerPassword }
            $null = Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new($workerBaseUri, '/login')) -Method Post -WebSession $session -Headers $headers -Body $body
            $sessionCookie = @($session.Cookies.GetCookies($workerBaseUri) | Where-Object { $_.Name -eq 'JSESSIONID' } | Select-Object -First 1)
            if ($sessionCookie.Count -eq 0) { throw 'ログイン後のJSESSIONID Cookieがありません' }
            return [pscustomobject]@{ Success = $true; Session = $session; Result = $null }
        } catch {
            $sw.Stop()
            return [pscustomobject]@{
                Success = $false
                Session = $null
                Result = [pscustomobject]@{
                    Kind = 'setup'
                    Scenario = $WorkerScenario
                    WorkerId = $WorkerId
                    Path = '/login'
                    Method = 'POST'
                    Status = 0
                    ErrorClass = 'login-failed'
                    ErrorMessage = $_.Exception.Message
                    LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
                    TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
                }
            }
        }
    }

    $login = New-WorkerSession
    if (-not $login.Success) {
        $login.Result
        return
    }

    if ($WorkerScenario -eq 'export') {
        Invoke-WorkerRequest $login.Session 'GET' $WorkerExportPath
        return
    }

    $normalRequests = @(
        @{ Method = 'GET'; Path = '/api/engineers?current=1&size=20' },
        @{ Method = 'GET'; Path = "/api/engineers/$WorkerEngineerId" },
        @{ Method = 'GET'; Path = '/api/dashboard/summary' },
        @{ Method = 'GET'; Path = '/api/notifications/unread-count' }
    )
    $deadline = [DateTime]::UtcNow.AddSeconds($WorkerDurationSeconds)
    $index = 0
    $requestCount = 0
    while ([DateTime]::UtcNow -lt $deadline) {
        $spec = if (-not $WorkerSkipUpdates -and $requestCount -gt 0 -and ($requestCount % 20) -eq 0) {
            @{ Method = 'PUT'; Path = '/api/notifications/read-all'; Body = '{}' }
        } else {
            $normalRequests[$index % $normalRequests.Count]
        }
        $body = if ($spec.ContainsKey('Body')) { [string]$spec.Body } else { '' }
        Invoke-WorkerRequest $login.Session $spec.Method $spec.Path $body
        $index++
        $requestCount++
        if ($WorkerThinkTimeMs -gt 0) { Start-Sleep -Milliseconds $WorkerThinkTimeMs }
    }
}

function Invoke-Stage {
    param([int]$StageUsers, [string]$Scenario, [int]$ExportParallelism, [int]$ReportStageUsers = $StageUsers)

    $stageStart = Get-Date
    $label = "$Scenario-$StageUsers-$ExportParallelism"
    $runspacePool = [RunspaceFactory]::CreateRunspacePool(1, $StageUsers)
    $runspacePool.Open()
    $workers = @()
    for ($i = 1; $i -le $StageUsers; $i++) {
        $powershell = [PowerShell]::Create()
        $powershell.RunspacePool = $runspacePool
        [void]$powershell.AddScript($worker).AddArgument($BaseUrl).AddArgument($Username).AddArgument($Password).`
            AddArgument($StageDurationSeconds).AddArgument($ThinkTimeMs).AddArgument($EngineerId).`
            AddArgument([bool]$SkipUpdates).AddArgument($Scenario).AddArgument($ExportPath).AddArgument($i)
        $workers += [pscustomobject]@{ PowerShell = $powershell; Handle = $powershell.BeginInvoke() }
    }
    $records = @()
    foreach ($workerHandle in $workers) {
        try {
            $records += @($workerHandle.PowerShell.EndInvoke($workerHandle.Handle))
        } catch {
            $records += [pscustomobject]@{ Kind = 'setup'; Scenario = $Scenario; WorkerId = 0; Path = '/load-worker';
                Method = 'RUNSPACE'; Status = 0; ErrorClass = 'load-generator-error'; ErrorMessage = $_.Exception.Message;
                LatencyMs = 0; TimestampUtc = (Get-Date).ToUniversalTime().ToString('o') }
        } finally {
            $workerHandle.PowerShell.Dispose()
        }
    }
    $runspacePool.Close()
    $runspacePool.Dispose()
    $elapsed = ((Get-Date) - $stageStart).TotalSeconds
    $summary = Get-RequestSummary $records $Scenario $ReportStageUsers $ExportParallelism $elapsed
    return [pscustomobject]@{ Records = $records; Summary = $summary }
}

$monitorProbePaths = @(
    '/login',
    '/actuator/health',
    '/actuator/metrics/hikaricp.connections.active',
    '/actuator/metrics/hikaricp.connections.idle',
    '/actuator/metrics/hikaricp.connections.pending',
    '/actuator/metrics/tomcat.threads.busy'
)
$environment = [ordered]@{
    RunId = $runId
    TimestampLocal = (Get-Date).ToString('o')
    TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
    ComputerName = $env:COMPUTERNAME
    PowerShell = $PSVersionTable.PSVersion.ToString()
    BaseUrl = $BaseUrl
    Stages = $Stages
    StageDurationSeconds = $StageDurationSeconds
    ThinkTimeMs = $ThinkTimeMs
    ExportPath = $ExportPath
    ExportConcurrency = $ExportConcurrency
    AppProcess = Get-AppProcessSnapshot $AppPid
    MySql = Get-MySqlSnapshot $MySqlCli
    EndpointProbes = @($monitorProbePaths | ForEach-Object { Get-EndpointStatus $_ })
}
$environment | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $runDirectory 'environment.json')

Write-Host "容量基線 run=$runId output=$runDirectory"
Write-Host (($environment.EndpointProbes | Format-Table -AutoSize | Out-String).TrimEnd())

if ($CheckOnly) {
    Write-Host 'CheckOnly: 負荷試験は実行しません。'
    exit 0
}

$allRecords = New-Object System.Collections.Generic.List[object]
$allSummaries = New-Object System.Collections.Generic.List[object]
$monitorSnapshots = New-Object System.Collections.Generic.List[object]

foreach ($stage in $Stages) {
    $monitorSnapshots.Add((Get-MonitorSnapshot "before-normal-$stage"))
    $normal = Invoke-Stage $stage 'normal' 0
    foreach ($record in $normal.Records) { $allRecords.Add($record) }
    $allSummaries.Add($normal.Summary)
    $monitorSnapshots.Add((Get-MonitorSnapshot "after-normal-$stage"))
    Write-Host ($normal.Summary | Format-List | Out-String).TrimEnd()

    if (-not $SkipExport) {
        $monitorSnapshots.Add((Get-MonitorSnapshot "before-export-$stage-x$ExportConcurrency"))
        $export = Invoke-Stage $ExportConcurrency 'export' $ExportConcurrency $stage
        foreach ($record in $export.Records) { $allRecords.Add($record) }
        $allSummaries.Add($export.Summary)
        $monitorSnapshots.Add((Get-MonitorSnapshot "after-export-$stage-x$ExportConcurrency"))
        Write-Host ($export.Summary | Format-List | Out-String).TrimEnd()
    }
}

$allRecords | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runDirectory 'requests.csv')
$allSummaries | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runDirectory 'summary.csv')
$monitorSnapshots | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $runDirectory 'monitor-snapshots.json')

Write-Host "完了: $(Join-Path $runDirectory 'summary.csv')"
