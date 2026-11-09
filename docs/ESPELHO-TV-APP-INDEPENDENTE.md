# App independente: TV no celular + celular como controle remoto

Estado: **implementado em `espelho-tv/` (2026-11-09); compila e passa nos testes do protocolo, falta teste no Chromecast real.**

## Objetivo

Assistir a tela do Chromecast com Google TV no celular (ex.: no banheiro) e controlar a TV pelo
mesmo celular. Feito para leigos: sem ADB, sem modo desenvolvedor, sem configuração técnica.

## Peças

| Peça | Onde roda | Função |
|---|---|---|
| **Ponte** | TV | Captura tela (MediaProjection) + áudio (AudioPlaybackCapture), codifica H.264/AAC, transmite cifrado ao celular |
| **Controle** | Celular | Recebe vídeo e áudio; envia comandos de controle |
| **Canal de controle** | Celular → TV | Protocolo **Android TV Remote v2** (o mesmo do app Google TV): direcional, OK, voltar, home, volume, liga/desliga, teclado. Não exige permissão na TV, só o código de pareamento mostrado na tela. |

Por que o controle não passa pela Ponte: injetar teclas a partir de um app exige ADB ou serviço de
acessibilidade (outra permissão para o leigo ativar, e restrita pela Play Store). O Remote v2 já
vem no Google TV.

## Fluxo do usuário

1. Instala **Ponte** na TV (idealmente pela Play Store) e o app no celular.
2. Abre a Ponte. Ela mostra um QR code (IP, porta, chave de 256 bits). Celular lê → pareado.
3. Na mesma tela, o celular pareia o controle (código de 6 dígitos que a TV mostra).
4. Ponte pede "Começar a transmitir?" → OK no controle. Fica ativa em serviço de primeiro plano
   até a TV desligar; sem cliente conectado, o encoder fica pausado.
5. No celular: **Assistir**. Vídeo + áudio na tela, controles sobrepostos.

Única interação obrigatória por sessão de TV ligada: o OK da captura (exigência do Android).

## Reaproveitamento do CleanYourTV

- `tv/bridge/ProjectionService.java`, `BridgeProtocol.java`: captura, H.264, AES-256-GCM.
- `app/.../TvH264Player.kt`, `AnnexBVideo.kt`, `BridgeClient.kt`: recepção e decodificação.
- Trocar: provisionamento da chave via ADB → QR code; `MirrorActivity` protegida por
  `WRITE_SECURE_SETTINGS` → atividade com ícone no launcher da TV (LEANBACK_LAUNCHER).
- Novo: áudio, descoberta mDNS (NSD), reconexão, cliente Android TV Remote v2.

## Limites conhecidos (não contornar)

- Conteúdo com DRM/FLAG_SECURE (Globoplay ao vivo, Netflix etc.) aparece preto.
- Vídeo em reprodução "tunneled" (provável no YouTube do Chromecast) pode aparecer preto.
- Apps que recusam captura de áudio ficam sem som no celular.
- Apenas rede local; nenhuma porta exposta à internet.

## Etapa 0 — teste de viabilidade (antes de tudo)

APK mínimo na TV que captura a tela e mostra a imagem capturada em miniatura na própria TV,
mais um medidor de áudio capturado. Abrir YouTube, Globoplay e Pluto TV e anotar:
vídeo visível? áudio capturado? Resultado decide se o produto é viável para o uso principal.
