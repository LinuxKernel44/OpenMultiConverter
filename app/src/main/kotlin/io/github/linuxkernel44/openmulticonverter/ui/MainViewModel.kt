package io.github.linuxkernel44.openmulticonverter.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.linuxkernel44.openmulticonverter.OmcApp
import io.github.linuxkernel44.openmulticonverter.core.AudioInfo
import io.github.linuxkernel44.openmulticonverter.core.ConversionJob
import io.github.linuxkernel44.openmulticonverter.core.Formats
import io.github.linuxkernel44.openmulticonverter.core.OutputFormat
import io.github.linuxkernel44.openmulticonverter.core.Plan
import io.github.linuxkernel44.openmulticonverter.core.Planner
import io.github.linuxkernel44.openmulticonverter.core.Settings
import io.github.linuxkernel44.openmulticonverter.core.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SelectedFile(val uri: Uri, val name: String) {
    var info by mutableStateOf<AudioInfo?>(null)
    var error by mutableStateOf<String?>(null)
    var probing by mutableStateOf(true)
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val engine = (app as OmcApp).engine
    private val prefs = app.getSharedPreferences("omc", 0)

    val files = mutableStateListOf<SelectedFile>()
    var target by mutableStateOf<OutputFormat>(Formats.FLAC)
    var settings by mutableStateOf(Settings())
    var outputTree by mutableStateOf(prefs.getString("output_tree", null)?.let(Uri::parse)?.takeIf { hasPermission(it) })
    var scanning by mutableStateOf(false)
    val jobs = engine.jobs

    private fun hasPermission(uri: Uri) =
        getApplication<Application>().contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }

    fun chooseOutputTree(uri: Uri) {
        getApplication<Application>().contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        outputTree = uri
        prefs.edit().putString("output_tree", uri.toString()).apply()
    }

    fun selectTarget(f: OutputFormat) {
        target = f
        // Keep only settings that still make sense for the new format.
        settings = Settings(
            sampleRate = settings.sampleRate,
            channels = settings.channels,
            bitDepth = settings.bitDepth?.takeIf { it in f.bitDepths },
            bitrate = settings.bitrate?.takeIf { it in f.bitrates },
            compressionLevel = settings.compressionLevel,
        )
    }

    fun addFiles(uris: List<Uri>) {
        val ctx = getApplication<Application>()
        val named = uris.map { it to Sources.displayName(ctx.contentResolver, it) }
        addNamed(named)
    }

    fun addFolder(tree: Uri) {
        viewModelScope.launch {
            scanning = true
            val found = withContext(Dispatchers.IO) { Sources.listTree(getApplication(), tree) }
            addNamed(found)
            scanning = false
        }
    }

    private fun addNamed(items: List<Pair<Uri, String>>) {
        for ((uri, name) in items) {
            if (files.any { it.uri == uri }) continue
            val f = SelectedFile(uri, name)
            files += f
            viewModelScope.launch {
                runCatching { withContext(Dispatchers.IO) { engine.probe(uri, name) } }
                    .onSuccess { f.info = it }
                    .onFailure { f.error = it.message ?: "Unreadable file" }
                f.probing = false
            }
        }
    }

    fun remove(f: SelectedFile) { files.remove(f) }
    fun clearFiles() = files.clear()

    fun planFor(f: SelectedFile): Plan? = f.info?.let { Planner.plan(it, target, settings) }

    fun canConvert() = outputTree != null && files.any { it.info != null }

    fun convert() {
        val tree = outputTree ?: return
        val ready = files.filter { it.info != null }
        engine.enqueue(ready.map { ConversionJob(source = it.uri, sourceName = it.name, target = target, settings = settings, outputTree = tree) })
        files.removeAll(ready.toSet())
    }

    fun cancel(id: String) = engine.cancel(id)
    fun cancelAll() = engine.cancelAll()
    fun clearFinished() = engine.clearFinished()
}
