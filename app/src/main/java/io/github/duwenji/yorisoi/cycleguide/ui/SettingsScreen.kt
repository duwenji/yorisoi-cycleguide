package io.github.duwenji.yorisoi.cycleguide.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.duwenji.yorisoi.kit.llm.AiConnection
import io.github.duwenji.yorisoi.kit.llm.AiRoute
import io.github.duwenji.yorisoi.kit.llm.Provider
import io.github.duwenji.yorisoi.cycleguide.BuildConfig
import io.github.duwenji.yorisoi.cycleguide.diagnostics.RideExport
import io.github.duwenji.yorisoi.cycleguide.settings.RideSettings
import io.github.duwenji.yorisoi.kit.companion.SessionRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import io.github.duwenji.yorisoi.cycleguide.style.RideStyles
import io.github.duwenji.yorisoi.cycleguide.style.StyleItem

/**
 * The ride style and its values (requirements F-10, F-11): every style in [RideStyles.ALL], then
 * each value of the chosen one, grouped, with what can't work pointed out.
 */
@Composable
fun SettingsScreen(
    settings: RideSettings,
    onChange: ((RideSettings) -> RideSettings) -> Unit,
    onClose: () -> Unit,
    exportRides: (Uri) -> String,
) {
    val values = settings.values
    Scaffold { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("設定", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = onClose) { Text("閉じる") }
                }
                Text("走り方", style = MaterialTheme.typography.titleMedium)
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RideStyles.ALL.forEach { style ->
                        FilterChip(
                            selected = values.style.id == style.id,
                            onClick = { onChange { it.copy(styleId = style.id) } },
                            label = { Text(style.label) },
                        )
                    }
                }
                Text(values.style.summary, style = MaterialTheme.typography.bodySmall)
            }
            values.conflicts().forEach { message ->
                item { Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            StyleItem.entries.groupBy { it.group }.forEach { (group, items) ->
                item { Text(group, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                items.forEach { item ->
                    item {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(item.label, modifier = Modifier.weight(1f))
                            val value = values[item]
                            TextButton(onClick = { onChange { it.withValue(item, value - step(item)) } }, enabled = value > item.min) { Text("−") }
                            Text("$value${item.unit}")
                            TextButton(onClick = { onChange { it.withValue(item, value + step(item)) } }, enabled = value < item.max) { Text("＋") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = { onChange { it.resetStyle() } }, modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp)) {
                    Text("${values.style.label}の値を元に戻す")
                }
            }
            item { AiSettings(settings, onChange) }
            item { AboutSection(settings, onChange, exportRides) }
        }
    }
}

/**
 * How the AI is reached (platform ADR-AI-RELAY): the operator's AI, or the rider's own key for a
 * service they choose, with its model and a daily limit. The key stays on the device, encrypted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AiSettings(settings: RideSettings, onChange: ((RideSettings) -> RideSettings) -> Unit) {
    Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("AI", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = settings.aiRoute == AiRoute.OPERATOR,
                onClick = { onChange { it.copy(aiRoute = AiRoute.OPERATOR) } },
                label = { Text(AiConnection.OPERATOR_AI_NAME) },
            )
            FilterChip(
                selected = settings.aiRoute == AiRoute.OWN_KEY,
                onClick = { onChange { it.copy(aiRoute = AiRoute.OWN_KEY) } },
                label = { Text("自分の API キー") },
            )
        }
        if (settings.aiRoute == AiRoute.OPERATOR) {
            Text("運営が用意した AI を使います。1 日に使える回数は運営が決めます。", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Provider.entries.forEach { p ->
                FilterChip(selected = settings.provider == p, onClick = { onChange { it.copy(provider = p) } }, label = { Text(p.label) })
            }
        }
        OutlinedTextField(
            value = settings.apiKey(),
            onValueChange = { key -> onChange { it.copy(apiKeys = it.apiKeys + (it.provider to key)) } },
            label = { Text("API キー") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = settings.models[settings.provider].orEmpty(),
            onValueChange = { model -> onChange { it.copy(models = it.models + (it.provider to model)) } },
            label = { Text("モデル（空なら ${settings.provider.defaultModel.ifEmpty { "なし" }}）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        if (settings.provider == Provider.CUSTOM) {
            OutlinedTextField(
                value = settings.customBaseUrl,
                onValueChange = { url -> onChange { it.copy(customBaseUrl = url) } },
                label = { Text("接続先 URL") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text("1 日に AI を呼ぶ回数の上限", style = MaterialTheme.typography.bodyMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RideSettings.DAILY_AI_LIMITS.forEach { limit ->
                FilterChip(
                    selected = settings.dailyAiLimit == limit,
                    onClick = { onChange { it.copy(dailyAiLimit = limit) } },
                    label = { Text(limit?.let { "${it}回" } ?: "上限なし") },
                )
            }
        }
    }
}

/**
 * The sources and the version; tapping the version seven times turns developer mode on (as
 * Android's own), which keeps a detailed record of each ride, exported from here (requirements F-29).
 */
@Composable
private fun AboutSection(settings: RideSettings, onChange: ((RideSettings) -> RideSettings) -> Unit, exportRides: (Uri) -> String) {
    val context = LocalContext.current
    var taps by remember { mutableIntStateOf(0) }
    val exporter = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let { Toast.makeText(context, exportRides(it), Toast.LENGTH_LONG).show() }
    }
    Column(Modifier.padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("このアプリについて", style = MaterialTheme.typography.titleMedium)
        Text("スポット・信号: © OpenStreetMap contributors　天気: Open-Meteo.com", style = MaterialTheme.typography.bodySmall)
        Text(
            "バージョン ${BuildConfig.VERSION_NAME}" + when {
                settings.developerMode -> "（開発者モード）"
                taps in 3..6 -> "（あと ${7 - taps} 回で開発者モード）"
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth().clickable(enabled = !settings.developerMode) {
                taps += 1
                if (taps >= 7) onChange { it.copy(developerMode = true) }
            }.padding(vertical = 8.dp),
        )
        if (settings.developerMode) {
            Text(
                "走行ごとの詳しい記録（位置・判定・発言・AI とのやり取り）を、この端末にだけ最新 ${SessionRecorder.MAX_SESSIONS} 件まで残します。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(
                onClick = { exporter.launch(RideExport.fileName(SimpleDateFormat("yyyyMMdd-HHmm", Locale.JAPAN).format(Date()))) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("走行の記録を書き出す") }
            TextButton(onClick = {
                taps = 0
                onChange { it.copy(developerMode = false) }
            }) { Text("開発者モードをやめる") }
        }
    }
}

/** Distances move by 50m, values that reach 100 or more by 5, the rest by 1. */
private fun step(item: StyleItem): Int = when {
    item.unit == "m" -> 50
    item.max >= 100 -> 5
    else -> 1
}
