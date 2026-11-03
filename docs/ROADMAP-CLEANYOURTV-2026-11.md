# CleanYourTV — entregas, pendências e critérios de aceite

Revisão: 2026-11-02. Este documento descreve trabalho de engenharia; não é texto da interface.

## Regras invariantes

- Um único aplicativo principal **CleanYourTV**, instalado no telefone. Pacote oficial Android `tv.cleanyour.app.personal` e o MESMO certificado de produção em todas as atualizações. Sem migração destrutiva, desinstalação obrigatória ou APK com chave de teste.
- O Chromecast, Google TV e Android TV são alvos gerenciados remotamente, não o local de instalação do aplicativo principal.
- O módulo auxiliar de TV, provisoriamente **CleanYourTV Bridge**, será opcional e deverá ter pacote e permissões próprios.
- Nenhuma ação destrutiva automática. Sempre identificar o dispositivo-alvo, explicar alterações, criar ponto de restauração quando aplicável e confirmar explicitamente.
- Interface voltada ao usuário 100% pt-BR. Comandos e identificadores técnicos só devem aparecer no contexto didático/diagnóstico, nunca em alertas de falha sem tradução.
- Não habilitar root, não desabilitar serviços críticos, não expor ADB TCP/5555 fora de redes locais confiáveis.

## Confirmado no código-fonte, ainda exigindo homologação

1. **Guia ADB no celular**: pareamento, conexão na porta dinâmica, `tcpip 5555` endereçado com `-s`, nova conexão na porta 5555 e `adb devices -l`. Inclui limites de segurança e esclarece que a chave do computador não é a chave do celular.
2. **Instalador de APK do telefone para a TV**: seletor de arquivo via Android SAF, análise de APK e assinatura, confirmação com nome da TV de destino, envio pela sessão ADB e indicação de progresso. Não são suportados bundles APKS/XAPK.
3. **Visualizador/controle de TV**: capturas periódicas de tela e comandos remotos. Não é streaming de vídeo em tempo real e imagens protegidas podem ser bloqueadas.
4. **Gerenciamento de TV, módulos e restauração**: fluxos já existentes; ainda dependem de testes regressivos e de casos reais por modelo/firmware.
5. **Android celular e Wear OS**: atualmente diagnósticos e ações limitadas; o monitoramento de Wear OS é somente leitura. Não anunciar limpeza avançada nesses dispositivos.
6. **Atualização própria**: fluxo GitHub Actions com assinatura permanente, validação de pacote, certificado, versão e hash; ainda depende de execução bem-sucedida e confirmação de que o update instala por cima do APK oficial sem perder dados.

## Progresso de 2026-11-02: conexão e visualização

- A prévia por capturas de tela agora é aberta automaticamente após a inspeção inicial da TV e a confirmação de sua identidade, ou quando o usuário escolhe um dispositivo já cadastrado na tela de gerenciamento. Fechar a prévia retorna à interface anterior.
- O visualizador passa a solicitar uma atualização imediatamente após comandos remotos concluídos e oferece ação manual `Atualizar imagem agora`. O período normal de atualização continua limitado a 1,2 s, além do tempo de obtenção e transferência de cada captura.
- O tutorial ADB explica portas TLS dinâmicas, diferença entre pareamento e conexão, descoberta `adb mdns services`, endereço IP DHCP e uso temporário de TCP/5555.
- **Limitação não resolvida:** pareamento ADB/TLS diretamente no Android não foi implementado. O app descobre anúncios mDNS, mas ainda não autentica conexão TLS; não afirmar que a troca de porta deixou de ser necessária em todos os dispositivos.
- **Limitação não resolvida:** a prévia é feita por PNGs sucessivos; **não é transmissão ao vivo contínua**. Um futuro modo de baixa latência requer uma solução de transporte de vídeo com consentimento e controles adequados, potencialmente via Bridge.
- Alterações acima foram enviadas como código-fonte; carecem de compilação, testes automatizados executados e homologação em aparelho real.

