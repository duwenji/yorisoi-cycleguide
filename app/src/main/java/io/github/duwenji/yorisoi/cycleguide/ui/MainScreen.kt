package io.github.duwenji.yorisoi.cycleguide.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.duwenji.yorisoi.cycleguide.R
import io.github.duwenji.yorisoi.cycleguide.companion.Lines
import io.github.duwenji.yorisoi.cycleguide.guide.Line
import io.github.duwenji.yorisoi.cycleguide.settings.RideSettings
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles

/** Starting and finishing a ride, and what was said last; shown only while stopped. */
@Composable
fun MainScreen(
    riding: Boolean,
    settings: RideSettings,
    channelName: String,
    lastLine: Line?,
    hasLocationPermission: Boolean,
    onRequestPermission: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onSettings: () -> Unit,
) {
    // The rules on phones and earphones, before the first ride (requirements S-3, S-5, AC-8).
    var noticing by remember { mutableStateOf(false) }
    Scaffold { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text("走り方: ${RideStyles.byId(settings.styleId).label}　チャンネル: $channelName")
            lastLine?.let {
                Text(it.text, style = MaterialTheme.typography.bodyLarge)
                it.credit?.let { credit -> Text(credit, style = MaterialTheme.typography.bodySmall) }
            }
            when {
                !hasLocationPermission -> Button(onClick = onRequestPermission, modifier = Modifier.fillMaxWidth()) { Text("位置情報を許可する") }
                riding -> Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text("走行を終える") }
                else -> Button(
                    onClick = { if (settings.earphoneNoticeShown) onStart() else noticing = true },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("走り始める") }
            }
            OutlinedButton(onClick = onSettings, modifier = Modifier.fillMaxWidth()) { Text("設定") }
            Text("走っている間は画面に何も出しません。操作は止まってからにしてください。", style = MaterialTheme.typography.bodySmall)
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

/** While moving: nothing to read, only that a ride is on (requirements S-1, AC-7). */
@Composable
fun RidingScreen() {
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Text("走行中", color = Color.Gray, style = MaterialTheme.typography.headlineSmall)
    }
}
