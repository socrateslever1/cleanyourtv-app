# CleanYourTV Bridge — espelhamento TV -> celular

Estado em 2026-11-03: **código implementado; compilação e homologação física pendentes**.

## Objetivo

O aplicativo principal CleanYourTV permanece instalado no **celular**. O módulo opcional
`tv.cleanyour.bridge` é um segundo aplicativo Android **instalado na TV** somente com
confirmação explícita do proprietário. A TV transmite sua tela ao celular (sentido inverso
do Google Cast convencional).

## Fluxo

1. Na tela de gerenciamento, tocar na imagem/cartão da TV ou na ação **Espelhar a TV no celular**.
2. O visualizador verifica via ADB se `tv.cleanyour.bridge` está instalado.
3. Se ausente, apresenta **Instalar módulo de espelhamento na TV**. O usuário confirma
   dispositivo-alvo, finalidade e risco. O APK vem de `assets/bridge/CleanYourTVBridge.apk`
   do app principal; sem download oculto.
4. O instalador valida o pacote, versão mínima, assinatura, plataforma TV e transmite
   por sessão ADB autenticada. Não remove aplicativos existentes.
5. O usuário pressiona **Espelhar pelo módulo da TV**. O CleanYourTV gera chave
   de sessão aleatória de 256 bits e inicia `MirrorActivity` via ADB.
6. **Na TV**, o proprietário pressiona **Autorizar espelhamento** e confirma a
   autorização do Android MediaProjection. A captura não começa sem essa permissão.
7. O agente abre um servidor temporário TCP na porta 39761, autentica um único
   cliente por HMAC-SHA256 e envia H.264 cifrado em AES-256-GCM. O celular decodifica
   usando `MediaCodec`/TextureView. Fecha o socket ao encerrar.
8. Se a TV negar MediaProjection ou não oferecer encoder H.264, há mensagem de
   falha e possibilidade de usar a prévia por capturas ADB.

## Compatibilidade e limitações

- A API de projeção pode não estar presente ou ser restringida por um firmware
  específico de Chromecast, Google TV ou Android TV. A homologação com
  Chromecast Sabrina (Google TV 4K) **não foi executada**.
- Sem áudio, sem gravação local, sem root e sem acesso a conteúdo bloqueado por DRM.
- Há consentimento novo a cada sessão; permissões não são reutilizadas, exportadas
  ou preservadas após reinicialização.
- O Bridge transmite apenas vídeo; o controle remoto permanece no canal ADB já existente.
- O agente não instala sozinho nem executa comandos arbitrários recebidos pela rede.
- A chave de sessão trafega pela inicialização ADB previamente autorizada.
  **Enquanto ADB TCP/5555 legado estiver ativo, esse canal de provisionamento
  não oferece sigilo de transporte**. Portanto, usar exclusivamente em rede
  local de confiança. O TLS interno do ADB deve ser implementado para retirar
  definitivamente esse risco de exposição.
- Reservar o IP DHCP no roteador não fixa a porta TLS do Android. O aplicativo
  já possui descoberta mDNS, mas o pareamento TLS diretamente no celular
  ainda é uma entrega separada.

## Compilação e entrega

- `:tv:bridge:assembleRelease` usa a **mesma chave permanente do proprietário**
  fornecida exclusivamente pelo ambiente de release.
- `:app:assembleFullRelease` incorpora o APK Bridge assinado em
  `assets/bridge/CleanYourTVBridge.apk`. O APK principal conserva
  `tv.cleanyour.app.personal` e sua assinatura.
- CI deve executar testes do Bridge e do aplicativo e verificar pacote,
  certificado e presença do asset. Sem assinatura, testes e compilação
  bem-sucedida, **não divulgar APK como atualização pronta**.

## Homologação obrigatória

- Instalar APK oficial do celular sem perda de dados e verificar atualização por cima.
- Testar conexão ADB, seleção explícita da TV Quarto e detecção do Bridge ausente.
- Instalar módulo e conferir identificador `tv.cleanyour.bridge` e certificado.
- Verificar UI e autorização MediaProjection na TV real.
- Exibir vídeo decodificado no celular, não apenas imagem estática; medir FPS/latência.
- Pressionar **Parar espelhamento**, encerrar pela notificação na TV e desconectar Wi-Fi.
- Testar fechamento, perda de rede, expiração da sessão, tela protegida,
  indisponibilidade de encoder e falta de espaço.
- Testar atualização do Bridge com mesma assinatura e incremento de versionCode.
