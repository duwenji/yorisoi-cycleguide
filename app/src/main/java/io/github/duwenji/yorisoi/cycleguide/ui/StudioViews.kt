package io.github.duwenji.yorisoi.cycleguide.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.duwenji.yorisoi.kit.studio.NoteTtl
import io.github.duwenji.yorisoi.kit.studio.Reading
import io.github.duwenji.yorisoi.kit.studio.ReportReason
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// The studio on CycleGuide's screens (platform kit-studio README「画面」), shown only while stopped.

/** What a line of the studio is, above its text: not the companion's own words (ADR-STUDIO S-1). */
@Composable
fun StudioLabel(reading: Reading) {
    Text(
        when (reading) {
            is Reading.OfNote -> "一言（この辺りを通った人）"
            is Reading.OfPlacement -> "お知らせ（${reading.placement.providerName}）"
        },
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

private val REASONS = listOf(
    ReportReason.DANGER to "安全を損なう",
    ReportReason.HARASSMENT to "人を傷つける・つきまとい",
    ReportReason.AD to "宣伝・勧誘",
    ReportReason.RIGHTS to "権利の侵害",
    ReportReason.INAPPROPRIATE to "聞かせるのに合わない",
    ReportReason.OTHER to "そのほか",
)

/** What the user can do with a line of the studio: like, report and block a note; open and report a placement. */
@Composable
fun StudioLineActions(reading: Reading, viewModel: StudioViewModel) {
    var reporting by remember { mutableStateOf(false) }
    var blocking by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        when (reading) {
            is Reading.OfNote -> {
                var liked by remember(reading) { mutableStateOf(viewModel.liked(reading)) }
                TextButton(onClick = { viewModel.like(reading); liked = true }, enabled = !liked) { Text(if (liked) "いいね済み" else "いいね") }
                TextButton(onClick = { blocking = true }) { Text("ブロック") }
            }
            is Reading.OfPlacement -> TextButton(onClick = { viewModel.open(reading) }) { Text(if (reading.audio != null) "音声を聞く" else "開いた") }
        }
        TextButton(onClick = { reporting = true }) { Text("通報") }
    }
    if (reporting) {
        var reason by remember { mutableStateOf<ReportReason?>(null) }
        AlertDialog(
            onDismissRequest = { reporting = false },
            title = { Text("運営に知らせる") },
            text = {
                Column {
                    REASONS.forEach { (r, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(reason == r, role = Role.RadioButton) { reason = r },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = reason == r, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { reason?.let { viewModel.report(reading, it) }; reporting = false }, enabled = reason != null) { Text("知らせる") }
            },
            dismissButton = { TextButton(onClick = { reporting = false }) { Text("やめる") } },
        )
    }
    if (blocking && reading is Reading.OfNote) {
        AlertDialog(
            onDismissRequest = { blocking = false },
            title = { Text("この人をブロックしますか") },
            text = { Text("この一言を置いた人の一言は、これから届かなくなります。相手には知らされません。設定の「スタジオ」で解けます。") },
            confirmButton = { TextButton(onClick = { viewModel.block(reading); blocking = false }) { Text("ブロックする") } },
            dismissButton = { TextButton(onClick = { blocking = false }) { Text("やめる") } },
        )
    }
}

/** The button that places a note, stopped on a ride, where the device can write speech out (REQ-STUDIO F-1, F-2). */
@Composable
fun NoteMicButton(viewModel: StudioViewModel, modifier: Modifier = Modifier) {
    if (!viewModel.canPlace) return
    val context = LocalContext.current
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) viewModel.startNote() }
    SmallFloatingActionButton(
        onClick = {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) viewModel.startNote()
            else ask.launch(Manifest.permission.RECORD_AUDIO)
        },
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary,
        modifier = modifier,
    ) {
        Icon(Icons.Filled.Mic, contentDescription = "一言を置く")
    }
}

/** The dialogs of placing a note: the terms, listening, and checking what was heard (sent after 3 s untouched). */
@Composable
fun NoteFlow(viewModel: StudioViewModel) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    when (val s = step) {
        NoteStep.Idle -> Unit
        is NoteStep.Terms -> AlertDialog(
            onDismissRequest = viewModel::cancelNote,
            title = { Text("一言の利用規約") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("一言は、この辺りに来たほかの人に読み上げられます。名前は出ません。人を傷つけること、人の居場所、連絡先、宣伝は置けません。置く前に運営の AI が確かめます。")
                    TextButton(onClick = { uri.openUri(s.terms.url) }) { Text("利用規約を読む（版 ${s.terms.version}）") }
                }
            },
            confirmButton = { TextButton(onClick = { viewModel.acceptTerms(s.terms) }) { Text("同意して話す") } },
            dismissButton = { TextButton(onClick = viewModel::cancelNote) { Text("やめる") } },
        )
        NoteStep.Listening -> AlertDialog(
            onDismissRequest = viewModel::cancelNote,
            title = { Text("どうぞ話してください") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CircularProgressIndicator(Modifier.size(24.dp))
                    Text("60 文字まで。声はこの端末の中で文字にし、送るのは文字だけです")
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = viewModel::cancelNote) { Text("やめる") } },
        )
        is NoteStep.Confirm -> ConfirmNote(s, viewModel)
        NoteStep.Sending -> AlertDialog(
            onDismissRequest = {},
            title = { Text("確かめています") },
            text = { CircularProgressIndicator(Modifier.size(24.dp)) },
            confirmButton = {},
        )
    }
}

