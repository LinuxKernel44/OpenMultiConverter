package io.github.linuxkernel44.openmulticonverter.core

/** User-selectable encoding options. `null` means "keep source / encoder default". */
data class Settings(
    val sampleRate: Int? = null,
    val channels: Int? = null,
    /** Target PCM depth (16/24/32) or [Formats.FLOAT_DEPTH]. */
    val bitDepth: Int? = null,
    /** Target bitrate in bits/second for lossy formats. */
    val bitrate: Int? = null,
    val compressionLevel: Int = 8,
) {
    val isDefault: Boolean get() = sampleRate == null && channels == null && bitDepth == null && bitrate == null
}

enum class Mode(val label: String) {
    /** Audio stream copied untouched. */
    COPY("Stream copy (no re-encoding)"),

    /** Decoded and re-encoded losslessly; samples are identical. */
    LOSSLESS_EXACT("Lossless (bit-exact)"),

    /** Lossless target but the source is lossy or a representation change is needed; no *additional* loss. */
    LOSSLESS_LOSSY_SOURCE("Lossless container (source is already lossy)"),

    /** Information is discarded. */
    LOSSY("Lossy"),
}

enum class Warning(val text: String) {
    LOSSY_TARGET("The output format is lossy: some audio information is discarded."),
    GENERATION_LOSS("The source is already lossy: re-encoding to another lossy format degrades quality further."),
    LOSSY_SOURCE_TO_LOSSLESS("The source is lossy: converting to a lossless format keeps its quality but cannot restore what was lost, and files get larger."),
    DOWNSAMPLE("Sample rate is changed: resampling is not lossless."),
    BIT_DEPTH_REDUCED("Bit depth is reduced: this discards information."),
    FLOAT_TO_INT("Floating-point samples are converted to integers: not bit-exact."),
    DOWNMIX("Channels are reduced (down-mixed)."),
    DSD_TO_PCM("DSD audio must be converted to PCM, which is not a bit-exact operation."),
    RATE_ADJUSTED("The sample rate was adjusted to one supported by the target format."),
    CHANNELS_ADJUSTED("The channel count was reduced to what the target format supports."),
    NO_COVER("Embedded cover art cannot be stored in this format and will be dropped."),
}

data class Plan(
    val mode: Mode,
    val warnings: List<Warning>,
    val outputFormat: OutputFormat,
    /** ffmpeg arguments that go between `-i <input>` and the output path. */
    val args: List<String>,
)

object Planner {

    fun plan(info: AudioInfo, target: OutputFormat, settings: Settings): Plan {
        val format = if (target.isOriginal) Formats.containerFor(info.codec) else target
        val warnings = linkedSetOf<Warning>()

        val coverOk = info.coverStreamIndex != null
        val writeCover = coverOk && format.supportsCover
        if (coverOk && !format.supportsCover) warnings += Warning.NO_COVER

        val args = mutableListOf("-map", "0:${info.streamIndex}")
        if (writeCover) {
            args += listOf("-map", "0:${info.coverStreamIndex}")
        }
        args += listOf("-map_metadata", "0", "-map_chapters", "-1")

        fun cover() {
            if (!writeCover) return
            val keep = info.coverCodec == "mjpeg" || info.coverCodec == "png"
            args += listOf("-c:v", if (keep) "copy" else "mjpeg", "-disposition:v:0", "attached_pic")
        }

        // ---- 1. Stream copy -------------------------------------------------------------------
        val copyable = target.isOriginal || (info.codec in format.copyableCodecs && settings.isDefault)
        if (copyable && !info.isDsd) {
            args += listOf("-c:a", "copy")
            cover()
            args += muxerArgs(format)
            return Plan(Mode.COPY, warnings.toList(), format, args)
        }

        // ---- 2. Sample rate / channels -------------------------------------------------------
        var rate = settings.sampleRate
        var channels = settings.channels
        val srcRate = info.sampleRate
        if (info.isDsd && rate == null) rate = 88_200
        format.sampleRates?.let { allowed ->
            val wanted = rate ?: srcRate
            if (wanted !in allowed) {
                rate = allowed.firstOrNull { it >= wanted } ?: allowed.max()
                warnings += Warning.RATE_ADJUSTED
            }
        }
        if (rate != null && rate != srcRate) warnings += Warning.DOWNSAMPLE
        val effChannels = channels ?: info.channels
        if (effChannels > format.maxChannels) {
            channels = format.maxChannels
            warnings += Warning.CHANNELS_ADJUSTED
        }
        if (channels != null && channels < info.channels) warnings += Warning.DOWNMIX
        if (rate != null && rate != srcRate) args += listOf("-ar", rate.toString())
        if (channels != null && channels != info.channels) args += listOf("-ac", channels.toString())

        // ---- 3. Encode ------------------------------------------------------------------------
        val mode: Mode
        if (format.lossless) {
            val src = info.sourceDepth
            val wanted = settings.bitDepth ?: if (info.isLosslessSource && !info.isDsd) src else 24
            var depth = wanted
            if (depth == Formats.FLOAT_DEPTH && Formats.FLOAT_DEPTH !in format.bitDepths) depth = 24
            if (depth == 0) depth = 24
            if (depth !in format.bitDepths) {
                depth = format.bitDepths.filter { it != Formats.FLOAT_DEPTH }.let { l -> l.firstOrNull { it >= depth } ?: l.max() }
            }
            if (info.isLosslessSource && !info.isDsd) {
                if (src == Formats.FLOAT_DEPTH && depth != Formats.FLOAT_DEPTH) warnings += Warning.FLOAT_TO_INT
                else if (src != Formats.FLOAT_DEPTH && depth != Formats.FLOAT_DEPTH && src > depth) warnings += Warning.BIT_DEPTH_REDUCED
            }
            if (info.isDsd) warnings += Warning.DSD_TO_PCM
            args += losslessEncoderArgs(format, depth, settings)
            mode = when {
                !info.isLosslessSource -> {
                    warnings += Warning.LOSSY_SOURCE_TO_LOSSLESS
                    Mode.LOSSLESS_LOSSY_SOURCE
                }
                warnings.any { it in LOSSY_WARNINGS } -> Mode.LOSSY
                info.isDsd -> Mode.LOSSLESS_LOSSY_SOURCE
                else -> Mode.LOSSLESS_EXACT
            }
        } else {
            warnings += if (info.isLosslessSource) Warning.LOSSY_TARGET else Warning.GENERATION_LOSS
            args += lossyEncoderArgs(format, settings)
            mode = Mode.LOSSY
        }
        cover()
        args += muxerArgs(format)
        return Plan(mode, warnings.toList(), format, args)
    }

