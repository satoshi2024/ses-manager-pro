$baseFile = ".\src\main\resources\messages.properties"
$baseLines = Get-Content $baseFile -Encoding UTF8
$baseKeys = @{}
foreach ($line in $baseLines) {
    if ($line -match "^([^#=:]+)[=:]") {
        $key = $matches[1].Trim()
        $baseKeys[$key] = $line
    }
}

Get-ChildItem .\src\main\resources\messages_*.properties | ForEach-Object {
    $file = $_.FullName
    $lines = Get-Content $file -Encoding UTF8
    $keys = @{}
    foreach ($line in $lines) {
        if ($line -match "^([^#=:]+)[=:]") {
            $key = $matches[1].Trim()
            $keys[$key] = $true
        }
    }
    
    $newLines = @()
    foreach ($k in $baseKeys.Keys) {
        if (-not $keys.ContainsKey($k)) {
            $newLines += $baseKeys[$k]
        }
    }
    
    if ($newLines.Length -gt 0) {
        $utf8NoBom = New-Object System.Text.UTF8Encoding $false
        [System.IO.File]::AppendAllText($file, "
" + ($newLines -join "
") + "
", $utf8NoBom)
    }
}