package tv.cleanyour.feature.setup

/**
 * Explanations are conservative and never inferred from an arbitrary package
 * suffix. Verified meanings are still model/firmware-dependent. Unknown entries
 * must NOT be presented as certainly safe to disable.
 */
internal data class ServiceImpact(
    val purpose: String,
    val disabling: String,
    val caution: String,
)

internal object ServiceImpactCatalog {
    fun explain(pkg: String): ServiceImpact = when (pkg) {
        "com.tcl.waterfalloverseas" -> ServiceImpact(
            "Exibe um feed de conteúdo online da TCL.",
            "O feed pode deixar de aparecer ou atualizar.",
            "O efeito exato depende do firmware TCL; outros recursos que compartilhem esse feed podem ser afetados.",
        )
        "com.tcl.partnercustomizer" -> ServiceImpact(
            "Personaliza linhas e conteúdo da tela inicial TCL.",
            "A personalização dessas linhas e a importação de conteúdo podem parar.",
            "Pode modificar a aparência ou a organização da tela inicial.",
        )
        "com.tcl.messagebox" -> ServiceImpact(
            "Recebe mensagens e avisos enviados pela TCL, incluindo conteúdo promocional.",
            "Essas mensagens podem deixar de chegar.",
            "Também pode impedir avisos do fabricante. Não confundir com notificações gerais do Android.",
        )
        "com.tcl.notereminder" -> ServiceImpact(
            "Gerencia notas e lembretes do fabricante.",
            "As notas e os lembretes podem não funcionar ou deixar de notificar.",
            "Impacto funcional: mantenha ativo se usa lembretes ou não conhece a dependência.",
        )
        "com.tcl.tv.tclhome_passive" -> ServiceImpact(
            "Fornece conteúdo TCL para a tela inicial, possivelmente durante a inicialização.",
            "O feed TCL pode parar de atualizar.",
            "Pode afetar elementos da tela inicial; verifique o comportamento após a alteração.",
        )
        "android.autoinstalls.config.google.atvapai" -> ServiceImpact(
            "Configura instalações automáticas de aplicativos parceiros do fabricante.",
            "Pode impedir futuras instalações ou provisionamentos de aplicativos parceiros.",
            "O comportamento pode variar após atualização ou redefinição de fábrica.",
        )
        "com.google.android.onetimeinitializer" -> ServiceImpact(
            "Executa tarefas iniciais de configuração do Google.",
            "Algumas tarefas de inicialização de configuração podem não ocorrer.",
            "Não é possível assegurar ausência de efeito em novas configurações ou atualizações.",
        )
        else -> ServiceImpact(
            "Finalidade específica não confirmada para este aparelho e firmware.",
            "O comportamento do serviço ou de recursos que dependem dele pode mudar.",
            "Risco desconhecido. Prefira manter ativo até conhecer a função e suas dependências.",
        )
    }

    fun groupNote(groupId: String): String = when (groupId.substringBefore("-optional")) {
        "ads" -> "Conteúdo comercial. Nem todo serviço desta categoria é apenas publicidade; avalie cada item."
        "tracking" -> "Medição e telemetria. Desativar pode reduzir coleta, mas também afetar análises usadas pelo fabricante."
        "preloads" -> "Aplicativos pré-instalados. A classificação depende do aparelho; alguns podem prestar funções úteis."
        "demo" -> "Recursos de exposição em lojas. Verifique se o aparelho utiliza algum desses serviços."
        "streaming" -> "Integrações de reprodução. Não desative aplicativos ou serviços que você utiliza."
        "voice" -> "Assistente e serviços de voz. Desativar pode afetar pesquisas, comandos e acessibilidade."
        "casting" -> "Transmissão e integração com outros aparelhos. Pode afetar Chromecast e espelhamento."
        "brand" -> "Serviços adicionais do fabricante. A função e o impacto dependem do firmware."
        "other" -> "Serviços em segundo plano. Não há garantia de que sejam dispensáveis em todos os aparelhos."
        else -> "Revise individualmente: a função do serviço depende do modelo, firmware e outros aplicativos."
    }

    const val recovery =
        "Se a mudança for aplicada e causar problema, abra a restauração da TV e reative o serviço ou desfaça a limpeza. A disponibilidade depende da conexão e do ponto de restauração."
}