@Composable
private fun ConfirmNote(step: NoteStep.Confirm, viewModel: StudioViewModel) {
    var text by remember(step) { mutableStateOf(step.text) }
    var ttl by remember(step) { mutableStateOf(NoteTtl.DAY) }
    var withSpot by remember(step) { mutableStateOf(step.spotName != null) }
    var touched by remember(step) { mutableStateOf(false) }
    var left by remember(step) { mutableIntStateOf(3) }
    val send = { viewModel.send(text, step.spotName?.takeIf { withSpot }, ttl) }
    LaunchedEffect(step, touched) {
        if (touched) return@LaunchedEffect
        while (left > 0) {
            delay(1000)
            left--
        }
        send()
    }
    AlertDialog(
        onDismissRequest = viewModel::cancelNote,
        title = { Text("この一言を置きます") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { touched = true; text = it.replace("\n", " ").take(60) },
                    supportingText = { Text("${text.length}/60") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(NoteTtl.HOUR to "1 時間", NoteTtl.DAY to "24 時間", NoteTtl.WEEK to "7 日").forEach { (t, label) ->
                        FilterChip(selected = ttl == t, onClick = { touched = true; ttl = t }, label = { Text(label) })
                    }
                }
                step.spotName?.let { name ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(withSpot, role = Role.Switch) { touched = true; withSpot = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("「${name}」の名前を付ける", Modifier.weight(1f))
                        Switch(checked = withSpot, onCheckedChange = null)
                    }
                }
                if (!touched) Text("${left} 秒後に置きます", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { send() }, enabled = text.isNotBlank()) { Text("置く") } },
        dismissButton = { TextButton(onClick = viewModel::cancelNote) { Text("やめる") } },
    )
}

/**
 * The studio's part of the settings (REQ-STUDIO D-12, F-5, F-23): what is read out, the user's own
 * notes and blocks. Applied at once, not with the screen's save.
 */
@Composable
fun StudioSettingsPart(viewModel: StudioViewModel) {
    if (!viewModel.available) {
        Text("このビルドでは使えません（運営の AI の中継がありません）", style = MaterialTheme.typography.bodySmall)
        return
    }
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val mine by viewModel.mine.collectAsStateWithLifecycle()
    val blocks by viewModel.blocks.collectAsStateWithLifecycle()
    val uri = LocalUriHandler.current
    var explain by remember { mutableStateOf<((Boolean) -> Unit)?>(null) }
    // Nothing is read out before the user knows what it is (REQ-STUDIO D-12).
    fun turnOn(apply: (Boolean) -> Unit) = if (settings.explained) apply(true) else explain = apply

    Text(
        "走行中、この辺りを通った人が残した一言や、店・地域のお知らせを、話の合間に読み上げます。AI の話とは別に、出どころを言ってから読みます。一言を置く・いいね・通報・ブロックは、止まっているときにできます。どちらも変えるとすぐに反映します。",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    StudioSwitch("一言を聞く", "知らない人の一言（1 時間に 10 件まで）", settings.listenNotes) { on -> if (on) turnOn(viewModel::setListenNotes) else viewModel.setListenNotes(false) }
    StudioSwitch("お知らせを聞く", "運営が認めた店・地域のお知らせ（1 時間に 2 件まで）", settings.listenPlacements) { on -> if (on) turnOn(viewModel::setListenPlacements) else viewModel.setListenPlacements(false) }
    explain?.let { apply ->
        AlertDialog(
            onDismissRequest = { explain = null },
            title = { Text("スタジオの読み上げ") },
            text = {
                Text("走行中、その場所に置かれた知らない人の一言や、店・地域のお知らせが読み上げられます。運営の AI と通報で確かめていますが、中身は置いた人・出した人のものです。読み上げのあと、通報やブロックができます。")
            },
            confirmButton = { TextButton(onClick = { apply(true); explain = null }) { Text("聞く") } },
            dismissButton = { TextButton(onClick = { explain = null }) { Text("やめる") } },
        )
    }

    val format = remember { SimpleDateFormat("M/d H:mm", Locale.JAPAN) }
    Text("自分の一言", style = MaterialTheme.typography.labelLarge)
    if (mine == null) {
        TextButton(onClick = { viewModel.loadMine() }) { Text("自分の一言を見る") }
    } else {
        val (notes, left) = mine!!
        Text("今日あと $left 件置けます", style = MaterialTheme.typography.bodySmall)
        if (notes.isEmpty()) Text("置いている一言はありません", style = MaterialTheme.typography.bodySmall)
        notes.forEach { n ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        listOfNotNull(
                            "${format.format(Date.from(n.expiresAt))} まで",
                            if (n.state == "hidden") "通報で止まっています" else null,
                            "聞いた ${n.heard?.let { "${it.atLeast} 人以上" } ?: "3 人未満"}",
                            "いいね ${n.likes?.let { "${it.atLeast} 以上" } ?: "3 未満"}",
                        ).joinToString("・"),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { viewModel.deleteNote(n.id) }) { Text("消す") }
            }
        }
    }
    Text("ブロックした人", style = MaterialTheme.typography.labelLarge)
    if (blocks == null) {
        TextButton(onClick = { viewModel.loadBlocks() }) { Text("ブロックした人を見る") }
    } else {
        if (blocks!!.isEmpty()) Text("ブロックしている人はいません", style = MaterialTheme.typography.bodySmall)
        blocks!!.forEach { (block, label) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label?.let { "「$it」の人" } ?: "ブロックした人", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { viewModel.unblock(block.id) }) { Text("解く") }
            }
        }
    }
    TextButton(onClick = { uri.openUri(StudioViewModel.TERMS_URL) }) { Text("一言の利用規約") }
}

@Composable
private fun StudioSwitch(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}
