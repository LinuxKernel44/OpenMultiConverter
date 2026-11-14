package io.github.linuxkernel44.openmulticonverter.core

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.UUID

/**
 * Gives the ffmpeg child process access to a content Uri without copying it: we open a file descriptor
 * in this process and expose `/proc/<our pid>/fd/<n>` through a symlink (which keeps the file extension
 * as a format hint). Falls back to a cache copy if the descriptor is not a regular seekable file.
 */
class InputHandle private constructor(
    val path: String,
    private val pfd: ParcelFileDescriptor?,
    private val tempFiles: List<File>,
) : AutoCloseable {
    override fun close() {
        tempFiles.forEach { it.delete() }
        runCatching { pfd?.close() }
    }

    companion object {
        fun open(context: Context, uri: Uri, displayName: String, forceCopy: Boolean = false): InputHandle {
            val ext = displayName.substringAfterLast('.', "").lowercase().takeIf { it.length in 1..5 }?.let { ".$it" } ?: ""
            val dir = File(context.cacheDir, "in").apply { mkdirs() }
            if (!forceCopy) {
                val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                if (pfd != null) {
                    val seekable = runCatching { Os.lseek(pfd.fileDescriptor, 0, 1 /* SEEK_CUR */); true }.getOrDefault(false)
                    if (seekable) {
                        val link = File(dir, UUID.randomUUID().toString() + ext)
                        Os.symlink("/proc/${android.os.Process.myPid()}/fd/${pfd.fd}", link.path)
                        return InputHandle(link.path, pfd, listOf(link))
                    }
                    pfd.close()
                }
            }
            val copy = File(dir, UUID.randomUUID().toString() + ext)
            context.contentResolver.openInputStream(uri)?.use { i -> copy.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                ?: error("Cannot open $uri")
            return InputHandle(copy.path, null, listOf(copy))
        }
    }
}

object Sources {
    val MEDIA_EXTENSIONS = setOf(
        "mp3", "m4a", "m4b", "aac", "flac", "wav", "wave", "aif", "aiff", "aifc", "ogg", "oga", "opus", "wma", "wv", "ape", "tta",
        "ac3", "eac3", "mp2", "mp1", "amr", "awb", "dsf", "dff", "mka", "caf", "w64", "au", "snd", "mpc", "tak", "dts", "thd",
        "mp4", "m4v", "mkv", "webm", "mov", "avi", "wmv", "flv", "3gp", "3g2", "ts", "m2ts", "mpg", "mpeg", "ogv", "weba",
    )

    fun displayName(resolver: ContentResolver, uri: Uri): String {
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    /** Recursively lists media files in a tree Uri (from ACTION_OPEN_DOCUMENT_TREE). */
    fun listTree(context: Context, tree: Uri, limit: Int = 5000): List<Pair<Uri, String>> {
        val out = mutableListOf<Pair<Uri, String>>()
        val root = DocumentFile.fromTreeUri(context, tree) ?: return out
        fun walk(dir: DocumentFile) {
            for (f in dir.listFiles().sortedBy { it.name?.lowercase() }) {
                if (out.size >= limit) return
                if (f.isDirectory) walk(f)
                else {
                    val n = f.name ?: continue
                    if (n.substringAfterLast('.', "").lowercase() in MEDIA_EXTENSIONS) out += f.uri to n
                }
            }
        }
        walk(root)
        return out
    }
}

fun safeBaseName(name: String): String {
    val base = name.substringBeforeLast('.', name)
    return base.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "audio" }
}
