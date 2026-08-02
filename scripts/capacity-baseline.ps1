[CmdletBinding()]
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$Username = $(if ($env:LOADTEST_USERNAME) { $env:LOADTEST_USERNAME } else { 'admin' }),
    [string]$Password = $(if ($env:LOADTEST_PASSWORD) { $env:LOADTEST_PASSWORD } else { 'admin123' }),
    [string]$CredentialFile = '',
    [int]$ExpectedMaxConcurrentSessions = 5,
    [switch]$AllowSingleCredentialSessionEviction,
    [switch]$RequireMetrics,
    [ValidateSet('steady', 'login-spike', 'session-eviction')]
    [string]$Scenario = 'steady',
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
$maxStageUsers = [int](($Stages | Measure-Object -Maximum).Maximum)
if ($maxStageUsers -lt 1) {
    throw 'Stagesには1以上の同時ユーザー数を指定してください。'
}

$credentials = @()
if (-not [string]::IsNullOrWhiteSpace($CredentialFile)) {
    if (-not (Test-Path -LiteralPath $CredentialFile -PathType Leaf)) {
        throw "credential fileが見つかりません: $CredentialFile"
    }
    $credentials = @(Import-Csv -LiteralPath $CredentialFile)
    foreach ($credential in $credentials) {
        if ($credential.PSObject.Properties.Name -notcontains 'username' -or
                $credential.PSObject.Properties.Name -notcontains 'password' -or
                [string]::IsNullOrWhiteSpace([string]$credential.username) -or
                [string]::IsNullOrWhiteSpace([string]$credential.password)) {
            throw 'credential CSVはusername,password列を持ち、空値を含まない必要があります。'
        }
    }
    if ($credentials.Count -lt $maxStageUsers) {
        throw "credential数($($credentials.Count))が最大stage($maxStageUsers)未満です。"
    }
    $duplicateUsers = @($credentials | Group-Object username | Where-Object { $_.Count -gt 1 })
    if ($duplicateUsers.Count -gt 0) {
        throw 'credential CSVのusernameはworkerごとに一意である必要があります。'
    }
} else {
    $credentials = @([pscustomobject]@{ username = $Username; password = $Password })
    $evictionAllowed = $Scenario -eq 'session-eviction' -and $AllowSingleCredentialSessionEviction
    if ($maxStageUsers -gt $ExpectedMaxConcurrentSessions -and -not $evictionAllowed) {
        throw "単一credentialのstage($maxStageUsers)がsession上限($ExpectedMaxConcurrentSessions)を超えます。複数credentialを指定するかsession-evictionを明示してください。"
    }
}

if ($Scenario -eq 'session-eviction' -and -not $AllowSingleCredentialSessionEviction) {
    throw 'session-eviction scenarioには-AllowSingleCredentialSessionEvictionが必要です。'
}

if ($CheckOnly) {
    Write-Host "CheckOnly: preflight成功 (scenario=$Scenario, maxStage=$maxStageUsers, credentials=$($credentials.Count))"
    exit 0
}

$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$runDirectory = Join-Path $OutputDirectory $runId
New-Item -ItemType Directory -Path $runDirectory -Force | Out-Null

function Get-EndpointStatus {
    param([string]$Path, $Session, [string]$AuthenticationFailureReason = '')

    $sw = [Diagnostics.Stopwatch]::StartNew()
    if ($Path.StartsWith('/actuator/') -and $null -eq $Session) {
        $sw.Stop()
        $reason = if ([string]::IsNullOrWhiteSpace($AuthenticationFailureReason)) {
            'monitor認証sessionを作成できませんでした'
        } else { $AuthenticationFailureReason }
        return [pscustomobject]@{ Path = $Path; Status = 0; Available = $false; Reason = $reason;
            LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); Error = $reason }
    }
    try {
        $params = @{
            UseBasicParsing = $true
            Uri = [Uri]::new($baseUri, $Path)
            Method = 'GET'
            TimeoutSec = 3
        }
        if ($null -ne $Session) { $params['WebSession'] = $Session }
        $response = Invoke-WebRequest @params
        $sw.Stop()
        $status = [int]$response.StatusCode
        $available = $status -ge 200 -and $status -lt 300
        $reason = if ($available) { '' } else { "HTTP $status" }
        return [pscustomobject]@{ Path = $Path; Status = $status; Available = $available; Reason = $reason;
            LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); Error = $reason }
    } catch {
        $sw.Stop()
        $status = 0
        if ($null -ne $_.Exception.Response) {
            try { $status = [int]$_.Exception.Response.StatusCode } catch { $status = 0 }
        }
        $reason = if ($status -gt 0) { "HTTP $status" } else { $_.Exception.Message }
        return [pscustomobject]@{ Path = $Path; Status = $status; Available = $false; Reason = $reason;
            LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2); Error = $reason }
    }
}

