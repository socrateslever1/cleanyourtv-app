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
$LegacyKey = Join-Path $env:USERPROFILE ".clean-install-android\cleaninstall-producao.jks"
$Dir = Join-Path $env:USERPROFILE ".cleanyourtv"
$LegacyExists = Test-Path $LegacyKey -PathType Leaf
$KeyStore = if ($LegacyExists) { $LegacyKey } else { Join-Path $Dir "cleanyourtv-producao.jks" }
$Alias = if ($LegacyExists) { "cleaninstall" } else { "cleanyourtv" }
$Existing = @(& gh secret list --repo $Repo --json name --jq '.[].name')
if ($LASTEXITCODE -ne 0) { throw "Sem acesso aos Secrets do repositorio." }
if (($Existing -contains "CLEAN_TV_KEYSTORE_B64") -and -not (Test-Path $KeyStore)) {
  throw "Chave ja registrada no GitHub, mas arquivo privado nao encontrado. RESTAURE o backup; gerar nova assinatura quebraria atualizacoes."
}
New-Item -ItemType Directory -Force -Path $Dir | Out-Null
if (-not (Test-Path -LiteralPath $KeyStore)) {
  Write-Host "Criando chave permanente em: $KeyStore"
  Write-Host "Se o keytool pedir a senha da CHAVE, pressione ENTER para reutilizar a senha do keystore."
  & keytool -genkeypair -v -keystore $KeyStore -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=CleanYourTV, OU=Android, O=Individual, C=BR"
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
  $certText = & keytool -list -v -keystore $KeyStore -storepass $Password -alias $Alias
  $fingerprintLine = @($certText | Select-String -Pattern 'SHA256:\s*([0-9a-fA-F:]+)' | Select-Object -First 1)
  if ($fingerprintLine.Count -ne 1 -or -not ($fingerprintLine[0].Line -match 'SHA256:\s*([0-9a-fA-F:]+)')) {
    throw "Nao foi possivel extrair o fingerprint do certificado."
  }
  $fingerprint = $Matches[1].Replace(":", "").ToLowerInvariant()
  if ($fingerprint -notmatch '^[0-9a-f]{64}
  $secrets = @(
     @("CLEAN_TV_KEYSTORE_B64", $encoded),
     @("CLEAN_TV_KEYSTORE_PASSWORD", $Password),
     @("CLEAN_TV_KEY_ALIAS", $Alias),
     @("CLEAN_TV_KEY_PASSWORD", $Password),
     @("CLEAN_TV_CERT_SHA256", $fingerprint)
  )
  foreach ($secret in $secrets) {
    # Envia os bytes exatos pelo STDIN, sem adicionar CRLF ou revelar senha na linha de comando.
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = (Get-Command gh).Source
    $psi.Arguments = "secret set $($secret[0]) --repo $Repo"
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardError = $true
    $psi.CreateNoWindow = $true
    $process = [System.Diagnostics.Process]::Start($psi)
    $process.StandardInput.Write($secret[1])
    $process.StandardInput.Close()
    $diagnostic = $process.StandardError.ReadToEnd()
    $process.WaitForExit()
    if ($process.ExitCode -ne 0) { throw "Falha ao configurar $($secret[0]): $diagnostic" }
    $process.Dispose()
    Write-Host "Configurado: $($secret[0])"
  }
  Write-Host "Certificado PUBLICO da assinatura: $fingerprint"
  & keytool -list -v -keystore $KeyStore -storepass $Password -alias $Alias | Select-String -Pattern "SHA256:"
  Write-Host "Guarde o arquivo .jks e sua senha em backup OFFLINE protegido."
  Write-Host "A primeira instalação com esta nova chave não substitui os APKs antigos de debug; exporte o backup da TV antes de migrar."
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr)
  Remove-Variable Password,SecretSecure,encoded,secrets,fingerprint,certText -ErrorAction SilentlyContinue
}
) { throw "Fingerprint invalido." }
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