    private val LOSSY_WARNINGS = setOf(
        Warning.DOWNSAMPLE, Warning.BIT_DEPTH_REDUCED, Warning.FLOAT_TO_INT, Warning.DOWNMIX,
        Warning.RATE_ADJUSTED, Warning.CHANNELS_ADJUSTED,
    )

    private fun muxerArgs(f: OutputFormat): List<String> = buildList {
        add("-f"); add(f.muxer)
        if (f.id == "mp3") { add("-id3v2_version"); add("3") }
        if (f.muxer == "ipod") { add("-movflags"); add("+faststart") }
    }

    private fun losslessEncoderArgs(f: OutputFormat, depth: Int, s: Settings): List<String> = when (f.id) {
        "flac" -> listOf(
            "-c:a", "flac", "-compression_level", s.compressionLevel.coerceIn(0, 12).toString(),
            "-sample_fmt", if (depth <= 16) "s16" else "s32",
        )
        "alac" -> listOf("-c:a", "alac", "-sample_fmt", if (depth <= 16) "s16p" else "s32p") +
            (if (depth > 16) listOf("-bits_per_raw_sample", "24") else emptyList())
        "wavpack" -> listOf("-c:a", "wavpack", "-sample_fmt", if (depth <= 16) "s16p" else "s32p") +
            (if (depth in 17..24) listOf("-bits_per_raw_sample", "24") else emptyList())
        "wav" -> listOf("-c:a", when (depth) {
            8 -> "pcm_u8"; 16 -> "pcm_s16le"; 24 -> "pcm_s24le"; 32 -> "pcm_s32le"; else -> "pcm_f32le"
        })
        "aiff" -> listOf("-c:a", when (depth) { 16 -> "pcm_s16be"; 24 -> "pcm_s24be"; else -> "pcm_s32be" })
        else -> error("Unsupported lossless format ${f.id}")
    }

    private fun lossyEncoderArgs(f: OutputFormat, s: Settings): List<String> {
        val br = s.bitrate ?: f.defaultBitrate
        val bitrateArgs = if (br != null) listOf("-b:a", br.toString()) else emptyList()
        return when (f.id) {
            "mp3" -> listOf("-c:a", "libmp3lame") + (if (br == null) listOf("-q:a", "0") else bitrateArgs)
            "aac" -> listOf("-c:a", "aac") + bitrateArgs
            "vorbis" -> listOf("-c:a", "libvorbis") + (if (br == null) listOf("-q:a", "7") else bitrateArgs)
            "opus" -> listOf("-c:a", "libopus", "-vbr", "on") + bitrateArgs
            "wma" -> listOf("-c:a", "wmav2") + bitrateArgs
            "ac3" -> listOf("-c:a", "ac3") + bitrateArgs
            "mp2" -> listOf("-c:a", "mp2") + bitrateArgs
            "amr" -> listOf("-c:a", "libopencore_amrnb") + bitrateArgs
            else -> error("Unsupported lossy format ${f.id}")
        }
    }
}