function New-MonitorSession {
    param($Credential)

    $session = New-Object Microsoft.PowerShell.Commands.WebRequestSession
    try {
        $null = Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new($baseUri, '/login')) -Method Get -WebSession $session
        $cookie = @($session.Cookies.GetCookies($baseUri) |
            Where-Object { $_.Name -eq 'XSRF-TOKEN' } | Select-Object -First 1)
        if ($cookie.Count -eq 0) { throw 'monitor login用XSRF-TOKENがありません' }
        $headers = @{ 'X-XSRF-TOKEN' = [Uri]::UnescapeDataString($cookie[0].Value); 'Accept' = 'text/html' }
        $body = @{ username = [string]$Credential.username; password = [string]$Credential.password }
        $response = Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new($baseUri, '/login')) -Method Post `
            -WebSession $session -Headers $headers -Body $body
        $finalUri = $null
        if ($null -ne $response.BaseResponse) {
            if ($null -ne $response.BaseResponse.ResponseUri) {
                $finalUri = $response.BaseResponse.ResponseUri
            } elseif ($null -ne $response.BaseResponse.RequestMessage) {
                $finalUri = $response.BaseResponse.RequestMessage.RequestUri
            }
        }
        if ($null -ne $finalUri -and $finalUri.AbsolutePath -eq '/login') {
            throw 'monitor loginに失敗しました'
        }
        return [pscustomobject]@{ Available = $true; Reason = ''; Session = $session }
    } catch {
        return [pscustomobject]@{ Available = $false; Reason = $_.Exception.Message; Session = $null }
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
    $probes = @($actuatorPaths | ForEach-Object {
        Get-EndpointStatus $_ $monitorAuthentication.Session $monitorAuthentication.Reason
    })
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

    $setup = @($Records | Where-Object { $_.Kind -eq 'setup' })
    $setupErrors = @($setup | Where-Object { $_.ErrorClass -ne 'success-2xx' })
    $authenticated = @($setup | Where-Object { $_.ErrorClass -eq 'success-2xx' })
    $requests = @($Records | Where-Object { $_.Kind -eq 'request' })
    $latencies = @($requests | ForEach-Object { [double]$_.LatencyMs })
    $requestErrors = @($requests | Where-Object { $_.ErrorClass -ne 'success-2xx' })
    $errors = @($setupErrors + $requestErrors)
    $errorGroups = @($errors | Group-Object ErrorClass | Sort-Object Name |
        ForEach-Object { "$($_.Name)=$($_.Count)" })
    $rps = if ($ElapsedSeconds -gt 0) { [math]::Round($requests.Count / $ElapsedSeconds, 2) } else { $null }
    return [pscustomobject]@{
        Scenario = $Scenario
        StageUsers = $Stage
        RequestedUsers = $Stage
        AuthenticatedUsers = $authenticated.Count
        SetupErrors = $setupErrors.Count
        ExportConcurrency = $Concurrency
        Requests = $requests.Count
        P50Ms = Get-Percentile $latencies 50
        P95Ms = Get-Percentile $latencies 95
        P99Ms = Get-Percentile $latencies 99
        ReqPerSec = $rps
        RequestErrors = $requestErrors.Count
        TotalErrors = $errors.Count
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
        [int]$WorkerId,
        $WorkerLoginBarrier,
        $WorkerLoginCompleted,
        $WorkerWorkloadStart,
        [int]$WorkerLoginDelayMs
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
            if ($WorkerLoginDelayMs -gt 0) { Start-Sleep -Milliseconds $WorkerLoginDelayMs }
            if ($null -ne $WorkerLoginBarrier -and
                    -not $WorkerLoginBarrier.SignalAndWait([TimeSpan]::FromSeconds(30))) {
                throw 'login barrierがtimeoutしました'
            }
            $headers = @{ 'X-XSRF-TOKEN' = $token; 'Accept' = 'text/html' }
            $body = @{ username = $WorkerUsername; password = $WorkerPassword }
            $loginResponse = Invoke-WebRequest -UseBasicParsing -Uri ([Uri]::new($workerBaseUri, '/login')) -Method Post -WebSession $session -Headers $headers -Body $body
            $finalUri = $null
            if ($null -ne $loginResponse.BaseResponse) {
                if ($null -ne $loginResponse.BaseResponse.ResponseUri) {
                    $finalUri = $loginResponse.BaseResponse.ResponseUri
                } elseif ($null -ne $loginResponse.BaseResponse.RequestMessage) {
                    $finalUri = $loginResponse.BaseResponse.RequestMessage.RequestUri
                }
            }
            if ($null -ne $finalUri -and $finalUri.AbsolutePath -eq '/login') {
                throw 'ログインに失敗しました'
            }
            $sessionCookie = @($session.Cookies.GetCookies($workerBaseUri) | Where-Object { $_.Name -eq 'JSESSIONID' } | Select-Object -First 1)
            if ($sessionCookie.Count -eq 0) { throw 'ログイン後のJSESSIONID Cookieがありません' }
            $sw.Stop()
            return [pscustomobject]@{
                Success = $true
                Session = $session
                Result = [pscustomobject]@{
                    Kind = 'setup'
                    Scenario = $WorkerScenario
                    WorkerId = $WorkerId
                    Path = '/login'
                    Method = 'POST'
                    Status = 200
                    ErrorClass = 'success-2xx'
                    ErrorMessage = ''
                    LatencyMs = [math]::Round($sw.Elapsed.TotalMilliseconds, 2)
                    TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
                }
            }
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
    $null = $WorkerLoginCompleted.Signal()
    $login.Result
    if (-not $login.Success) {
        return
    }
    if (-not $WorkerWorkloadStart.Wait([TimeSpan]::FromSeconds(60))) {
        [pscustomobject]@{
            Kind = 'request'; Scenario = $WorkerScenario; WorkerId = $WorkerId; Path = '/load-worker';
            Method = 'BARRIER'; Status = 0; ErrorClass = 'load-generator-error';
            ErrorMessage = 'workload barrierがtimeoutしました'; LatencyMs = 0;
            TimestampUtc = (Get-Date).ToUniversalTime().ToString('o')
        }
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
    $records = @()
    $loginBarrier = if ($Scenario -eq 'login-spike' -or $Scenario -eq 'session-eviction') {
        [System.Threading.Barrier]::new($StageUsers)
    } else { $null }
    $loginCompleted = [System.Threading.CountdownEvent]::new($StageUsers)
    $workloadStart = [System.Threading.ManualResetEventSlim]::new($false)
    for ($i = 1; $i -le $StageUsers; $i++) {
        $powershell = [PowerShell]::Create()
        $powershell.RunspacePool = $runspacePool
        $credential = if ($credentials.Count -eq 1) { $credentials[0] } else { $credentials[$i - 1] }
        $loginDelayMs = if ($Scenario -eq 'steady') { ($i - 1) * 150 } else { 0 }
        [void]$powershell.AddScript($worker).AddArgument($BaseUrl).AddArgument([string]$credential.username).AddArgument([string]$credential.password).`
            AddArgument($StageDurationSeconds).AddArgument($ThinkTimeMs).AddArgument($EngineerId).`
            AddArgument([bool]$SkipUpdates).AddArgument($Scenario).AddArgument($ExportPath).AddArgument($i).`
            AddArgument($loginBarrier).AddArgument($loginCompleted).AddArgument($workloadStart).AddArgument($loginDelayMs)
        $workers += [pscustomobject]@{ PowerShell = $powershell; Handle = $powershell.BeginInvoke() }
    }
    if (-not $loginCompleted.Wait([TimeSpan]::FromSeconds(60))) {
        $records += [pscustomobject]@{ Kind = 'setup'; Scenario = $Scenario; WorkerId = 0; Path = '/load-worker';
            Method = 'BARRIER'; Status = 0; ErrorClass = 'load-generator-error';
            ErrorMessage = 'login完了待機がtimeoutしました'; LatencyMs = 0;
            TimestampUtc = (Get-Date).ToUniversalTime().ToString('o') }
    }
    $workloadStart.Set()
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
    if ($null -ne $loginBarrier) { $loginBarrier.Dispose() }
    $loginCompleted.Dispose()
    $workloadStart.Dispose()
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
$monitorAuthentication = New-MonitorSession $credentials[0]
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
    MonitorAuthentication = [pscustomobject]@{
        Available = $monitorAuthentication.Available
        Reason = $monitorAuthentication.Reason
    }
    EndpointProbes = @($monitorProbePaths | ForEach-Object {
        Get-EndpointStatus $_ $monitorAuthentication.Session $monitorAuthentication.Reason
    })
}
$environment | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 (Join-Path $runDirectory 'environment.json')

