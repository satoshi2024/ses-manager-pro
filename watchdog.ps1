# watchdog.ps1
# .opencode/task_bridge.json をポーリングし、current_turn に応じて
# impl(3000) / review(3001) の opencode serve へタスクを自動委譲する。
#
# 使い方: powershell -ExecutionPolicy Bypass -File watchdog.ps1

$ErrorActionPreference = 'Stop'

$ProjectRoot       = Split-Path -Parent $MyInvocation.MyCommand.Path
$BridgePath        = Join-Path $ProjectRoot '.opencode\task_bridge.json'
$TurnPorts         = @{ 'impl' = 3000; 'review' = 3001 }
$TurnMessages      = @{
    'impl'   = '実装ターンです。.opencode/task_bridge.json を読み、task_description に従って実装を進めてください。完了後は impl_summary にまとめを書き、current_turn を review に変更してください。'
    'review' = 'レビューターンです。.opencode/task_bridge.json と Git の変更を読み、review_criteria に従ってレビューしてください。全部合格なら status を DONE に、不合格なら review_feedback に戻し意見を書いて current_turn を impl に変更してください。'
}
$TaskTimeoutSec    = 180   # タスク実行のタイムアウト（秒）
$PollIntervalSec   = 5     # ポーリング間隔（秒）
$SessionTimeoutSec = 15    # session 作成 API のタイムアウト（秒）
$MaxSessionRetry   = 3     # session 作成のリトライ回数

function TimeStamp { Get-Date -Format 'yyyy-MM-dd HH:mm:ss' }

function Log {
    param([string]$Level, [string]$Message)
    Write-Host ("[{0}] [{1}] {2}" -f (TimeStamp), $Level, $Message)
}

function Read-Bridge {
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $text = [System.IO.File]::ReadAllText($BridgePath, [System.Text.Encoding]::UTF8)
            return ($text | ConvertFrom-Json)
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $null
}

function Read-BridgeRaw {
    try {
        return [System.IO.File]::ReadAllText($BridgePath, [System.Text.Encoding]::UTF8)
    } catch {
        return $null
    }
}

function Set-BridgeStatus {
    param([string]$Status)
    for ($i = 0; $i -lt 3; $i++) {
        try {
            $text = [System.IO.File]::ReadAllText($BridgePath, [System.Text.Encoding]::UTF8)
            $new  = [System.Text.RegularExpressions.Regex]::Replace(
                $text,
                '"status"\s*:\s*"[^"]*"',
                ('"status": "' + $Status + '"')
            )
            [System.IO.File]::WriteAllText($BridgePath, $new, (New-Object System.Text.UTF8Encoding($false)))
            return $true
        } catch {
            Start-Sleep -Milliseconds 500
        }
    }
    return $false
}

# サーバー認証ヘッダー（OPENCODE_SERVER_PASSWORD が設定されている場合のみ Basic 認証）
function Get-AuthHeader {
    if ($env:OPENCODE_SERVER_PASSWORD) {
        $user  = if ($env:OPENCODE_SERVER_USERNAME) { $env:OPENCODE_SERVER_USERNAME } else { 'opencode' }
        $token = [Convert]::ToBase64String([System.Text.Encoding]::UTF8.GetBytes("${user}:$($env:OPENCODE_SERVER_PASSWORD)"))
        return (New-Object System.Net.Http.Headers.AuthenticationHeaderValue('Basic', $token))
    }
    return $null
}

# opencode serve の API で新しい Session を自動作成する
function New-ServerSession {
    param([int]$Port)
    $client = New-Object System.Net.Http.HttpClient
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($SessionTimeoutSec)
        $auth = Get-AuthHeader
        if ($auth) { $client.DefaultRequestHeaders.Authorization = $auth }
        $body    = '{}'
        $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
        $resp    = $client.PostAsync("http://localhost:$Port/session", $content).GetAwaiter().GetResult()
        $text    = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        if (-not $resp.IsSuccessStatusCode) {
            throw "POST /session -> $([int]$resp.StatusCode): $text"
        }
        $data = $text | ConvertFrom-Json
        return $data.id
    } finally {
        $client.Dispose()
    }
}

