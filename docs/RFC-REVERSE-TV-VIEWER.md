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
1. **Iniciado:** contrato de sessão, mapeamento de toque e testes unitários.
2. Cliente de vídeo H.264 com MediaCodec e transporte ADB autenticado, com controle explícito de backpressure.
3. Injeção de eventos direcionais/teclado com lista de comandos permitidos; toque absoluto só quando suportado.
4. Tela Compose com player, controles e estados de erro, integrada por rota própria.
5. Testes em Chromecast Google TV 4K e TCL Google TV, reconexão e compatibilidade.
6. APK de avaliação assinado, validação de atualização e homologação antes de produção.

## Limitações conhecidas
O scrcpy upstream não constitui SDK Android embarcável pronto.
Uma integração depende de servidor compatível na TV e do ciclo de vida da conexão ADB.
Dispositivos sem captura suportada deverão oferecer apenas controle remoto e explicar a limitação.
