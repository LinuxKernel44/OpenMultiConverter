@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package io.github.linuxkernel44.openmulticonverter.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import android.net.Uri
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Queue
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.linuxkernel44.openmulticonverter.core.AudioInfo
import io.github.linuxkernel44.openmulticonverter.core.ConversionJob
import io.github.linuxkernel44.openmulticonverter.core.Formats
import io.github.linuxkernel44.openmulticonverter.core.JobState
import io.github.linuxkernel44.openmulticonverter.core.Mode
import io.github.linuxkernel44.openmulticonverter.core.OutputFormat

@Composable
fun OmcRoot(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var about by rememberSaveable { mutableStateOf(false) }
    val jobs by vm.jobs.collectAsState()
    val active = jobs.count { it.state == JobState.PENDING || it.state == JobState.RUNNING }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("OpenMultiConverter") },
                actions = { IconButton({ about = true }) { Icon(Icons.Default.Info, "About") } },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == 0, { tab = 0 }, { Icon(Icons.Default.Transform, null) }, label = { Text("Convert") })
                NavigationBarItem(tab == 1, { tab = 1 }, { Icon(Icons.Default.Queue, null) },
                    label = { Text(if (active > 0) "Queue ($active)" else "Queue") })
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad)) {
            if (tab == 0) ConvertScreen(vm, onStarted = { tab = 1 }) else QueueScreen(vm, jobs)
        }
    }
    if (about) AboutDialog { about = false }
}

@Composable
fun ConvertScreen(vm: MainViewModel, onStarted: () -> Unit) {
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { vm.addFiles(it) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::addFolder) }
    val pickOutput = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(vm::chooseOutputTree) }
    val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.convert(); onStarted() }
    val plans = vm.files.mapNotNull { vm.planFor(it) }
    val warnings = plans.flatMap { it.warnings }.distinct()

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        item { SectionTitle("1. Source files") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button({ pickFiles.launch(arrayOf("audio/*", "video/*", "application/ogg", "application/octet-stream")) }) {
                    Icon(Icons.Default.AudioFile, null); Spacer(Modifier.width(8.dp)); Text("Add files")
                }
                OutlinedButton({ pickFolder.launch(null) }) {
                    Icon(Icons.Default.Folder, null); Spacer(Modifier.width(8.dp)); Text("Add folder")
                }
                if (vm.scanning) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.CenterVertically))
            }
        }
        if (vm.files.isEmpty()) {
            item { Text("Pick audio files, video files (audio gets extracted) or a whole folder.", style = MaterialTheme.typography.bodyMedium) }
        } else {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${vm.files.size} file(s)", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    TextButton({ vm.clearFiles() }) { Text("Clear") }
                }
            }
            items(vm.files.toList(), key = { it.uri.toString() }) { f ->
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.padding(start = 12.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                when {
                                    f.probing -> "Reading…"
                                    f.error != null -> "Error: ${f.error}"
                                    else -> describe(f.info!!)
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (f.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton({ vm.remove(f) }) { Icon(Icons.Default.Close, "Remove") }
                    }
                }
            }
        }

        item { SectionTitle("2. Output format") }
        item { FormatGroup("Original", listOf(Formats.ORIGINAL), vm) }
        item { FormatGroup("Lossless", Formats.lossless, vm) }
        item { FormatGroup("Lossy", Formats.lossy, vm) }
        item { Text(vm.target.description, style = MaterialTheme.typography.bodySmall) }

        item { SettingsCard(vm) }

        if (plans.isNotEmpty()) {
            item { QualityCard(vm, plans.map { it.mode }, warnings.map { it.text }) }
        }

        item { SectionTitle("3. Output folder") }
        item {
            OutlinedButton({ pickOutput.launch(null) }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.FolderOpen, null); Spacer(Modifier.width(8.dp))
                Text(vm.outputTree?.let { Uri.decode(it.lastPathSegment ?: it.toString()) } ?: "Choose output folder", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        item {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33) notif.launch(Manifest.permission.POST_NOTIFICATIONS) else { vm.convert(); onStarted() }
                },
                enabled = vm.canConvert(),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                Icon(Icons.Default.PlayArrow, null); Spacer(Modifier.width(8.dp)); Text("Convert")
            }
        }
    }
}

private fun describe(i: AudioInfo): String = buildList {
    add(i.codec.uppercase())
    if (i.sampleRate > 0) add("%.1f kHz".format(i.sampleRate / 1000.0))
    when (i.sourceDepth) { Formats.FLOAT_DEPTH -> add("float"); 0 -> {}; else -> add("${i.sourceDepth}-bit") }
    add(when (i.channels) { 1 -> "mono"; 2 -> "stereo"; else -> "${i.channels} ch" })
    i.bitRate?.let { add("${it / 1000} kbps") }
    i.durationSec?.let { add("%d:%02d".format(it.toInt() / 60, it.toInt() % 60)) }
    if (i.coverStreamIndex != null) add("cover")
}.joinToString(" · ")

@Composable
private fun SectionTitle(text: String) = Text(text, style = MaterialTheme.typography.titleMedium)

