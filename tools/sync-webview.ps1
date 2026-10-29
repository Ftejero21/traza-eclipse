# Compila la webview Angular de nexo-vscode y copia el resultado al plugin.
# Uso (desde traza-eclipse): .\tools\sync-webview.ps1
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$nexo = Join-Path (Split-Path -Parent $root) 'nexo-vscode'
$dest = Join-Path $root 'bundles\com.ftejero21.traza\webview'

npm --prefix (Join-Path $nexo 'webview') run build
if ($LASTEXITCODE -ne 0) { throw 'npm run build ha fallado.' }

$dist = Join-Path $nexo 'dist\webview'
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw "No se encontro $dist\index.html" }

if (Test-Path $dest) { Remove-Item -Recurse -Force $dest }
New-Item -ItemType Directory -Force $dest | Out-Null
Copy-Item -Recurse -Force (Join-Path $dist '*') $dest
Write-Host "Webview sincronizada en $dest"