# 指定 Session にタスクを送信し、完了（JSON レスポンス取得）まで待つ。
# 180 秒の Timeout 保護付き。完了後はタスク結果をログに残す。
function Send-TurnMessage {
    param([int]$Port, [string]$SessionId, [string]$Message)
    $client = New-Object System.Net.Http.HttpClient
    try {
        $client.Timeout = [TimeSpan]::FromSeconds($TaskTimeoutSec)
        $auth = Get-AuthHeader
        if ($auth) { $client.DefaultRequestHeaders.Authorization = $auth }
        $body    = @{ parts = @(@{ type = 'text'; text = $Message }) } | ConvertTo-Json -Depth 5
        $content = New-Object System.Net.Http.StringContent($body, [System.Text.Encoding]::UTF8, 'application/json')
        $resp    = $client.PostAsync("http://localhost:$Port/session/$SessionId/message", $content).GetAwaiter().GetResult()
        $resp.EnsureSuccessStatusCode()
        $text = $resp.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        Log 'INFO' ("タスク応答取得（{0} bytes）" -f $text.Length)
        return $true
    } catch {
        if ($_.Exception.Message -match 'タイムアウト|TaskCanceled|Timeout') {
            throw "タスクが ${TaskTimeoutSec} 秒でタイムアウトしました: $($_.Exception.Message)"
        }
        throw $_.Exception
    } finally {
        $client.Dispose()
    }
}

function Invoke-Turn {
    param([string]$Turn)
    $port = $TurnPorts[$Turn]
    $sessionId = $null
    for ($attempt = 1; $attempt -le $MaxSessionRetry; $attempt++) {
        try {
            $sessionId = New-ServerSession -Port $port
            break
        } catch {
            Log 'WARN' ("session 作成失敗 ($attempt/$MaxSessionRetry): {0}" -f $_.Exception.Message)
            if ($attempt -lt $MaxSessionRetry) { Start-Sleep -Seconds 5 }
        }
    }
    if (-not $sessionId) {
        Log 'ERROR' "port $port で session を作成できませんでした。opencode serve の起動を確認してください。"
        return $false
    }
    Log 'INFO' "session=$sessionId turn=$turn port=$port"
    $message = $TurnMessages[$Turn]
    if (-not $message) { $message = 'Read .opencode/task_bridge.json and follow its instructions.' }
    try {
        return (Send-TurnMessage -Port $port -SessionId $sessionId -Message $message)
    } catch {
        Log 'ERROR' ("タスク実行に失敗: {0}" -f $_.Exception.Message)
        return $false
    }
}

Log 'INFO' "watchdog 開始: $BridgePath"
Log 'INFO' 'impl=3000 / review=3001 / timeout=180s / poll=5s / Ctrl+C で停止'

$lastDispatchedText = $null
while ($true) {
    $bridge = Read-Bridge
    $raw    = Read-BridgeRaw
    if ($null -eq $bridge) {
        Log 'WARN' 'task_bridge.json を読み込めません（次のポーリングで再試行）'
    }
    elseif ($bridge.status -ne 'DONE') {
        $turn = $bridge.current_turn
        $changed = ($raw -ne $lastDispatchedText)
        if ($bridge.status -eq 'PENDING' -and $changed) {
            if ($null -eq $TurnPorts[$turn]) {
                Log 'ERROR' "不明な current_turn です: $turn"
            } else {
                Log 'INFO' "タスク委譲を開始: turn=$turn"
                Set-BridgeStatus 'RUNNING' | Out-Null
                $dispatchText = Read-BridgeRaw
                $ok = Invoke-Turn -Turn $turn
                $after = Read-Bridge
                if ($ok -and $after -and $after.current_turn -ne $turn) {
                    Log 'INFO' ("ターン切替を検出: {0} -> {1} (status={2})" -f $turn, $after.current_turn, $after.status)
                    if ($after.status -ne 'DONE') { Set-BridgeStatus 'PENDING' | Out-Null }
                    $lastDispatchedText = $dispatchText
                } else {
                    Log 'WARN' '進捗なし（タイムアウト/エラー）。status は RUNNING のまま保持します。task_bridge.json を確認してください。'
                    $lastDispatchedText = Read-BridgeRaw
                }
            }
        }
    }
    Start-Sleep -Seconds $PollIntervalSec
}
