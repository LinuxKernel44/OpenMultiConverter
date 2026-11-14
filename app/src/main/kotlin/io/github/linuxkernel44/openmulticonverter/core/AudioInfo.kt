package io.github.linuxkernel44.openmulticonverter.core

import org.json.JSONObject

/** What ffprobe tells us about the audio stream we are going to convert. */
data class AudioInfo(
    val streamIndex: Int,
    val codec: String,
    val sampleRate: Int,
    val channels: Int,
    val bitRate: Long?,
    val sampleFmt: String?,
    /** Bits per sample actually stored (0 if unknown / not applicable). */
    val bitsPerSample: Int,
    val durationSec: Double?,
    /** Index of an embedded cover-art stream, if any. */
    val coverStreamIndex: Int?,
    val coverCodec: String?,
    val containerName: String?,
    val tags: Map<String, String>,
) {
    val isLosslessSource: Boolean get() = codec in LOSSLESS_CODECS || codec.startsWith("pcm_") || codec.startsWith("dsd_")
    val isDsd: Boolean get() = codec.startsWith("dsd_")

    /** Bit depth as stored in the source, [Formats.FLOAT_DEPTH] for floating point, 0 if unknown. */
    val sourceDepth: Int
        get() = when {
            sampleFmt?.startsWith("flt") == true || sampleFmt?.startsWith("dbl") == true -> Formats.FLOAT_DEPTH
            bitsPerSample > 0 -> bitsPerSample
            sampleFmt?.startsWith("u8") == true || sampleFmt?.startsWith("s8") == true -> 8
            sampleFmt?.startsWith("s16") == true -> 16
            sampleFmt?.startsWith("s32") == true -> 32
            sampleFmt?.startsWith("s64") == true -> 32
            else -> 0
        }

    companion object {
        val LOSSLESS_CODECS = setOf(
            "flac", "alac", "ape", "wavpack", "tta", "mlp", "truehd", "tak", "shorten", "wmalossless", "als", "osq", "rka",
        )

        /** Parses the JSON from `ffprobe -show_streams -show_format`. Returns null if there is no audio stream. */
        fun fromProbeJson(json: String): AudioInfo? {
            val root = JSONObject(json)
            val streams = root.optJSONArray("streams") ?: return null
            var audio: JSONObject? = null
            var cover: JSONObject? = null
            for (i in 0 until streams.length()) {
                val s = streams.getJSONObject(i)
                when (s.optString("codec_type")) {
                    "audio" -> if (audio == null) audio = s
                    "video" -> {
                        val attached = s.optJSONObject("disposition")?.optInt("attached_pic", 0) == 1
                        if (attached && cover == null) cover = s
                    }
                }
            }
            val a = audio ?: return null
            val format = root.optJSONObject("format")
            val duration = a.optString("duration").toDoubleOrNull() ?: format?.optString("duration")?.toDoubleOrNull()
            val tags = mutableMapOf<String, String>()
            for (obj in listOfNotNull(format?.optJSONObject("tags"), a.optJSONObject("tags"))) {
                obj.keys().forEach { k -> tags[k.lowercase()] = obj.optString(k) }
            }
            val raw = a.optString("bits_per_raw_sample").toIntOrNull() ?: 0
            val bps = a.optInt("bits_per_sample", 0)
            return AudioInfo(
                streamIndex = a.optInt("index", 0),
                codec = a.optString("codec_name", "unknown"),
                sampleRate = a.optString("sample_rate").toIntOrNull() ?: 0,
                channels = a.optInt("channels", 0),
                bitRate = a.optString("bit_rate").toLongOrNull() ?: format?.optString("bit_rate")?.toLongOrNull(),
                sampleFmt = a.optString("sample_fmt").ifEmpty { null },
                bitsPerSample = if (raw > 0) raw else bps,
                durationSec = duration,
                coverStreamIndex = cover?.optInt("index"),
                coverCodec = cover?.optString("codec_name"),
                containerName = format?.optString("format_name"),
                tags = tags,
            )
        }
    }
}
