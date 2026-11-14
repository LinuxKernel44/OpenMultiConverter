package io.github.linuxkernel44.openmulticonverter.core

/** An output format the app can produce. */
data class OutputFormat(
    val id: String,
    val label: String,
    val description: String,
    val extension: String,
    val mime: String,
    /** ffmpeg muxer name (`-f`). */
    val muxer: String,
    val lossless: Boolean,
    /** ffprobe codec names that can be stream-copied into this format as-is. */
    val copyableCodecs: Set<String>,
    /** Whether embedded cover art can be written. */
    val supportsCover: Boolean,
    /** Selectable bitrates in bits/second (lossy formats). Empty = no bitrate setting. */
    val bitrates: List<Int> = emptyList(),
    /** Default bitrate in bps; null = encoder's default quality (VBR) mode. */
    val defaultBitrate: Int? = null,
    /** Sample rates the encoder accepts; null = any. */
    val sampleRates: List<Int>? = null,
    val maxChannels: Int = 8,
    /** PCM bit depths the format can store, ascending. Empty for lossy formats. */
    val bitDepths: List<Int> = emptyList(),
    val hasCompressionLevel: Boolean = false,
    /** Special "stream copy, keep original codec" pseudo-format. */
    val isOriginal: Boolean = false,
)

object Formats {
    val FLAC = OutputFormat(
        "flac", "FLAC", "Free Lossless Audio Codec", "flac", "audio/flac", "flac", true,
        setOf("flac"), supportsCover = true, bitDepths = listOf(16, 24), hasCompressionLevel = true,
    )
    val WAV = OutputFormat(
        "wav", "WAV", "Uncompressed PCM", "wav", "audio/x-wav", "wav", true,
        setOf("pcm_u8", "pcm_s16le", "pcm_s24le", "pcm_s32le", "pcm_f32le", "pcm_f64le"),
        supportsCover = false, bitDepths = listOf(8, 16, 24, 32, FLOAT_DEPTH),
    )
    val ALAC = OutputFormat(
        "alac", "ALAC", "Apple Lossless (.m4a)", "m4a", "audio/mp4", "ipod", true,
        setOf("alac"), supportsCover = true, bitDepths = listOf(16, 24),
    )
    val AIFF = OutputFormat(
        "aiff", "AIFF", "Uncompressed PCM (big-endian)", "aiff", "audio/x-aiff", "aiff", true,
        setOf("pcm_s16be", "pcm_s24be", "pcm_s32be"), supportsCover = false, bitDepths = listOf(16, 24, 32),
    )
    val WAVPACK = OutputFormat(
        "wavpack", "WavPack", "WavPack lossless", "wv", "audio/x-wavpack", "wv", true,
        setOf("wavpack"), supportsCover = false, bitDepths = listOf(16, 24, 32),
    )
    val MP3 = OutputFormat(
        "mp3", "MP3", "MPEG Layer III (LAME)", "mp3", "audio/mpeg", "mp3", false,
        setOf("mp3"), supportsCover = true,
        bitrates = listOf(320, 256, 224, 192, 160, 128, 96, 64).map { it * 1000 }, defaultBitrate = null,
        sampleRates = listOf(8000, 11025, 12000, 16000, 22050, 24000, 32000, 44100, 48000), maxChannels = 2,
    )
    val AAC = OutputFormat(
        "aac", "AAC", "Advanced Audio Coding (.m4a)", "m4a", "audio/mp4", "ipod", false,
        setOf("aac"), supportsCover = true,
        bitrates = listOf(320, 256, 192, 160, 128, 96, 64).map { it * 1000 }, defaultBitrate = 256_000,
    )
    val VORBIS = OutputFormat(
        "vorbis", "OGG Vorbis", "Ogg Vorbis (.ogg)", "ogg", "audio/ogg", "ogg", false,
        setOf("vorbis"), supportsCover = false,
        bitrates = listOf(500, 320, 256, 192, 160, 128, 96, 64).map { it * 1000 }, defaultBitrate = null,
    )
    val OPUS = OutputFormat(
        "opus", "Opus", "Opus (.opus)", "opus", "audio/ogg", "opus", false,
        setOf("opus"), supportsCover = false,
        bitrates = listOf(256, 192, 160, 128, 96, 64, 48, 32).map { it * 1000 }, defaultBitrate = 192_000,
        sampleRates = listOf(8000, 12000, 16000, 24000, 48000),
    )
    val WMA = OutputFormat(
        "wma", "WMA", "Windows Media Audio v2", "wma", "audio/x-ms-wma", "asf", false,
        setOf("wmav2", "wmav1"), supportsCover = false,
        bitrates = listOf(320, 256, 192, 160, 128, 96, 64).map { it * 1000 }, defaultBitrate = 192_000, maxChannels = 2,
    )
    val AC3 = OutputFormat(
        "ac3", "AC-3", "Dolby Digital", "ac3", "audio/ac3", "ac3", false,
        setOf("ac3"), supportsCover = false,
        bitrates = listOf(640, 448, 384, 320, 256, 192, 128, 96).map { it * 1000 }, defaultBitrate = 448_000,
        sampleRates = listOf(32000, 44100, 48000), maxChannels = 6,
    )
    val MP2 = OutputFormat(
        "mp2", "MP2", "MPEG Layer II", "mp2", "audio/mpeg", "mp2", false,
        setOf("mp2"), supportsCover = false,
        bitrates = listOf(384, 320, 256, 224, 192, 160, 128, 96, 64).map { it * 1000 }, defaultBitrate = 256_000,
        sampleRates = listOf(16000, 22050, 24000, 32000, 44100, 48000), maxChannels = 2,
    )
    val AMR = OutputFormat(
        "amr", "AMR-NB", "Adaptive Multi-Rate (voice, 8 kHz mono)", "amr", "audio/amr", "amr", false,
        setOf("amr_nb"), supportsCover = false,
        bitrates = listOf(12200, 10200, 7950, 7400, 6700, 5900, 5150, 4750), defaultBitrate = 12_200,
        sampleRates = listOf(8000), maxChannels = 1,
    )
    val ORIGINAL = OutputFormat(
        "original", "Original (no re-encode)", "Copy the audio stream untouched into the matching container",
        "", "application/octet-stream", "", true, emptySet(), supportsCover = true, isOriginal = true,
    )
    val MKA = OutputFormat(
        "mka", "Matroska Audio", "Matroska container (.mka)", "mka", "audio/x-matroska", "matroska", true,
        emptySet(), supportsCover = false,
    )
    val EAC3 = OutputFormat(
        "eac3", "E-AC-3", "Dolby Digital Plus", "eac3", "audio/eac3", "eac3", true, setOf("eac3"), supportsCover = false,
    )

    val lossless = listOf(FLAC, WAV, ALAC, AIFF, WAVPACK)
    val lossy = listOf(MP3, AAC, VORBIS, OPUS, WMA, AC3, MP2, AMR)
    val all = listOf(ORIGINAL) + lossless + lossy

    /** Container used when stream-copying a source codec ("original" mode). */
    fun containerFor(codec: String): OutputFormat = when {
        codec == "mp3" -> MP3
        codec == "aac" -> AAC
        codec == "alac" -> ALAC
        codec == "opus" -> OPUS
        codec == "vorbis" -> VORBIS
        codec == "flac" -> FLAC
        codec in WAV.copyableCodecs -> WAV
        codec in AIFF.copyableCodecs -> AIFF
        codec == "ac3" -> AC3
        codec == "eac3" -> EAC3
        codec == "mp2" -> MP2
        codec == "wavpack" -> WAVPACK
        codec == "wmav2" || codec == "wmav1" -> WMA
        codec == "amr_nb" -> AMR
        else -> MKA
    }

    const val FLOAT_DEPTH = 0
}