## Pendências prioritárias

### P0 — Entrega instalável e ausência de regressões

- Executar `check`, testes Robolectric e Roborazzi, lint, `assembleFullRelease`; corrigir falhas ANTES de publicar. Testar em pelo menos um celular Samsung e no Chromecast real.
- Disparar o GitHub Actions de assinatura na revisão de `main`, verificar certificado SHA-256, `applicationId`, incremento de `versionCode`, arquivo da Release e funcionamento do atualizador no telefone.
- Tornar a localização pt-BR totalmente determinística/offline para produção: hoje `scripts/localize_ptbr.py` pode depender de tradução remota. Revisar pontuação, ICU/plurals e testar manualmente todas as telas.
- Testar especificamente o seletor de dispositivo, navegação/voltar, permissões ADB, telas pequenas, alterações de orientação, retomada de sessão e comunicação perdida.

### P0 — Conexão Android moderna e segurança

- Implementar pareamento direto no CleanYourTV usando o protocolo ADB sobre TLS com código de seis dígitos, armazenamento seguro da chave no Android Keystore e validação de identidade do dispositivo.
- Não confundir porta temporária de pareamento, porta dinâmica de conexão TLS e porta clássica TCP/5555. Detecção de mDNS deve ter alternativa manual.
- Permitir migração gradual: 5555 apenas como modo de compatibilidade explicitamente autorizado. Evitar manter a depuração habilitada permanentemente e alertar sobre exposição de rede.

### P1 — Instalador remoto de APK

- Homologar a instalação e a atualização de APKs assinados em Android TV/Google TV com progresso e erro explicativo em português; respeitar versões, conflito de certificados, espaço insuficiente e incompatibilidade de SDK/ABI.
- Antes da confirmação, exibir arquivo, pacote, versão, tamanho, TV de destino e riscos. Preservar dados; não efetuar instalações em lote nem sem confirmação.
- Se bundles APKS/XAPK forem suportados no futuro, validar todas as divisões e a assinatura antes do envio; não simular suporte com APK isolado.

### P1 — CleanYourTV Bridge opcional, instalado na TV

- Criar um aplicativo TV separado com escopo de permissão mínimo, pareamento autenticado com o telefone, autenticação mútua, revogação e desligamento simples.
- Definir APIs tipadas para diagnóstico, controle remoto, aplicativos recentes e telemetria permitida por Android TV; sem promessas de acesso root, remoção de apps protegidos ou gravação de dados privados de terceiros.
- Proteger chamadas contra execução sem consentimento e acesso fora da rede autorizada. Elaborar testes de segurança, reconexão e desinstalação/reversão do agente.

### P1 — Restauração, diagnóstico e painel de mudanças

- Mostrar, antes de cada ação, seu significado, impacto e possibilidade de reversão. Criar/restaurar backups com manifesto, hash, vínculo ao dispositivo, versão de firmware e relatório verificável.
- Identificar o consumo real do armazenamento separando aplicativo, dados e cache; nunca interpretar `df` ou `dumpsys diskstats` como autorização para excluir.
- Testar em Chromecast Sabrina, Google TV TCL e demais modelos individualmente; proteger launcher ativo, OTA, Google Play Services, Cast, acessibilidade, áudio e conectividade.

### P2 — Ampliação de plataformas e interface

- Multidispositivo: escolher explicitamente TV, celular ou relógio, com capacidades e avisos diferentes. Perfis de Android comum e Wear OS não herdam automaticamente comandos de TV.
- Evoluir controle da TV com latência, frequência de capturas e estado de sessão visíveis; captura de tela protegida não pode ser contornada.
- Validar design responsivo de telefone/tablet/TV, acessibilidade, português brasileiro consistente e ausência de informações internas em telas de usuário.

## Critério para chamar de concluído

Nenhum item acima é considerado entregue apenas por existir no GitHub. Precisa de código, teste executado, APK assinado verificável, instalação/atualização sem perda de dados e homologação funcional no dispositivo-alvo.
