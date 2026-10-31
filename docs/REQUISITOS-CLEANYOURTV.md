# CleanYourTV — requisitos consolidados

Nome oficial mantido: **CleanYourTV**. O nome não restringe o produto a uma única TV:
o aplicativo controlador roda no celular Android e pode gerenciar Android TV, Google TV
e futuramente smartphones e relógios Wear OS. Não alterar o applicationId existente
(`tv.cleanyour.app`) nem sobrescrever os dados ou pontos de restauração.

## Requisitos solicitados

### 1. Compatibilidade com TVs Android e Google TV
- Reconhecer fabricante, modelo, firmware, versão Android e serviços instalados.
- Cobrir TCL, Chromecast/Google TV e outros dispositivos compatíveis, por perfis seguros.
- Não aplicar cegamente o mesmo conjunto de desativações em fabricantes diferentes.
- Preservar Google Cast, Play Store, atualizações OTA, Bluetooth, controle remoto,
  botões dedicados, HDMI/CEC, serviços de entrada de TV e configurações.

### 2. Limpeza inteligente, seletiva e reversível
- Apresentar cada aplicativo/pacote a ser desativado com nome, função, risco e motivo.
- Permitir escolher individualmente manter/desativar antes de aplicar.
- Proteger componentes vitais de forma não ignorável.
- Evitar simplificação excessiva da interface: permitir personalização de aplicativos,
  recomendações, canais e elementos visuais disponíveis no Google TV.
- Não substituir launcher da TCL ou do Chromecast quando isso colocar funções em risco.
- Medir o resultado antes/depois: armazenamento, serviços, tempo de abertura, responsividade;
  não prometer ganhos de RAM/CPU sem evidências.

### 3. Recuperação e backup
- Salvar ponto de restauração antes de toda operação.
- Disponibilizar na tela principal "Restaurar como estava antes" e reativação individual.
- Exportar para o celular o estado anterior e scripts/instruções de recuperação em ZIP
  independente dos dados internos do app.
- Diferenciar desabilitação de aplicativos de exclusão: o ZIP de restauração não
  representa necessariamente um backup dos binários APKs.
- Preservar o backup durante troca de versão, mudança de chave ou reinstalação.

### 4. Sideload e atualização de APKs externos
- Selecionar APK no celular e instalar em TV Android/Google TV após confirmação.
- Mostrar nome, versão, tamanho, compatibilidade, origem e assinatura; dar guia ao usuário.
- Possibilitar atualização de aplicativos pessoais sem precisar desinstalar a cada versão,
  respeitando mesmo packageName/certificado e versionCode crescente.
- Não instalar silenciosamente, sem autorização ou a partir de arquivos suspeitos.

### 5. Aplicativos recentes e continuidade de mídia
- Exibir atalhos para aplicativos usados recentemente em TVs compatíveis.
- Reabrir rapidamente um app fechado acidentalmente, sem substituir o launcher de fábrica.
- Para "Continuar assistindo" da interface Google TV, estudar integração do player
  (estado/timestamp, deep links, MediaSession e interfaces Google aplicáveis).
- Não prometer retomar a posição de qualquer app arbitrário sem suporte do próprio player.

### 6. Atualização do CleanYourTV no próprio aplicativo
- Compilar no GitHub e publicar APK com versão crescente, certificado e hash verificados.
- Avisar dentro do app quando existir versão nova, baixar ali mesmo, mostrar progresso
  e solicitar ao Android a confirmação da instalação (sem abrir o GitHub no navegador).
- Assinar todas as versões futuras com uma chave permanente armazenada em Secrets,
  nunca no repositório, garantindo update in-place sem perda de dados.
- Tratar a primeira migração de APKs assinados com chaves debug diferentes
  cuidadosamente, exportando backup antes de qualquer desinstalação.
- Tratar builds de debug como experimentais, não como versões estáveis.

### 7. Android celular
- Adaptar interface para telas de celular e tablet, com diagnóstico local de espaço,
  aplicativos e recomendações não destrutivas.
- Só permitir debloat real após classificação e proteção de telefonia, biometria,
  notificações, serviços Google, fabricante e gestão de energia.

### 8. Wear OS
- Reconhecer relógio como plataforma distinta e bloquear perfis de limpeza de TV.
- Começar por diagnóstico somente leitura (modelo, Android e armazenamento).
- Evoluir pareamento ADB no celular com suporte seguro aos fluxos próprios de Wear OS.
- Antes de qualquer otimização: preservar saúde/sensores, Bluetooth, vínculo com celular,
  SOS/emergência, chamadas, pagamentos, relógio, notificações e atualizações.

### 9. Qualidade, usabilidade, idioma e suporte
- Interface integralmente em português brasileiro, inclusive textos dinâmicos.
- Navegação direta na seleção da TV, sem toques redundantes.
- Manter telas modernas e utilizáveis, sem excesso de texto técnico nem opção invisível.
- Garantir CI, testes unitários, capturas de regressão, release consistente e rollback.
- Não quebrar recursos já funcionando ao acrescentar novas funcionalidades.

## Estado de implementação conhecido — não confundir código com validação física

| Requisito | Estado de implementação |
| --- | --- |
| Nome CleanYourTV | Atualizado na identidade de interface e na publicação futura |
| Seleção individual e proteções TCL | Código implementado; requer testes físicos |
| Ponto de restauração e exportação ZIP | Existente; melhorar visibilidade e testar recuperação |
| Instalador de APK por ADB | Código implementado; teste físico pendente |
| Aviso/download interno de atualização | Código implementado; assinatura permanente ainda não configurada |
| Upgrade sem desinstalar | Depende de certificado persistente e migração da versão debug |
| Apps recentes na TV | Não implementado |
| Continuidade de reprodução universal | Não garantível; integração depende do player |
| Otimização de celulares | Diagnóstico/atalhos iniciais, sem motor de debloat |
| Wear OS | Diagnóstico inicial somente leitura; sem debloat |
| Validação CI / captura visual | Deve passar integralmente antes de promover release estável |
