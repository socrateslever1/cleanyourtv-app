# CleanYourTV — RFC: controle real de dispositivos Android, versão 2

Status: **arquitetura e critérios de aceite; NÃO implementado na r49**.
Base auditada: 192ee77b538e6340f3fc7539b87070825cf94d19.
Objetivo: o controlador no celular administra, com autorização verificável, Android TV, Google TV, outros celulares/tablets Android e Wear OS. Um diagnóstico não deve ser apresentado como otimização.

## Problemas auditados na r49

1. `AppGraph.sessions` cria `AdbSessionManager(..., tls = null)`; `adb/wire/Tls.kt` recusa STLS. Assim, pareamento TLS com código, necessário para a depuração sem fio moderna, **não existe**.
2. `WearOsReadOnly` tem somente comandos de leitura. Não há execução de planos, regras por firmware ou restauração Wear OS.
3. `PhoneDiagnostics` lê apenas dados do próprio celular; não há gestão de um segundo celular.
4. `core/backup` produz ZIP de restauração **de TV**, com snapshots de pacote/alterações; não contém necessariamente APKs nem dados privados de aplicativos. A restauração não é genérica.
5. O motor de planejamento está condicionado a perfis e invariantes da TV, inclusive Home, canais e controle remoto. Reaproveitá-lo cegamente em um telefone ou relógio é inseguro.
6. `TvTools` oferece apenas tarefas recentes e medições, sem controle de aplicativos em outra plataforma.

## Arquitetura obrigatória

- **Transporte autenticado:** `AndroidConnection` abstrai ADB TCP clássico, ADB TLS por pareamento e, somente onde autorizado, um agente local com privilégios conferidos pelo sistema. Uma porta IP aberta não constitui autorização. TLS deve autenticar e fixar a identidade do alvo, não apenas criptografar um socket.
- **Identidade e capacidades:** `DeviceIdentity` (identificador persistente, modelo, versão, firmware, usuário atual) e `Capabilities` (leitura, alterar estado de pacote, instalar APK, recuperar histórico, exportar dados). TV, celular/tablet e relógio têm políticas distintas. Classificação desconhecida = bloqueio de escrita.
- **Políticas por plataforma:** `TvPolicy`, `PhonePolicy`, `WearPolicy`; política geral nega alteração dos pacotes protegidos e exige verificação do launcher, teclado, telefone, SMS, rede, permissões, perfil administrativo, acessibilidade, autenticação, SOS, sensores, saúde, sincronização, pagamento e atualizações. Denylist é piso, não autorização por ausência na lista. Para relógio, exige perfil por fabricante/firmware validado.
- **Operações tipadas:** `ListPackages`, `DisableForUser`, `EnableForUser`, `InstallVerifiedApk`, `RestoreState`; evitar comandos arbitrários de shell originados da UI. Validar identificador de pacote e usuário explícito, respeitando multiusuário Android.
- **Mudança transacional:** leitura do estado atual, preview do que muda e impacto, autorização do proprietário, ponto de restauração DURÁVEL antes da primeira escrita, conferência de identidade e firmware imediatamente antes da ação, uma escrita por dispositivo por vez, verificação de pós-condição e compensação em caso de falha.
- **Backup exportável:** ZIP versionado, manifesto autenticado por hashes, id do dispositivo, usuário do Android, informações de compatibilidade, inventário e estado anterior dos pacotes, diário de mudanças, scripts de restauração inspecionáveis e cópia opcional de APKs/splits quando a origem permitir. Não prometer backup de dados privados sem permissões legais/tecnicamente suficientes. Dados potencialmente sensíveis precisam de criptografia antes de exportar, sem chaves no repositório.
- **Restauração:** importar arquivo, verificar limites contra zip-bomb, hashes, formato, dispositivo e usuário, mostrar diferenças e permitir restaurar tudo ou cada pacote. Nunca executar scripts importados sem validação tipada. Garantir retomada após falha ou interrupção e registrar o resultado.
- **UX:** lista de dispositivos e tipo, status real da conexão, indicadores de capacidade, lista de aplicativos e classificação de risco, seleção individual, botão ativar/desativar quando permitido, confirmação, backup e restaurar, registro da última operação; nenhuma ação que pareça ativa quando está em modo somente leitura.
- **Auditoria:** logs sem IP, chaves, serial ou dados privados; testes unitários, integração, falhas simuladas e validação física em modelos/firmwares suportados. Releases bloqueadas se ocorrer regressão de backup, assinatura ou controles protegidos.

## Critérios de aceite verificáveis

1. Telefone A controla **outro** telefone B autorizado: identifica B, carrega inventário de B, permite desabilitar e reabilitar aplicativo NÃO ESSENCIAL com confirmação, verifica no B, mostra resultado e não altera A.
2. Android TV e Google TV mantêm todas as proteções e backup pré-existentes; o motor novo não regride o fluxo TCL/Chromecast.
3. No Wear OS suportado por perfil validado, desativa e reativa somente aplicativo elegível sem comprometer saúde, SOS, Bluetooth, relógio, chamadas e atualizações. Fora do perfil aprovado, leitura e backup apenas.
4. Sem backup íntegro, sem identidade estável, sem privilégio ADB suficiente, com troca de firmware, usuário ou rede suspeita, a operação é NEGADA com motivo claro.
5. Simular queda de conexão entre desativação e confirmação: journal persistido, operação reconciliada na reconexão e restauração permitida. Nunca declarar sucesso só porque o comando foi enviado.
6. O ZIP exportado é validado e importado; restaurar estado antes/depois de reiniciar o controlador e após atualização de APK. Mudar de packageName não pode prometer migração automática dos dados do app.
7. Fluxo ADB/TLS moderno funciona com código no próprio celular controlador, sem computador, em Android/Wear OS compatíveis; autorização reversível e opção de desconectar depuração.
8. Entrega assinada com identidade `tv.cleanyour.app.personal`, certificado do proprietário e versionCode crescente; CI, screenshots e smoke tests aprovados. Nenhuma mudança na chave para "resolver" instalação.

## Etapas de execução (não confundir com conclusão)

1. Camada ADB TLS + identidade do alvo e testes de autenticação/pareamento.
2. Inventário tipado e políticas Phone/TV/Wear, com bloqueio de escrita para desconhecidos.
3. Backup ZIP seguro, importação, restauração, journal e verificação após interrupções.
4. Ativar/desativar, instalar APKs verificados e UI de controle remoto.
5. Perfil certificado de Wear OS, testes físicos e expansão de fabricantes.
6. Publicação estável somente após os critérios de aceite; não promover stubs nem telas meramente demonstrativas.

## Decisões de segurança

- Padrão: sem root e sem modificar serviços essenciais; ADB autorizado é necessário para operações privilegiadas em outros Androids.
- "Backup completo de outro aplicativo" (arquivos privados, contas, tokens e bancos internos) não é garantido pelo ADB comum; distinguir explicitamente de inventário, configurações e APKs.
- Não oferecer remoção de componentes críticos mesmo quando uma lista genérica de debloat recomendar. Para aparelhos desconhecidos, falhar fechado.
