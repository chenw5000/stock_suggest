# Build and redeploy StockSugg WAR to Apache Tomcat 10.1.x
# Usage: .\scripts\deploy\deploy.ps1 [-TomcatHome C:\apache-tomcat-10.1.57] [-Port 7070] [-SkipBuild]

param(
    [string]$TomcatHome = $env:CATALINA_HOME,
    [int]$Port = 7070,
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"
$RepoRoot = Resolve-Path (Join-Path $PSScriptRoot "..\..")
Set-Location $RepoRoot

if (-not $TomcatHome -or -not (Test-Path $TomcatHome)) {
    Write-Error "Tomcat home not found. Pass -TomcatHome or set CATALINA_HOME."
}

$env:CATALINA_HOME = $TomcatHome
$env:CATALINA_BASE = $TomcatHome

Write-Host "Repo:    $RepoRoot"
Write-Host "Tomcat:  $TomcatHome"
Write-Host "Port:    $Port"

if (-not $SkipBuild) {
    Write-Host "Building WAR..."
    mvn -DskipTests package
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

$War = Join-Path $RepoRoot "target\stocksugg.war"
if (-not (Test-Path $War)) {
    Write-Error "Missing $War — run mvn -DskipTests package first."
}

Write-Host "Stopping Tomcat..."
& "$TomcatHome\bin\shutdown.bat" 2>$null
Start-Sleep -Seconds 6

$pids = (netstat -ano | Select-String ":$Port\s" | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique)
foreach ($p in $pids) {
    if ($p -match '^\d+$' -and [int]$p -gt 0) {
        Write-Host "Killing process on port $Port (PID $p)"
        taskkill /PID $p /F 2>$null
    }
}
Start-Sleep -Seconds 2

$Webapps = Join-Path $TomcatHome "webapps"
Remove-Item -Recurse -Force (Join-Path $Webapps "stocksugg") -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $Webapps "stocksugg.war") -ErrorAction SilentlyContinue
Remove-Item -Force (Join-Path $Webapps "stocksugg.war.failed") -ErrorAction SilentlyContinue

Write-Host "Copying WAR..."
Copy-Item -Force $War (Join-Path $Webapps "stocksugg.war")

Write-Host "Starting Tomcat..."
& "$TomcatHome\bin\startup.bat"

$HealthUrl = "http://localhost:$Port/stocksugg/health"
Write-Host "Waiting for $HealthUrl ..."
$ok = $false
for ($i = 0; $i -lt 40; $i++) {
    Start-Sleep -Seconds 2
    try {
        $r = Invoke-WebRequest -Uri $HealthUrl -UseBasicParsing -TimeoutSec 3
        Write-Host "Health: $($r.StatusCode) $($r.Content)"
        $ok = $true
        break
    } catch {
        if ($i % 5 -eq 0) { Write-Host "  wait $($i + 1)..." }
    }
}

if (-not $ok) {
    Write-Error "Health check failed at $HealthUrl"
}
Write-Host "Deploy complete."