Write-Host "容量基線 run=$runId output=$runDirectory"
Write-Host (($environment.EndpointProbes | Format-Table -AutoSize | Out-String).TrimEnd())

$allRecords = New-Object System.Collections.Generic.List[object]
$allSummaries = New-Object System.Collections.Generic.List[object]
$monitorSnapshots = New-Object System.Collections.Generic.List[object]

function Copy-ResultRecord {
    param($Record)
    return [pscustomobject]@{
        Kind = [string]$Record.Kind
        Scenario = [string]$Record.Scenario
        WorkerId = [int]$Record.WorkerId
        Path = [string]$Record.Path
        Method = [string]$Record.Method
        Status = [int]$Record.Status
        ErrorClass = [string]$Record.ErrorClass
        ErrorMessage = [string]$Record.ErrorMessage
        LatencyMs = [double]$Record.LatencyMs
        TimestampUtc = [string]$Record.TimestampUtc
    }
}

foreach ($stage in $Stages) {
    $monitorSnapshots.Add((Get-MonitorSnapshot "before-$Scenario-$stage"))
    $normal = Invoke-Stage $stage $Scenario 0
    foreach ($record in $normal.Records) { $allRecords.Add((Copy-ResultRecord $record)) }
    $allSummaries.Add($normal.Summary)
    $monitorSnapshots.Add((Get-MonitorSnapshot "after-$Scenario-$stage"))
    Write-Host ($normal.Summary | Format-List | Out-String).TrimEnd()

    if (-not $SkipExport) {
        $monitorSnapshots.Add((Get-MonitorSnapshot "before-export-$stage-x$ExportConcurrency"))
        $export = Invoke-Stage $ExportConcurrency 'export' $ExportConcurrency $ExportConcurrency
        foreach ($record in $export.Records) { $allRecords.Add((Copy-ResultRecord $record)) }
        $allSummaries.Add($export.Summary)
        $monitorSnapshots.Add((Get-MonitorSnapshot "after-export-$stage-x$ExportConcurrency"))
        Write-Host ($export.Summary | Format-List | Out-String).TrimEnd()
    }
}

