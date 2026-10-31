# CleanYourTV — assinatura privada permanente e atualizações sem perda de dados

## O que o Android exige

Para instalar uma versão nova sobre outra, o Android exige simultaneamente:

1. Mesmo identificador do pacote: **tv.cleanyour.app.personal** na edição estável atual; a antiga edição Debug foi distribuída com **tv.cleanyour.app.cleaninstall**;
2. Certificado digital compatível com o certificado da versão instalada;
3. versionCode superior ao da versão instalada.

Renomear o ícone para CleanYourTV não altera o identificador do pacote e
não substitui o certificado. APKs de depuração assinados em runners distintos
podem ser incompatíveis entre si.

## Configuração única da sua assinatura

A chave privada DEVE ser criada no computador do proprietário e guardada
em backup offline e protegido. Ela NÃO vai para o repositório público,
não deve ser enviada pelo chat e não pode ser regenerada em cada compilação.

**Pré-requisitos no Windows:** Java JDK 21 com keytool e GitHub CLI (\`gh\`),
autenticada com \`gh auth login\` e com acesso de administrador ao repositório
\`socrateslever1/cleanyourtv-app\`.

No repositório, abra \`scripts/configurar-assinatura-windows.ps1\`.
Baixe esse script e execute no PowerShell (localmente e após revisar o código):

\`\`\`powershell
.\configurar-assinatura-windows.ps1
\`\`\`

O script cria ou REUTILIZA um keystore na pasta privada do usuário Windows,
verifica sua senha, obtém o SHA-256 do certificado público e configura no
GitHub Actions os cinco segredos:

- CLEAN_TV_KEYSTORE_B64
- CLEAN_TV_KEYSTORE_PASSWORD
- CLEAN_TV_KEY_ALIAS
- CLEAN_TV_KEY_PASSWORD
- CLEAN_TV_CERT_SHA256

A última entrada **amarra permanentemente** os builds à chave original. Se a
chave for trocada por engano, o pipeline falha em vez de lançar um APK incapaz
de atualizar a instalação.

Para checar a existência dos Secrets SEM imprimir seus valores:

\`\`\`powershell
.\configurar-assinatura-windows.ps1 -CheckOnly
\`\`\`

**Importante:** o comando acima verifica a presença dos cinco Secrets, não
demonstra que todos estão corretos. O teste verdadeiro é a compilação
\`fullRelease\` e a comparação do certificado com o fingerprint registrado.

## Publicação do GitHub

O workflow \`ptbr-apk.yml\` só produz \`assembleFullRelease\` com assinatura
permanente. O fluxo \`ptbr-release.yml\` só publica o APK estável após
revalidar assinatura, nome do pacote, versão e hash SHA-256.

A versão estável expõe \`CleanYourTV.apk\` em GitHub Releases.
O próprio aplicativo consulta a Release estável, baixa o APK em seu cache
privado, verifica o SHA-256, nome do pacote, versionCode e certificado, e
abre o instalador Android com consentimento explícito. Não existe instalação
silenciosa nem bypass da segurança do sistema.

## A primeira migração dos APKs antigos

**Não desinstale a versão atual antes de fazer backup dos dados e da TV.**

1. Com a TCL conectada, exporte pelo CleanYourTV o ZIP de restauração para
   uma pasta de sua escolha no celular, de preferência com cópia fora dele.
2. Confira no computador, via ADB USB autorizado:
   \`adb -d shell pm list packages | findstr /i cleanyour\`
3. Se a instalação antiga for \`tv.cleanyour.app.cleaninstall\` e a nova
   \`tv.cleanyour.app.personal\`, as duas podem coexistir; mantenha a
   antiga enquanto testa a nova e conserva o ZIP de restauração.
4. Se as duas versões tiverem o MESMO packageName mas certificados diferentes,
   o Android recusará a atualização direta. Só depois de preservar o backup
   poderá ocorrer a migração pontual, com reinstalação.
5. Não faça \`pm clear\`, não apague dados internos e não remova pacotes da TV
   para resolver um erro de assinatura.

**A partir da primeira instalação assinada com a chave permanente**, todos
os novos APKs do mesmo canal e certificado poderão atualizar a instalação
existente, preservando dados, sujeito à confirmação e às verificações do Android.

## Invariante de segurança

Se algum segredo de assinatura estiver ausente, inválido ou diferente do
fingerprint registrado, **o pipeline deverá falhar sem gerar uma Release
estável**. A ausência de APK é preferível a publicar atualizações incompatíveis.
