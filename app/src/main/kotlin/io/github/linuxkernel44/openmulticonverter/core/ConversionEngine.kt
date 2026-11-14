package io.github.linuxkernel44.openmulticonverter.core

import android.content.Context
import android.net.Uri
import io.github.linuxkernel44.openmulticonverter.service.ConversionService
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

enum class JobState { PENDING, RUNNING, DONE, FAILED, CANCELLED }

data class ConversionJob(
    val id: String = UUID.randomUUID().toString(),
    val source: Uri,
    val sourceName: String,
    val target: OutputFormat,
    val settings: Settings,
    val outputTree: Uri,
    val state: JobState = JobState.PENDING,
    val progress: Float = 0f,
    val mode: Mode? = null,
    val outputName: String? = null,
    val message: String? = null,
)

/** Sequential conversion queue. Lives in the application scope; [ConversionService] keeps the process alive. */
class ConversionEngine(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ffmpeg = Ffmpeg(context)
    private val _jobs = MutableStateFlow<List<ConversionJob>>(emptyList())
    val jobs: StateFlow<List<ConversionJob>> = _jobs.asStateFlow()

    private var worker: Job? = null
    @Volatile private var current: Thread? = null
    private val cancelCurrent = AtomicBoolean(false)
    private val cancelledIds = mutableSetOf<String>()

    val isActive: Boolean get() = _jobs.value.any { it.state == JobState.PENDING || it.state == JobState.RUNNING }

    fun enqueue(newJobs: List<ConversionJob>) {
        if (newJobs.isEmpty()) return
        _jobs.update { it + newJobs }
        ConversionService.start(context)
        synchronized(this) {
            if (worker?.isActive != true) worker = scope.launch { runQueue() }
        }
    }

    fun cancel(id: String) {
        synchronized(cancelledIds) { cancelledIds += id }
        val j = _jobs.value.firstOrNull { it.id == id } ?: return
        if (j.state == JobState.PENDING) update(id) { it.copy(state = JobState.CANCELLED, message = "Cancelled") }
        else if (j.state == JobState.RUNNING) { cancelCurrent.set(true); current?.interrupt() }
    }

    fun cancelAll() {
        _jobs.value.filter { it.state == JobState.PENDING || it.state == JobState.RUNNING }.forEach { cancel(it.id) }
    }

    fun clearFinished() = _jobs.update { l -> l.filter { it.state == JobState.PENDING || it.state == JobState.RUNNING } }

    fun probe(uri: Uri, name: String): AudioInfo = openProbed(uri, name).let { (h, info) -> h.close(); info }

    /** Opens the source and probes it, retrying through a cache copy if direct access fails. */
    private fun openProbed(uri: Uri, name: String): Pair<InputHandle, AudioInfo> {
        var handle = InputHandle.open(context, uri, name)
        return try {
            handle to ffmpeg.probe(handle.path)
        } catch (e: FfmpegException) {
            handle.close()
            handle = InputHandle.open(context, uri, name, forceCopy = true)
            try { handle to ffmpeg.probe(handle.path) } catch (e2: Exception) { handle.close(); throw e2 }
        }
    }

    private fun update(id: String, f: (ConversionJob) -> ConversionJob) =
        _jobs.update { l -> l.map { if (it.id == id) f(it) else it } }

    private suspend fun runQueue() {
        while (scope.isActive) {
            val job = _jobs.value.firstOrNull { it.state == JobState.PENDING } ?: break
            update(job.id) { it.copy(state = JobState.RUNNING, progress = 0f) }
            cancelCurrent.set(false)
            // Run on a dedicated thread so that cancel() can interrupt blocking process I/O.
            val t = Thread { runJob(job) }.also { current = it; it.start() }
            t.join()
            current = null
        }
    }

    private fun runJob(job: ConversionJob) {
        val tmpDir = File(context.cacheDir, "out").apply { mkdirs() }
        var tmp: File? = null
        try {
            val (handle, info) = openProbed(job.source, job.sourceName)
            try {
                val plan = Planner.plan(info, job.target, job.settings)
                val fmt = plan.outputFormat
                val outName = safeBaseName(job.sourceName) + "." + fmt.extension
                update(job.id) { it.copy(mode = plan.mode, outputName = outName) }
                tmp = File(tmpDir, UUID.randomUUID().toString() + "." + fmt.extension)
                val duration = info.durationSec?.takeIf { it > 0 }
                ffmpeg.convert(handle.path, tmp, plan) { t ->
                    if (duration != null) update(job.id) { it.copy(progress = (t / duration).toFloat().coerceIn(0f, 0.99f)) }
                }
                if (cancelCurrent.get()) throw InterruptedException()
                val tree = DocumentFile.fromTreeUri(context, job.outputTree) ?: throw FfmpegException("Output folder is not accessible")
                val doc = tree.createFile(fmt.mime, outName) ?: throw FfmpegException("Cannot create output file")
                try {
                    context.contentResolver.openOutputStream(doc.uri, "w")!!.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                } catch (e: Exception) {
                    doc.delete(); throw e
                }
                update(job.id) { it.copy(state = JobState.DONE, progress = 1f, outputName = doc.name ?: outName, message = null) }
            } finally {
                handle.close()
            }
        } catch (e: InterruptedException) {
            update(job.id) { it.copy(state = JobState.CANCELLED, message = "Cancelled") }
        } catch (e: Exception) {
            update(job.id) { it.copy(state = JobState.FAILED, message = e.message ?: e.javaClass.simpleName) }
        } finally {
            tmp?.delete()
        }
    }

    fun shutdown() = scope.cancel()
}
