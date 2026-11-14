package io.github.linuxkernel44.openmulticonverter.core

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

class FfmpegException(message: String) : Exception(message)

/** Runs the bundled ffmpeg / ffprobe executables (shipped as lib*.so inside nativeLibraryDir). */
class Ffmpeg(context: Context) {
    private val dir = File(context.applicationInfo.nativeLibraryDir)
    private val ffmpeg = File(dir, "libffmpeg.so")
    private val ffprobe = File(dir, "libffprobe.so")

    fun probe(input: String): AudioInfo {
        val p = ProcessBuilder(
            ffprobe.path, "-v", "error", "-print_format", "json", "-show_streams", "-show_format", input,
        ).redirectErrorStream(false).start()
        val err = StringBuilder()
        val errThread = Thread { p.errorStream.bufferedReader().forEachLine { err.appendLine(it) } }.also { it.start() }
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(30, TimeUnit.SECONDS)) {
            p.destroyForcibly()
            throw FfmpegException("Timed out while reading file information")
        }
        errThread.join(1000)
        if (p.exitValue() != 0) throw FfmpegException(err.toString().trim().ifEmpty { "Unreadable file" })
        return AudioInfo.fromProbeJson(out) ?: throw FfmpegException("No audio stream found")
    }

    /**
     * Converts [input] to [output] using [plan]. [onProgress] receives the processed time in seconds.
     * Throws [InterruptedException] if the current thread is interrupted (the process is killed).
     */
    fun convert(input: String, output: File, plan: Plan, onProgress: (Double) -> Unit) {
        val cmd = buildList {
            add(ffmpeg.path)
            addAll(listOf("-nostdin", "-y", "-hide_banner", "-v", "error", "-nostats", "-progress", "pipe:1"))
            addAll(listOf("-i", input))
            addAll(plan.args)
            add(output.path)
        }
        val p = ProcessBuilder(cmd).start()
        val err = StringBuilder()
        val errThread = Thread {
            p.errorStream.bufferedReader().forEachLine { if (err.length < 4000) err.appendLine(it) }
        }.also { it.start() }
        try {
            p.inputStream.bufferedReader().forEachLine { line ->
                if (Thread.currentThread().isInterrupted) throw InterruptedException()
                if (line.startsWith("out_time_us=")) {
                    line.substringAfter('=').toLongOrNull()?.takeIf { it >= 0 }?.let { onProgress(it / 1_000_000.0) }
                }
            }
            val code = p.waitFor()
            errThread.join(1000)
            if (code != 0) throw FfmpegException(err.toString().trim().lines().lastOrNull().orEmpty().ifEmpty { "ffmpeg exited with code $code" })
        } catch (e: InterruptedException) {
            p.destroyForcibly()
            throw e
        } finally {
            if (p.isAlive) p.destroyForcibly()
        }
    }
}
