# Assinatura permanente do CleanYourTV PT-BR

O instalador do Android só aceita uma atualização no lugar da versão existente
quando o applicationId é o mesmo, a versão é maior e o certificado de assinatura
é compatível. APKs assinados com chaves de depuração diferentes **não são
atualizáveis uns sobre os outros**.

## Criar a chave no computador Windows (uma única vez)

Instale o JDK 21 e execute no PowerShell, em uma pasta particular:

```powershell
keytool -genkeypair -v -keystore .\cleanyourtv-producao.jks -alias cleanyourtv -keyalg RSA -keysize 3072 -validity 10000
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\cleanyourtv-producao.jks)))
```

Guarde o arquivo .jks e as senhas em backup seguro fora do repositório.
A saída Base64 NÃO deve ser enviada por chat público nem salva num commit.

No GitHub, abra: **Settings > Secrets and variables > Actions > New repository secret**.

Configure os quatro segredos exatamente com estes nomes:

- CLEAN_TV_KEYSTORE_B64: conteúdo Base64 integral do arquivo .jks;
- CLEAN_TV_KEYSTORE_PASSWORD: senha do keystore;
- CLEAN_TV_KEY_ALIAS: cleanyourtv;
- CLEAN_TV_KEY_PASSWORD: senha da chave.

O fluxo '.github/workflows/ptbr-apk.yml' usa esses segredos para assinar
todas as compilações futuras com o mesmo certificado e aplica um versionCode
crescente. O próprio app consulta seu GitHub Releases e baixa o APK,
verifica seu SHA-256 e sua assinatura antes de apresentar o instalador do Android.

## Migração necessária

**A primeira versão com esta chave NÃO poderá substituir uma versão anteriormente
assinada com a chave de depuração temporária**, mesmo tendo o mesmo package name.
Faça o seguinte ANTES de desinstalar:

1. Conecte a TV e abra "Restaurar TV ou salvar backup no celular".
2. Exporte e guarde o ZIP em uma pasta durável, fora do armazenamento privado do app.
3. Se não puder restaurar a TV pelo aplicativo, use os scripts de restauração do ZIP
   com ADB por um computador.
4. Desinstale a versão antiga do celular, instale a primeira versão assinada com
   sua chave permanente e conecte a TV novamente.
5. A partir dessa versão, os APKs seguintes com a mesma chave poderão atualizar por cima.

Não adicione uma assinatura fixa compartilhada/publicada ao código-fonte.
Não inclua a chave privada no GitHub Actions Artifact ou Releases.
O instalador do Android **sempre exige a confirmação do usuário**.
