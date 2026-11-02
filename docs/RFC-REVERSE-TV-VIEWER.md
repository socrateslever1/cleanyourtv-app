# CleanYourTV: visualização reversa e controle (implementação incremental)

Status: **Em desenvolvimento — não disponível no APK de produção.**

## Escopo
TV/Chromecast Google TV -> vídeo na tela do CleanYourTV no telefone;
telefone -> eventos remotos para a mesma TV, em rede local.

## Integração existente
- Reutilizar `:adb:session` para a conexão e validação de identidade da TV.
- Criar camada de transporte de quadros e controle isolada das operações destrutivas.
- Reutilizar a seleção de TV existente no `:app`; não alterar a seleção inicial.
- Exibir opção `Visualizar e controlar` somente após detecção de capacidade.
- Estado e mapeamento de coordenadas independentes da interface em `:core:flows`.

## Regras de segurança
1. Nenhum controle sem sessão autorizada e dispositivo identificado.
2. Proibir exposição da transmissão à internet e não abrir portas públicas.
3. Encerrar captura, sockets e tarefas ao sair ou trocar de TV.
4. Não gravar conteúdo de tela, teclas ou credenciais em logs.
5. Não contornar FLAG_SECURE nem conteúdo DRM.
6. Não iniciar execução de shell arbitrário por ações de interface; somente comandos fixos e validados.
7. Falhas de captura não afetam limpeza, backup ou restauração.

## Etapas de entrega
1. **Implementado, pendente execução de testes:** contrato de sessão, mapeamento de toque, testes unitários de validação.
2. **Protótipo implementado:** capturas PNG sucessivas por ADB, arquivo temporário único e limpeza após cada captura; ainda não é vídeo H.264 fluido.
3. **Implementado, pendente teste físico:** comandos direcionais, Home, Voltar, OK, volume, toque absoluto e texto ASCII restrito.
4. **Implementado, pendente teste físico:** tela Compose de prévia integrada ao diálogo das ferramentas da TV.
5. **Pendente:** execução do pipeline Gradle, testes em Chromecast Google TV 4K e TCL Google TV, reconexão e compatibilidade.
6. **Pendente:** APK de avaliação assinado com chave de atualização, validação de instalação e homologação antes de produção.

## Limitações conhecidas
O scrcpy upstream não constitui SDK Android embarcável pronto.
Uma integração depende de servidor compatível na TV e do ciclo de vida da conexão ADB.
Dispositivos sem captura suportada deverão oferecer apenas controle remoto e explicar a limitação.

## Estado de compilação (2026-11-01)
O código foi enviado ao GitHub, mas os commits desta branch ainda não dispararam um workflow Actions. Não existe APK comprovadamente compilado desta implementação. A workflow existente `.github/workflows/ptbr-apk.yml` tem `workflow_dispatch` para execução selecionando esta branch, requer segredos da chave permanente e valida testes, lint, assinatura, versão e pacote. Não fazer merge em `main` até compilação e testes físicos. As capturas sucessivas são uma solução inicial de visualização, **não** espelhamento de vídeo fluido. A implementação H.264 deverá ser feita em etapa independente, com testes de latência e dispositivos.
