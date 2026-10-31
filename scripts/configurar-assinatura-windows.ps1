#requires -Version 5.1
<#
  Configuração ÚNICA da assinatura digital do CleanYourTV.
  A chave fica em seu Windows e somente os segredos criptografados vão ao GitHub.
  Nunca envie o keystore ou a senha em mensagens, commits ou releases.
#>
param([switch]$CheckOnly)
$ErrorActionPreference = "Stop"
$Repo = "socrateslever1/cleanyourtv-app"
$Required = @("CLEAN_TV_KEYSTORE_B64", "CLEAN_TV_KEYSTORE_PASSWORD", "CLEAN_TV_KEY_ALIAS",
              "CLEAN_TV_KEY_PASSWORD", "CLEAN_TV_CERT_SHA256")
if (-not (Get-Command gh -ErrorAction SilentlyContinue)) {
  throw "Instale GitHub CLI (https://cli.github.com), depois execute gh auth login."
}
& gh auth status --hostname github.com
if ($LASTEXITCODE -ne 0) { throw "GitHub CLI não autenticado: gh auth login." }
$Existing = @(& gh secret list --repo $Repo --json name --jq '.[].name')
if ($LASTEXITCODE -ne 0) { throw "Não foi possível acessar Secrets do repositório." }
if ($CheckOnly) {
  foreach ($name in $Required) {
    $state = if ($Existing -contains $name) { "CONFIGURADO" } else { "AUSENTE" }
    Write-Host "$name : $state"
  }
  exit 0
}
if (-not (Get-Command keytool -ErrorAction SilentlyContinue)) { throw "Instale JDK 21 (keytool)." }

$Legacy = Join-Path $env:USERPROFILE ".clean-install-android\cleaninstall-producao.jks"
$NewDir = Join-Path $env:USERPROFILE ".cleanyourtv"
$NewFile = Join-Path $NewDir "cleanyourtv-producao.jks"
$UseLegacy = Test-Path -LiteralPath $Legacy -PathType Leaf
$KeyStore = if ($UseLegacy) { $Legacy } else { $NewFile }
$Alias = if ($UseLegacy) { "cleaninstall" } else { "cleanyourtv" }
if (($Existing -contains "CLEAN_TV_KEYSTORE_B64") -and
    -not (Test-Path -LiteralPath $KeyStore -PathType Leaf)) {
  throw "Já existe chave nos Secrets, mas o keystore local não foi encontrado. RESTAURE o backup; não crie outra chave."
}
if (-not (Test-Path -LiteralPath $KeyStore -PathType Leaf)) {
  New-Item -ItemType Directory -Path $NewDir -Force | Out-Null
  Write-Host "Criando keystore privado: $KeyStore"
  & keytool -genkeypair -v -keystore $KeyStore -alias $Alias -keyalg RSA -keysize 3072 -validity 10000 -dname "CN=CleanYourTV, OU=Android, O=Personal, C=BR"
  if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $KeyStore)) {
    throw "Falha ao gerar chave. Nenhum Secret foi alterado."
  }
} else {
  Write-Host "Reutilizando chave existente: $KeyStore"
}
$SecretPassword = Read-Host "Digite a senha do keystore (não compartilhe)" -AsSecureString
$Bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($SecretPassword)
try {
  $Password = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($Bstr)
  if ([string]::IsNullOrWhiteSpace($Password)) { throw "Senha vazia." }
  $CertText = @(& keytool -list -v -keystore $KeyStore -storepass $Password -alias $Alias)
  if ($LASTEXITCODE -ne 0) { throw "Senha ou keystore incorreto. Nenhum Secret alterado." }
  $Candidate = @($CertText | Select-String -Pattern 'SHA256:\s*([0-9A-Fa-f:]+)' | Select-Object -First 1)
  if ($Candidate.Count -ne 1) { throw "Não foi possível identificar SHA-256 do certificado." }
  $Line = $Candidate[0].Line
  if (-not ($Line -match 'SHA256:\s*([0-9A-Fa-f:]+)')) { throw "SHA-256 ausente." }
  $Fingerprint = $Matches[1].Replace(":", "").ToLowerInvariant()
  if ($Fingerprint -notmatch '^[0-9a-f]{64}$') { throw "Certificado inválido." }
  Write-Host "Identidade pública do APK (SHA-256): $Fingerprint"

  function SaveSecret([string]$Name, [string]$Value) {
    $Psi = New-Object System.Diagnostics.ProcessStartInfo
    $Psi.FileName = (Get-Command gh).Source
    $Psi.Arguments = "secret set $Name --repo $Repo"
    $Psi.UseShellExecute = $false
    $Psi.RedirectStandardInput = $true
    $Psi.RedirectStandardError = $true
    $Psi.CreateNoWindow = $true
    $Proc = [System.Diagnostics.Process]::Start($Psi)
    $Proc.StandardInput.Write($Value)
    $Proc.StandardInput.Close()
    $Diagnostic = $Proc.StandardError.ReadToEnd()
    $Proc.WaitForExit()
    $Code = $Proc.ExitCode
    $Proc.Dispose()
    if ($Code -ne 0) { throw "Não foi possível salvar $Name : $Diagnostic" }
    Write-Host "$Name : CONFIGURADO"
  }

  SaveSecret "CLEAN_TV_KEYSTORE_B64" ([Convert]::ToBase64String([IO.File]::ReadAllBytes($KeyStore)))
  SaveSecret "CLEAN_TV_KEYSTORE_PASSWORD" $Password
  SaveSecret "CLEAN_TV_KEY_ALIAS" $Alias
  SaveSecret "CLEAN_TV_KEY_PASSWORD" $Password
  SaveSecret "CLEAN_TV_CERT_SHA256" $Fingerprint
  Write-Host "Assinatura própria configurada. Guarde o .jks e a senha em backup offline."
  Write-Host "Iniciando compilação de produção no GitHub Actions..."
  & gh workflow run ptbr-apk.yml --repo $Repo --ref main
  if ($LASTEXITCODE -ne 0) {
    Write-Warning "Secrets configurados, mas não foi possível iniciar o build. Abra Actions > APK em português brasileiro > Run workflow."
  }
  Write-Warning "Um APK debug antigo com certificado diferente não pode ser substituído diretamente. Exporte primeiro o backup de restauração da TV."
} finally {
  [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($Bstr)
  Remove-Variable Password, SecretPassword, CertText, Fingerprint -ErrorAction SilentlyContinue
}
