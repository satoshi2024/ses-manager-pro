# Real Browser Execution & Manual Step Runner for Spec S09 (order-acceptance-workflow)
# Execution Usage:
#   .\browser-runner.ps1 -Viewport "desktop" -Role "admin" -TargetId 101 -LaunchChrome

param (
    [string]$Viewport = "desktop",
    [string]$Role = "admin",
    [long]$TargetId = 101,
    [string]$WorkMonth = "2026-07",
    [switch]$LaunchChrome
)

Write-Host "=========================================================="
Write-Host "Browser Acceptance Test Verification & Step Runner (S09)"
Write-Host "Viewport: $Viewport | Role: $Role | Target ID: $TargetId | Month: $WorkMonth"
Write-Host "=========================================================="

$url = "http://localhost:8080/acceptance?workMonth=$WorkMonth&acceptanceId=$TargetId"

if ($LaunchChrome) {
    $chromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
    if (-not (Test-Path $chromePath)) {
        $chromePath = "C:\Program Files (x86)\Google\Chrome\Application\chrome.exe"
    }
    
    if (Test-Path $chromePath) {
        $windowSize = if ($Viewport -eq "mobile") { "--window-size=390,844" } else { "--window-size=1920,1080" }
        Write-Host "Launching Google Chrome at $url..."
        Start-Process $chromePath -ArgumentList "$windowSize", "$url"
    } else {
        Write-Host "Chrome executable not found at standard path. Launching default browser..."
        Start-Process $url
    }
} else {
    Write-Host "Manual Steps for Browser Replay:"
    Write-Host "1. Start local server: .\apache-maven-3.9.6\bin\mvn spring-boot:run"
    Write-Host "2. Login as '$Role' (http://localhost:8080/login)"
    Write-Host "3. Navigate to: $url"
    Write-Host "4. Verify table row tr[data-acceptance-id='$TargetId'] has class 'table-warning' and scrolled into view."
    Write-Host "5. Verify Chrome DevTools Console has 0 errors."
}

Write-Host "Step Runner Execution Complete."
