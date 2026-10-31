package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Podcasts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.duwenji.yorisoi.kit.ui.ScreenBar
import io.github.duwenji.yorisoi.cycleguide.R
import io.github.duwenji.yorisoi.cycleguide.companion.Lines
import io.github.duwenji.yorisoi.cycleguide.guide.Line
import io.github.duwenji.yorisoi.cycleguide.settings.RideSettings
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles

/**
 * Starting and finishing a ride, and what was said last; shown only while stopped. The map fills
 * the screen as in SanpoGuide; the start button sits at the bottom, big enough for gloved hands.
 */
@Composable
fun MainScreen(
    riding: Boolean,
    /** The ride is ending: its look back is being written. */
    finishing: Boolean,
    settings: RideSettings,
    channelName: String,
    lastLine: Line?,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
    /** Opens the rides finished; null during a ride, or when there are none. */
    onHistory: (() -> Unit)? = null,
    /** The map while stopped on a ride, or of the last ride outside one; null when there is none to show. */
    map: (@Composable (Modifier) -> Unit)? = null,
    /** The studio: what can be done with its line, and placing a note; null without the studio. */
    studio: StudioViewModel? = null,
) {
    // The rules on phones and earphones, before the first ride (requirements S-3, S-5, AC-8).
    var noticing by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            ScreenBar(stringResource(R.string.app_name)) {
                onHistory?.let { IconButton(onClick = it) { Icon(Icons.Filled.History, contentDescription = "走行の履歴") } }
                IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, contentDescription = "設定") }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(top = padding.calculateTopPadding())) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatusChip(Icons.Filled.Tune, "走り方: ${RideStyles.byId(settings.styleId).label}", onSettings)
                StatusChip(Icons.Outlined.Podcasts, channelName, onSettings)
            }
            lastLine?.let { LastLineCard(it, studio, Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                if (map != null) map(Modifier.fillMaxSize().padding(horizontal = 16.dp).clip(MaterialTheme.shapes.medium))
                else NoMap(Modifier.fillMaxSize())
                // Placing a note: stopped on a ride (the screen is shown only while stopped).
                if (riding && !finishing && studio != null) NoteMicButton(studio, Modifier.align(Alignment.BottomEnd).padding(end = 28.dp, bottom = 12.dp))
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                when {
                    !hasLocationPermission -> BigButton(Icons.Filled.LocationOn, "位置情報を許可する", onRequestPermission)
                    // The look back takes a few seconds: the button says so at once (F-28).
                    finishing -> BigButton(Icons.Filled.Stop, "振り返り中…", onClick = {}, enabled = false, tonal = true)
                    riding -> BigButton(Icons.Filled.Stop, "走行を終える", onStop, tonal = true)
                    else -> BigButton(Icons.AutoMirrored.Filled.DirectionsBike, "走り始める", {
                        if (settings.earphoneNoticeShown) onStart() else noticing = true
                    })
                }
                Text(
                    "走っている間は画面に何も出しません。操作は止まってからにしてください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    if (noticing) {
        AlertDialog(
            onDismissRequest = { noticing = false },
            title = { Text("走る前に") },
            text = { Text(Lines.SAFETY_NOTICE) },
            confirmButton = {
                TextButton(onClick = {
                    noticing = false
                    onStart()
                }) { Text("わかりました") }
            },
        )
    }
}

/** What the ride is set to; pressed, the settings. */
@Composable
private fun StatusChip(icon: ImageVector, text: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(text) }, leadingIcon = { Icon(icon, contentDescription = null, Modifier.size(18.dp)) })
}

/** What the companion said last, with its credit; a line of the studio with what to do with it. */
@Composable
private fun LastLineCard(line: Line, studio: StudioViewModel?, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primaryContainer, shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            line.studio?.let { StudioLabel(it) }
            Text(line.text, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
            line.credit?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) }
            if (studio != null) line.studio?.let { StudioLineActions(it, studio) }
        }
    }
}

/** Before the first ride: the app's mark where the map will be. */
@Composable
private fun NoMap(modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(96.dp).background(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.shapes.extraLarge), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.DirectionsBike, contentDescription = null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
            }
            Text("走った道は、ここに地図で出ます", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The one action of the screen: tall and full width. [tonal] for ending, quieter than starting. */
@Composable
private fun BigButton(icon: ImageVector, text: String, onClick: () -> Unit, enabled: Boolean = true, tonal: Boolean = false) {
    val modifier = Modifier.fillMaxWidth().height(56.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
    if (tonal) FilledTonalButton(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
    else Button(onClick = onClick, enabled = enabled, modifier = modifier) { content() }
}

/** While moving: nothing to read, only that a ride is on (requirements S-1, AC-7). */
@Composable
fun RidingScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("走行中", color = Color.Gray, style = MaterialTheme.typography.headlineSmall)
    }
}
