package tv.cleanyour.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tv.cleanyour.core.model.TvId
import tv.cleanyour.ui.design.component.ScreenScaffold
import tv.cleanyour.ui.design.component.ScreenTitle
import tv.cleanyour.ui.design.theme.Glow

/**
 * Device selection is independent of TV registration and never runs ADB
 * automatically. Every transition is explicit; the existing TV setup
 * machine still handles its own authorization and restore points.
 */
@Composable
internal fun DevicePickerScreen(
    graph: AppGraph,
    onSelectTv: (TvId) -> Unit,
    onAddTv: () -> Unit,
    onOtherAndroid: () -> Unit,
    onWatch: () -> Unit,
    onThisPhone: () -> Unit,
) {
    val televisions by graph.tvs.tvs.collectAsStateWithLifecycle()
    ScreenScaffold(Glow.Tv) {
        ScreenTitle("Qual dispositivo deseja gerenciar?", Modifier.padding(top = 20.dp))
        Text(
            "Escolha um aparelho. Nenhuma limpeza será executada automaticamente.",
            Modifier.padding(top = 10.dp, bottom = 18.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            televisions.forEach { tv ->
                Button(
                    onClick = { onSelectTv(tv.id) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("TV: ${tv.nickname.ifBlank { tv.facts.model }}")
                }
            }
            OutlinedButton(onClick = onAddTv, modifier = Modifier.fillMaxWidth()) {
                Text("Adicionar outra TV / Google TV")
            }
            HorizontalDivider()
            OutlinedButton(onClick = onOtherAndroid, modifier = Modifier.fillMaxWidth()) {
                Text("Outro celular ou tablet Android")
            }
            Text(
                "Requer conexão ADB autorizada. O pareamento moderno por código ainda não está disponível.",
            )
            OutlinedButton(onClick = onWatch, modifier = Modifier.fillMaxWidth()) {
                Text("Relógio Wear OS")
            }
            Text(
                "Diagnóstico somente leitura. A desativação de serviços ainda não foi homologada.",
            )
            OutlinedButton(onClick = onThisPhone, modifier = Modifier.fillMaxWidth()) {
                Text("Este celular — diagnóstico")
            }
        }
    }
}
