package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.duwenji.yorisoi.cycleguide.history.RideRecord
import io.github.duwenji.yorisoi.cycleguide.history.RideSummary
import io.github.duwenji.yorisoi.kit.geo.LatLon
import io.github.duwenji.yorisoi.kit.geo.map.NewWayFromTrip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * The rides finished (requirements F-28): the list, and one ride with its map, the look back and
 * what was said at each spot. Opened only outside a ride (S-2).
 *
 * @param ride reads one ride's file (`RideHistory.ride`)
 * @param saveWay makes a frequent way of part of a ride: its name, its points, when the ride ended (REQ-FREQUENT-WAYS F-16)
 */
@Composable
fun HistoryScreen(rides: List<RideSummary>, ride: (Long) -> RideRecord?, saveWay: (String, List<LatLon>, Long) -> Unit, onClose: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    open?.let { startedAt ->
        BackHandler { open = null }
        RideDetail(startedAt, ride, saveWay, onClose = { open = null })
        return
    }
    Scaffold { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("走行の履歴", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("閉じる") }
                }
            }
            items(rides, key = { it.startedAt }) { r ->
                Column(Modifier.fillMaxWidth().clickable { open = r.startedAt }.padding(vertical = 12.dp)) {
                    Text(r.text(), style = MaterialTheme.typography.bodyLarge)
                    Text("話した見どころ ${r.spotCount} か所", style = MaterialTheme.typography.bodySmall)
                }
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun RideDetail(startedAt: Long, read: (Long) -> RideRecord?, saveWay: (String, List<LatLon>, Long) -> Unit, onClose: () -> Unit) {
    val ride by produceState<RideRecord?>(null, startedAt) { value = withContext(Dispatchers.IO) { read(startedAt) } }
    var makingWay by rememberSaveable { mutableStateOf(false) }
    var madeWay by rememberSaveable { mutableStateOf<String?>(null) }
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("走行", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("一覧へ") }
                }
            }
            val r = ride ?: return@LazyColumn
            item { Text(r.summary.text(), style = MaterialTheme.typography.bodyLarge) }
            r.recap?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium) } }
            // The frequent ways gone along, and those this ride made (REQ-FREQUENT-WAYS F-12, D-12).
            r.ways.filter { it.new }.forEach { way -> item { Text("よく通る道にしました: ${way.name}", style = MaterialTheme.typography.bodyMedium) } }
            r.ways.filter { !it.new }.takeIf { it.isNotEmpty() }?.let { ways ->
                item {
                    Text(
                        "通ったよく通る道: " + ways.joinToString("、") { "${it.name}（${it.passes} 回目）" },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            if (makingWay) {
                item {
                    NewWayFromTrip(
                        r.route,
                        onSave = { name, points ->
                            saveWay(name, points, r.endedAt)
                            makingWay = false
                            madeWay = name
                        },
                        onCancel = { makingWay = false },
                    )
                }
            } else {
                item { PastRideMap(r, Modifier.fillMaxWidth().height(320.dp)) }
                item {
                    madeWay?.let { Text("「$it」をよく通る道にしました", style = MaterialTheme.typography.bodySmall) }
                    OutlinedButton(onClick = { makingWay = true }, modifier = Modifier.fillMaxWidth()) { Text("この走行からよく通る道を作る") }
                }
            }
            if (r.talks.isNotEmpty()) item { Text("話した見どころ", style = MaterialTheme.typography.titleMedium) }
            items(r.talks) { talk ->
                Column(Modifier.padding(vertical = 4.dp)) {
                    Text("${clock.format(talk.at)}　${talk.name}", style = MaterialTheme.typography.titleSmall)
                    Text(talk.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private val clock = SimpleDateFormat("H:mm", Locale.JAPAN)
