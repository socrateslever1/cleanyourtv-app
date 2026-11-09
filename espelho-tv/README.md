# Espelho TV

A tela do Chromecast com Google TV no celular, e o celular como controle remoto.
Feito para assistir do banheiro, da cozinha ou de outro cômodo sem perder o que está passando.

Projeto independente do CleanYourTV: tem build próprio e não usa ADB nem modo desenvolvedor.

## Como funciona

| Peça | Onde | O que faz |
|---|---|---|
| `tv/` | Na TV | Captura imagem e som (com o OK do Android) e transmite cifrado pelo Wi-Fi |
| `celular/` | No celular | Mostra a imagem, toca o som e envia comandos de controle remoto |
| `protocolo/` | Os dois | Canal cifrado (AES-256-GCM), QR de pareamento e cliente do controle remoto nativo do Google TV |

O controle remoto usa o serviço que o próprio Google TV já tem (o mesmo do app Google TV).
Por isso não precisa de nenhuma permissão extra na TV: só digitar uma vez o código que ela mostra.

## Primeiro uso

1. Instale `tv-release.apk` na TV e `celular-release.apk` no celular.
2. Abra o **Espelho TV** na TV. Ele pede:
   - permissão de **áudio**, para mandar o som da TV ao celular (não usa microfone);
   - **"Começar agora"**, a autorização de captura de tela do Android.
3. No celular, toque em **Parear com a TV** e aponte para o QR code da TV.
4. O celular já pede para parear o **controle remoto**: digite o código de 6 caracteres que aparece na TV.

Pronto. Depois disso, no dia a dia:

- Cada vez que a TV liga, a autorização de captura precisa de um novo OK (regra do Android).
  Dá para fazer pelo celular: **Ligar a transmissão na TV** e depois **OK** no controle do app.
- No celular, **Assistir**. Em pé: vídeo em cima e controle embaixo. Deitado: tela cheia, e um toque mostra o controle.

## Instalar na TV sem Play Store (fase de teste)

O Chromecast precisa permitir apps de fora da loja, uma única vez:

1. Na TV, instale pela Play Store um app como **Downloader** ou **Send Files to TV**.
2. Autorize esse app em Configurações > Apps > Segurança e restrições > Fontes desconhecidas.
3. Use-o para baixar ou receber o `tv-release.apk` e instalar.

Não precisa ativar modo desenvolvedor nem depuração.

Para o público leigo, o caminho definitivo é publicar o app da TV na Play Store.

## Limites (não são contornados)

- **Conteúdo protegido aparece preto**: Globoplay ao vivo, Netflix, Prime, Disney+ e outros com DRM.
- **YouTube pode aparecer preto** no Chromecast, se o vídeo for desenhado direto pelo hardware. Precisa testar.
- Apps que proíbem captura de som ficam sem som no celular.
- Só funciona na rede local (mesmo Wi-Fi). Nada é exposto à internet.
- O controle remoto funciona com qualquer app, protegido ou não.

## Compilar

```
./gradlew :protocolo:test assembleRelease
```

APKs em `tv/build/outputs/apk/release/` e `celular/build/outputs/apk/release/`.
A workflow `.github/workflows/espelho-tv.yml` gera os mesmos APKs no GitHub Actions.

`teste.keystore` é uma chave **só de teste**, pública, para cada versão instalar por cima da anterior.
Antes de publicar, troque por uma chave privada.

## Estado

- Compila; os testes do protocolo passam, inclusive um Google TV simulado em TLS
  (pareamento por código, teclas, abrir app).
- **Ainda não foi testado num Chromecast real.** O primeiro teste deve confirmar:
  a janela "Começar agora" aparece? A imagem do YouTube e da Pluto TV chega? O som chega?
  O código do controle remoto é aceito?
