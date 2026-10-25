package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.core.format.TalkLevel
import io.github.duwenji.yorisoi.cycleguide.BuildConfig
import io.github.duwenji.yorisoi.cycleguide.CycleApp
import io.github.duwenji.yorisoi.cycleguide.channel.AppReport
import io.github.duwenji.yorisoi.kit.channel.Station
import io.github.duwenji.yorisoi.kit.channel.remote.ChannelSyncException
import io.github.duwenji.yorisoi.kit.channel.remote.ListedStatus
import io.github.duwenji.yorisoi.kit.channel.remote.ListedView
import io.github.duwenji.yorisoi.kit.channel.remote.ProviderView
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("M/d HH:mm").withZone(ZoneId.systemDefault())

/**
 * The channel lists the user added and the channels they deliver (requirements F-26, API-002
 * 設定画面のリスト). Opened only outside a ride, from the settings. A channel taken in is used only
 * once the rider accepts how much it talks (platform ADR-004 H-9).
 */
@Composable
fun ChannelsScreen(app: CycleApp, onClose: () -> Unit) {
    val channels = app.channels
    val providers by channels.providers.collectAsStateWithLifecycle()
    val notices by channels.notices.collectAsStateWithLifecycle()
    val busy by channels.busy.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var pending by remember { mutableStateOf<Station?>(null) }

    /** Runs [work], showing why it failed in the user's words. */
    fun act(work: suspend () -> Unit) {
        message = null
        scope.launch {
            try {
                work()
            } catch (e: ChannelSyncException) {
                message = e.message
            }
        }
    }

    fun use(providerId: String, channelId: String) = act {
        channels.install(providerId, channelId)
        val id = "$providerId/$channelId"
        pending = withTimeoutOrNull(5_000) { app.stations.list.first { l -> l.any { it.id == id } } }?.first { it.id == id }
    }

    Scaffold { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("チャンネル・リスト", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                TextButton(onClick = onClose) { Text("閉じる") }
            }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            if (notices.isNotEmpty()) {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("お知らせ", style = MaterialTheme.typography.titleSmall)
                        notices.forEach { Text("${TIME.format(it.at)}  ${it.text}", style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { act { channels.dismissNotices() } }) { Text("確認した") }
                    }
                }
            }
            Text(
                "チャンネル・リストは、運営（チャンネルと素材を審査する組織）が審査して署名したチャンネルの一覧です。足したリストは 1 日ごと（アプリの起動時と走行の開始時）に確かめます。" +
                    "このアプリで使えるのは、自転車向け（cycle）のチャンネルだけです。天気の急変・日の入りの案内は、どのチャンネルでも同じです。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (providers.isEmpty()) Text("リストはまだありません。", style = MaterialTheme.typography.bodyMedium)
            providers.forEach { view ->
                ProviderCard(
                    view, enabled = !busy,
                    onEnabled = { on -> act { channels.setEnabled(view.record.id, on) } },
                    onRemove = { act { channels.removeProvider(view.record.id) } },
                    onUse = { use(view.record.id, it) },
                    onUpdate = { act { channels.install(view.record.id, it) } },
                    onDelete = { act { channels.uninstall(view.record.id, it) } },
                    stationOf = { id -> app.stations.all.firstOrNull { it.id == "${view.record.id}/$id" } },
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                OutlinedButton(onClick = { adding = true }, enabled = !busy) { Text("リストを足す") }
                OutlinedButton(onClick = { act { channels.refreshNow() } }, enabled = !busy && providers.isNotEmpty()) { Text("今すぐ確かめる") }
            }
        }
    }
    if (adding) {
        AddProviderDialog(onDismiss = { adding = false }, onAdd = { url, id ->
            act {
                channels.addProvider(url, id)
                adding = false
            }
        })
    }
    pending?.let { station ->
        UseDialog(
            station, app.stations.standard,
            onUse = {
                pending = null
                app.settings.update { it.copy(stationId = station.id) }
            },
            onCancel = {
                pending = null
                val (provider, channel) = station.id.split('/', limit = 2)
                act { channels.uninstall(provider, channel) }
            },
        )
    }
}

/** How much the channel talks, before it is used (platform ADR-004 H-9). */
@Composable
private fun UseDialog(station: Station, standard: Station, onUse: () -> Unit, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("「${station.name}」を使いますか") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(station.manifest.summary)
                Text("話す量: ${label(station.talkLevel)}（標準のチャンネルは${label(standard.talkLevel)}）")
                if (station.talkLevel.ordinal > standard.talkLevel.ordinal) {
                    Text(
                        "標準のチャンネルより AI を多く呼ぶため、運営の AI では 1 日に使える回数を早く使い切り、自分の API キーでは費用が増えることがあります。",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onUse) { Text("使う") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("使わない") } },
    )
}

