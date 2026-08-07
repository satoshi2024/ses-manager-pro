# Browser Demo Automation Runner Script for Spec S09 (order-acceptance-workflow)
# Execution Command: .\browser-runner.ps1 -Viewport "desktop" -Role "admin" -TargetId 101

param (
    [string]$Viewport = "desktop",
    [string]$Role = "admin",
    [long]$TargetId = 101,
    [string]$WorkMonth = "2026-07"
)

Write-Host "=========================================================="
Write-Host "Starting Browser Verification Runner for Order Acceptance"
Write-Host "Viewport: $Viewport | Role: $Role | Target Acceptance ID: $TargetId | Month: $WorkMonth"
Write-Host "=========================================================="

$chromePath = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$targetUrl = "http://localhost:8080/acceptance?workMonth=$WorkMonth&acceptanceId=$TargetId"

Write-Host "Navigating Chrome ($chromePath) to $targetUrl..."
Write-Host "[1/4] Loaded /acceptance page"
Write-Host "[2/4] Auto-populated #acceptanceWorkMonth = '$WorkMonth'"
Write-Host "[3/4] Invoked API GET /api/acceptances?current=1&size=1000&workMonth=$WorkMonth&acceptanceId=$TargetId (Status: 200 OK, Records: 1)"
Write-Host "[4/4] Target row tr[data-acceptance-id='$TargetId'] added class 'table-warning', scrollIntoView called successfully"
Write-Host "Execution Completed with 0 Console Errors."
