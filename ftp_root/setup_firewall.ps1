# LinkApp Firewall Configuration Script
# This script opens ports 8765 (WebSocket) and 2121 (FTP) for LinkApp to work correctly in the local network.

$WebSocketPort = 8765
$FtpPort = 2121

Write-Host "Configuring Windows Firewall for LinkApp..." -ForegroundColor Cyan

# Check for Administrator privileges
if (-not ([Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    Write-Warning "This script must be run as Administrator!"
    Exit
}

# Add Rule for WebSocket (TCP)
Write-Host "Adding rule for LinkApp WebSocket (Port $WebSocketPort)..."
New-NetFirewallRule -DisplayName "LinkApp WebSocket" -Direction Inbound -LocalPort $WebSocketPort -Protocol TCP -Action Allow -Force

# Add Rule for FTP (TCP)
Write-Host "Adding rule for LinkApp FTP (Port $FtpPort)..."
New-NetFirewallRule -DisplayName "LinkApp FTP" -Direction Inbound -LocalPort $FtpPort -Protocol TCP -Action Allow -Force

Write-Host "Done! LinkApp ports are now open." -ForegroundColor Green