$allRecords | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runDirectory 'requests.csv')
$allSummaries | Export-Csv -NoTypeInformation -Encoding UTF8 (Join-Path $runDirectory 'summary.csv')
$monitorSnapshots | ConvertTo-Json -Depth 12 | Set-Content -Encoding UTF8 (Join-Path $runDirectory 'monitor-snapshots.json')

Write-Host "完了: $(Join-Path $runDirectory 'summary.csv')"
if ($RequireMetrics) {
    $metricProbes = @($environment.EndpointProbes | Where-Object { $_.Path -like '/actuator/metrics/*' })
    foreach ($snapshot in $monitorSnapshots) {
        $metricProbes += @($snapshot.Actuator | Where-Object { $_.Path -like '/actuator/metrics/*' })
    }
    $unavailableMetrics = @($metricProbes | Where-Object { -not $_.Available })
    if ($unavailableMetrics.Count -gt 0) {
        $reasons = @($unavailableMetrics | ForEach-Object { "$($_.Path):$($_.Reason)" } | Select-Object -Unique)
        Write-Error ("必須metricsを取得できません: " + ($reasons -join '; '))
        exit 2
    }
}
$totalErrors = ($allSummaries | Measure-Object -Property TotalErrors -Sum).Sum
if ($null -ne $totalErrors -and [int]$totalErrors -gt 0) {
    Write-Error "容量試験でerrorを検出しました: TotalErrors=$totalErrors"
    exit 1
}
exit 0
