#requires -Version 5.1
<# Gera keystore local, salva SOMENTE nos Secrets do GitHub e nunca no repositório. #>
param([switch]$CheckOnly)
$ErrorActionPreference = "Stop"
$Repo = "socrateslever1/cleanyourtv-app"
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) { throw "Instale GitHub CLI: https://cli.github.com/ e execute gh auth login." }
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) { throw "Autentique o GitHub CLI com gh auth login." }
if ($CheckOnly) { & gh secret list --repo $Repo; exit $LASTEXITCODE }
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { throw "Instale JDK 21 e adicione keytool ao PATH." }
$Dir = Join-Path $env:USERPROFILE ".clean-install-android"
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
$KeyStore = Join-Path $Dir "cleaninstall-producao.jks"
$Alias = "cleaninstall"
if (-not (Test-Path -LiteralPath $KeyStore)) {
  Write-Host "Criando chave permanente em: $KeyStore"
  Write-Host "Se o keytool pedir a senha da CHAVE, pressione ENTER para reutilizar a senha do keystore."
  & keytool -genkeypair -v -keystore $KeyStore -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=Clean-install Android, OU=Android, O=Individual, C=BR"
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $KeyStore)) { throw "Falha ao gerar a chave. Nenhum segredo foi alterado." }
} else {
  Write-Warning "A chave já existe e NÃO será substituída."
}
$SecretSecure = Read-Host "Informe a senha do keystore" -AsSecureString
$bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecretSecure)
try {
  $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
  if ([string]::IsNullOrWhiteSpace($Password)) { throw "Senha vazia." }
  & keytool -list -keystore $KeyStore -storepass $Password -alias $Alias | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "Senha incorreta ou chave inválida: nenhum Secret alterado." }
  $encoded = [Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyStore))
  $secrets = @(
     @("CLEAN_TV_KEYSTORE_B64", $encoded),
     @("CLEAN_TV_KEYSTORE_PASSWORD", $Password),
     @("CLEAN_TV_KEY_ALIAS", $Alias),
     @("CLEAN_TV_KEY_PASSWORD", $Password)
  )
  foreach ($secret in $secrets) {
    $secret[1] | & gh secret set $secret[0] --repo $Repo
    if ($LASTEXITCODE -ne 0) { throw "Falha ao configurar $($secret[0])." }
    Write-Host "Configurado: $($secret[0])"
  }
  Write-Host "Certificado PUBLICO da assinatura:"
  & keytool -list -v -keystore $KeyStore -storepass $Password -alias $Alias | Select-String -Pattern "SHA256:"
  Write-Host "Guarde o arquivo .jks e sua senha em backup OFFLINE protegido."
  Write-Host "A primeira instalação com esta nova chave não substitui os APKs antigos de debug; exporte o backup da TV antes de migrar."
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
  Remove-Variable Password,SecretSecure,encoded,secrets -ErrorAction SilentlyContinue
}
