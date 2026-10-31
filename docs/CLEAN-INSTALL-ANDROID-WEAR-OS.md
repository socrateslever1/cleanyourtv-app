# Clean-install Android — arquitetura multidispositivo

Nome comercial do APK: **Clean-install Android**. O projeto histórico/repositório
continua em \`socrateslever1/cleanyourtv-app\`. A versão Full usa
\`applicationId\` distinto \`tv.cleanyour.app.cleaninstall\`, permitindo instalar
em paralelo ao antigo Clean Your TV e preservar o backup privado dele.
Não há migração automática dos dados privados entre os apps.
A assinatura própria persistente ainda é obrigatória para futuras atualizações.

## Perfis por plataforma

1. **Android TV / Google TV** — limpeza seletiva mediante confirmação,
   proteção de pacotes, registro de alterações, restauração, sideload local ADB.
2. **Android celular** — por enquanto, atalhos às configurações nativas.
   Nenhum debloat automático de telefonia, biometria, serviços de fabricantes
   ou pacotes do sistema sem perfil validado.
3. **Wear OS** — diagnóstico inicial de SOMENTE LEITURA (modelo, versão
   Android, espaço em /data). Nenhum plano de TV e nenhum comando de remoção
   pode ser aplicado a relógios.

## Proteção de Wear OS

O ADB da TV verifica \`pm list features\`. Se o dispositivo declarar
\`feature:android.hardware.type.watch\`, a análise de TV é interrompida.

O diagnóstico Wear OS usa comandos ADB fixos somente de leitura.
Ele reconhece o relógio pela feature oficial e não habilita/desabilita
pacotes, não instala software e não grava parâmetros.

Conexão ADB do aplicativo atual: TCP clássico porta 5555, apenas endereços
IPv4 privados. O pareamento sem fio por código TLS dos Wear OS recentes
não está implementado no aplicativo; pode exigir pareamento prévio no
computador e \`adb tcpip 5555\`. Nunca deixar a depuração ativa
permanentemente.

A evolução para limpeza segura exigirá validação independente de cada linha,
fabricante e versão de firmware, testes reais, plano de reversão e proteção
inegociável destes grupos de funções:

- Health Services, sensores e dados de saúde;
- Bluetooth/BLE, vínculo com o telefone e sincronização;
- SOS, chamada, detecção de queda e telemetria de emergência;
- relógio, alarmes, mostradores e Ambient Mode;
- notificações, autenticação, pagamentos e proteção de tela;
- Google Play Services, atualização OTA e serviços críticos do fabricante.

**Não existe garantia de que uma limpeza preserve estas funções somente
pela marca ou modelo**. Logo, a limpeza Wear OS continua bloqueada até
a fase de certificação por firmware.

## Identificação do nome

Evitar renomear automaticamente o repositório GitHub, as tags e os caminhos
de atualização na mesma entrega, pois versões anteriores ainda fazem
requisições a essas URLs. A alteração atual é somente a marca e o título
apresentados ao usuário. O pacote Android e o certificado devem permanecer
estáveis para atualizações no lugar.
