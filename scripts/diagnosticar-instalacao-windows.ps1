#requires -Version 5.1
<#
Diagnostico de "App nao instalado" no Android.
REGRAS: nunca desinstala o aplicativo, nunca executa pm clear nem adb uninstall.
A opcao -TentarAtualizar e opt-in e tenta adb install -r (preserva dados).
#>
param(
  [Parameter(Mandatory=$true)][string]$Apk,
  [switch]$TentarAtualizar
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath $Apk -PathType Leaf)) { throw "APK nao encontrado: $Apk" }
$cmd = Get-Command adb -ErrorAction SilentlyContinue
$Adb = if ($cmd) { $cmd.Source } else { $null }
if (-not $Adb) {
  $candidate = Join-Path (Get-Location) "adb.exe"
  if (Test-Path $candidate) { $Adb=$candidate }
}
if (-not $Adb) { throw "adb.exe nao encontrado. Execute no C:\platform-tools ou adicione ao PATH." }
& $Adb -d get-state
if ($LASTEXITCODE -ne 0) { throw "Conecte o celular via USB, habilite depuracao e autorize este PC." }
$model = (& $Adb -d shell getprop ro.product.model) -join ""
Write-Host "Dispositivo USB: $model"
Write-Host "Pacote existente: tv.cleanyour.app"
& $Adb -d shell dumpsys package tv.cleanyour.app | Select-String -Pattern 'versionCode=|versionName=|Signing Details|signatures'
Write-Host "APK escolhido: $Apk"
if ($TentarAtualizar) {
  Write-Warning "Tentativa de atualizacao sobre o app existente, SEM desinstalar e SEM limpar dados."
  & $Adb -d install -r "$Apk"
  if ($LASTEXITCODE -ne 0) {
    Write-Host "Possiveis codigos: INSTALL_FAILED_UPDATE_INCOMPATIBLE (certificado diferente), INSTALL_FAILED_VERSION_DOWNGRADE (versao anterior), INSTALL_FAILED_INVALID_APK (pacote invalido), ou restricoes do sistema." -ForegroundColor Yellow
    exit 2
  }
  Write-Host "APK atualizado sem desinstalacao."
} else {
  Write-Host "Modo SOMENTE LEITURA. Use -TentarAtualizar para mostrar o erro exato do Android sem desinstalar."
}