@Composable
private fun FormatGroup(title: String, formats: List<OutputFormat>, vm: MainViewModel) {
    Column {
        Text(title, style = MaterialTheme.typography.labelMedium)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            formats.forEach { f ->
                FilterChip(vm.target.id == f.id, { vm.selectTarget(f) }, label = { Text(f.label) })
            }
        }
    }
}

@Composable
private fun SettingsCard(vm: MainViewModel) {
    var open by rememberSaveable { mutableStateOf(false) }
    val t = vm.target
    val s = vm.settings
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Advanced settings", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                TextButton({ open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (open && t.isOriginal) Text("Not applicable: the audio stream is copied untouched.", style = MaterialTheme.typography.bodySmall)
            if (open && !t.isOriginal) {
                Dropdown("Sample rate", s.sampleRate, listOf(null, 8000, 16000, 22050, 32000, 44100, 48000, 88200, 96000, 176400, 192000),
                    { it?.let { r -> "%.2f kHz".format(r / 1000.0).replace(".00", "") } ?: "Keep original" }) { vm.settings = s.copy(sampleRate = it) }
                Dropdown("Channels", s.channels, listOf(null, 1, 2), { when (it) { null -> "Keep original"; 1 -> "Mono"; else -> "Stereo" } }) { vm.settings = s.copy(channels = it) }
                if (t.bitDepths.isNotEmpty()) {
                    Dropdown("Bit depth", s.bitDepth, listOf<Int?>(null) + t.bitDepths,
                        { when (it) { null -> "Keep original"; Formats.FLOAT_DEPTH -> "32-bit float"; else -> "$it-bit" } }) { vm.settings = s.copy(bitDepth = it) }
                }
                if (t.bitrates.isNotEmpty()) {
                    Dropdown("Bitrate", s.bitrate, listOf<Int?>(null) + t.bitrates,
                        { it?.let { b -> (if (b % 1000 == 0) "${b / 1000}" else "%.2f".format(b / 1000.0)) + " kbps" } ?: if (t.defaultBitrate == null) "Best quality (VBR)" else "Default (${t.defaultBitrate / 1000} kbps)" }) { vm.settings = s.copy(bitrate = it) }
                }
                if (t.hasCompressionLevel) {
                    Text("Compression level: ${s.compressionLevel} (higher = smaller file, slower; always lossless)", style = MaterialTheme.typography.bodySmall)
                    Slider(s.compressionLevel.toFloat(), { vm.settings = s.copy(compressionLevel = it.toInt()) }, valueRange = 0f..12f, steps = 11)
                }
            }
        }
    }
}

@Composable
private fun <T> Dropdown(label: String, value: T, options: List<T>, text: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded, { expanded = it }) {
        OutlinedTextField(
            text(value), {}, readOnly = true, label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable).fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded, { expanded = false }) {
            options.forEach { o -> DropdownMenuItem({ Text(text(o)) }, { onSelect(o); expanded = false }) }
        }
    }
}

@Composable
private fun QualityCard(vm: MainViewModel, modes: List<Mode>, warnings: List<String>) {
    val bad = warnings.isNotEmpty() && modes.any { it == Mode.LOSSY || it == Mode.LOSSLESS_LOSSY_SOURCE }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (bad) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (bad) Icons.Default.Warning else Icons.Default.Info, null)
                Spacer(Modifier.width(8.dp))
                Text(
                    modes.groupingBy { it }.eachCount().entries.joinToString("\n") { "${it.value} × ${it.key.label}" },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            warnings.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
fun QueueScreen(vm: MainViewModel, jobs: List<ConversionJob>) {
    if (jobs.isEmpty()) {
        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Nothing in the queue yet.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row {
                TextButton({ vm.cancelAll() }) { Text("Cancel all") }
                TextButton({ vm.clearFinished() }) { Text("Clear finished") }
            }
        }
        items(jobs.reversed(), key = { it.id }) { j ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(j.sourceName, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                            Text(
                                "→ ${j.outputName ?: j.target.label}  ·  ${j.state.name.lowercase()}" + (j.mode?.let { "  ·  ${it.label}" } ?: ""),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (j.state == JobState.PENDING || j.state == JobState.RUNNING) IconButton({ vm.cancel(j.id) }) { Icon(Icons.Default.Close, "Cancel") }
                    }
                    if (j.state == JobState.RUNNING) {
                        if (j.progress > 0f) LinearProgressIndicator({ j.progress }, Modifier.fillMaxWidth()) else LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    if (j.state == JobState.FAILED) Text(j.message.orEmpty(), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun AboutDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onDismiss) { Text("Close") } },
        title = { Text("OpenMultiConverter 0.1.0") },
        text = {
            Text(
                "Open-source, fully offline audio converter. No network access, no tracking.\n\n" +
                    "Licensed under GPLv3. Conversion is powered by FFmpeg (built with LAME, Opus, Vorbis and OpenCORE-AMR). " +
                    "Source code: github.com/LinuxKernel44/OpenMultiConverter",
            )
        },
    )
}
