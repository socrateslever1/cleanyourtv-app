package tv.cleanyour.app

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.cleanyour.core.model.TvId
import tv.cleanyour.ui.design.component.CytTopBar
import tv.cleanyour.ui.design.component.LinkRow
import tv.cleanyour.ui.design.component.ListDivider
import tv.cleanyour.ui.design.component.ListGroup
import tv.cleanyour.ui.design.component.ListRow
import tv.cleanyour.ui.design.component.Overline
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.theme.CytTheme
import tv.cleanyour.ui.design.theme.Glow

/**
 * Device selection is independent of TV registration and never runs ADB
 * automatically. Every transition is explicit; TV setup retains existing
 * authorization and restoration behavior.
 */
@Composable
internal fun DevicePickerScreen(
    graph: AppGraph,
    onSelectTv: (TvId) -> Unit,
    onAddTv: () -> Unit,
    onResumeSetup: (() -> Unit)? = null,
    onOtherAndroid: () -> Unit,
    onWatch: () -> Unit,
    onThisPhone: () -> Unit,
) {
    val televisions by graph.tvs.tvs.collectAsStateWithLifecycle()
    ScreenScaffold(Glow.Tv, topBar = { CytTopBar() }) {
        ScreenTitle("Qual dispositivo deseja gerenciar?", Modifier.padding(top = 12.dp))
        Text(
            "Escolha o aparelho antes de consultar ou modificar qualquer serviço. Nenhuma limpeza é automática.",
            Modifier.padding(top = 8.dp, bottom = 20.dp),
            color = CytTheme.colors.textSecondary,
            style = CytTheme.type.bodyMedium,
        )
        Overline("TVs e Google TV", Modifier.padding(bottom = 8.dp, start = 4.dp))
        ListGroup {
            if (onResumeSetup != null) {
                ListRow(
                    title = "Retomar configuração inacabada",
                    subtitle = "Continue as escolhas já salvas, sem recomeçar a configuração.",
                    onClick = onResumeSetup,
                )
                ListDivider()
            }
            if (televisions.isEmpty()) {
                ListRow("Nenhuma TV cadastrada", subtitle = "Adicione uma TV para começar.")
                ListDivider()
            }
            televisions.forEach { tv ->
                ListRow(
                    title = tv.nickname.ifBlank { tv.facts.model },
                    subtitle = listOfNotNull(tv.facts.model.takeIf { it.isNotBlank() }, tv.room)
                        .joinToString(" · ").ifBlank { "TV Android cadastrada" },
                    onClick = { onSelectTv(tv.id) },
                )
                ListDivider()
            }
            LinkRow("Adicionar TV / Google TV", onAddTv)
        }
        Overline("Outros dispositivos Android", Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp))
        ListGroup {
            ListRow(
                "Outro celular ou tablet",
                subtitle = "Controle de aplicativos com ADB autorizado. Pareamento moderno por código ainda pendente.",
                onClick = onOtherAndroid,
            )
            ListDivider()
            ListRow(
                "Relógio Wear OS",
                subtitle = "Diagnóstico somente leitura; modificações ainda não homologadas.",
                onClick = onWatch,
            )
            ListDivider()
            ListRow(
                "Este celular",
                subtitle = "Consultar armazenamento e diagnóstico local.",
                onClick = onThisPhone,
            )
        }
        Text(
            "A disponibilidade de cada função depende das permissões e do modelo do dispositivo.",
            Modifier.padding(top = 14.dp, bottom = 20.dp),
            color = CytTheme.colors.textTertiary,
            style = CytTheme.type.bodySmall,
        )
    }
}