private fun label(level: TalkLevel) = when (level) {
    TalkLevel.QUIET -> "控えめ"
    TalkLevel.NORMAL -> "ふつう"
    TalkLevel.CHATTY -> "おしゃべり"
}

@Composable
private fun ProviderCard(
    view: ProviderView,
    enabled: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRemove: () -> Unit,
    onUse: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDelete: (String) -> Unit,
    stationOf: (String) -> Station?,
) {
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    val p = view.record
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(view.name, style = MaterialTheme.typography.titleMedium)
                    Text(p.id, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = p.enabled, onCheckedChange = onEnabled, enabled = enabled)
            }
            Text(
                buildString {
                    append(p.fetchedAt?.let { "最後に確かめた日時: ${TIME.format(it)}" } ?: "まだ確かめていません")
                    view.expiresAt?.let { append("（リストの期限 ${TIME.format(it)}）") }
                },
                style = MaterialTheme.typography.bodySmall,
            )
            if (!view.listValid && p.list != null) Text("リストの期限が切れています。このチャンネルは使えません", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            p.error?.let { Text("確かめられませんでした: $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (view.channels.isEmpty()) Text("このアプリで使えるチャンネルはありません", style = MaterialTheme.typography.bodySmall)
            view.channels.forEach { ChannelRow(it, enabled, onUse, onUpdate, onDelete, stationOf) }
            view.gone.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.id}（リストから外れたため使えません）", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { onDelete(c.id) }, enabled = enabled) { Text("削除") }
                }
            }
            TextButton(onClick = { confirmRemove = true }, enabled = enabled) { Text("このリストを外す") }
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("リストを外しますか？") },
            text = { Text("このリストのチャンネルは端末から消え、使えなくなります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    onRemove()
                }) { Text("外す") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("やめる") } },
        )
    }
}

@Composable
private fun ChannelRow(
    view: ListedView,
    enabled: Boolean,
    onUse: (String) -> Unit,
    onUpdate: (String) -> Unit,
    onDelete: (String) -> Unit,
    stationOf: (String) -> Station?,
) {
    val context = LocalContext.current
    val c = view.channel
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(c.name, style = MaterialTheme.typography.bodyLarge)
        Text("${c.summary}（チャンネル提供者: ${c.publisherName}、版 ${c.version}）", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        c.publisherChange?.let { Text("チャンネル提供者の鍵が移し替えられました: ${it.reason}", style = MaterialTheme.typography.bodySmall) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (view.status) {
                ListedStatus.AVAILABLE -> Button(onClick = { onUse(c.id) }, enabled = enabled) { Text("取り込んで使う") }
                ListedStatus.INSTALLED -> Text("取り込み済み", style = MaterialTheme.typography.labelMedium)
                ListedStatus.UPDATE_PENDING -> OutlinedButton(onClick = { onUpdate(c.id) }, enabled = enabled) { Text("新しい版を取り込む") }
                ListedStatus.BLOCKED -> Text("チャンネル提供者が裏付けなく変わったため、新しい版は使いません", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                ListedStatus.NEEDS_APP_UPDATE -> Text("アプリの更新が必要です", style = MaterialTheme.typography.bodySmall)
                ListedStatus.UNSUPPORTED -> Text("このアプリでは使えない形式です", style = MaterialTheme.typography.bodySmall)
            }
            if (view.installed != null) {
                TextButton(onClick = { onDelete(c.id) }, enabled = enabled) { Text("削除") }
                stationOf(c.id)?.let { station ->
                    TextButton(onClick = {
                        context.startActivity(AppReport.mail(AppReport.report.target(station), BuildConfig.VERSION_NAME))
                    }) { Text("問題を知らせる") }
                }
            }
        }
    }
}

@Composable
private fun AddProviderDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var url by rememberSaveable { mutableStateOf("https://") }
    var id by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("リストを足す") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "このリストの運営が審査したチャンネルを信用することになります。リストIDは、運営のサイトなど、このアプリとは別の方法で確かめたものを入れてください。",
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedTextField(url, { url = it }, label = { Text("URL") }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                OutlinedTextField(id, { id = it.trim() }, label = { Text("リストID（sc1…）") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(url, id) }, enabled = url.length > 8 && id.isNotEmpty()) { Text("足す") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
}
