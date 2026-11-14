package io.github.linuxkernel44.openmulticonverter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannerTest {
    private fun info(
        codec: String, rate: Int = 44100, ch: Int = 2, fmt: String? = "s16", bits: Int = 0,
        cover: Int? = null, coverCodec: String? = null,
    ) = AudioInfo(0, codec, rate, ch, null, fmt, bits, 10.0, cover, coverCodec, null, emptyMap())

    private val flac16 = info("flac", fmt = "s16", bits = 16)
    private val flac24 = info("flac", rate = 96000, fmt = "s32", bits = 24)
    private val mp3 = info("mp3", fmt = "fltp")

    @Test fun sameCodecIsStreamCopy() {
        val p = Planner.plan(flac16, Formats.FLAC, Settings())
        assertEquals(Mode.COPY, p.mode)
        assertTrue(p.args.containsAll(listOf("-c:a", "copy")))
        assertTrue(p.warnings.isEmpty())
    }

    @Test fun sameCodecWithChangedSettingsReencodes() {
        val p = Planner.plan(mp3, Formats.MP3, Settings(bitrate = 128_000))
        assertEquals(Mode.LOSSY, p.mode)
        assertTrue(Warning.GENERATION_LOSS in p.warnings)
    }

    @Test fun losslessToLosslessIsBitExact() {
        val p = Planner.plan(flac24, Formats.ALAC, Settings())
        assertEquals(Mode.LOSSLESS_EXACT, p.mode)
        assertTrue(p.warnings.isEmpty())
        assertTrue(p.args.containsAll(listOf("-sample_fmt", "s32p", "-bits_per_raw_sample", "24")))
        assertFalse(p.args.contains("-ar"))
    }

    @Test fun flac24ToWavKeeps24Bit() {
        val p = Planner.plan(flac24, Formats.WAV, Settings())
        assertEquals(Mode.LOSSLESS_EXACT, p.mode)
        assertTrue(p.args.containsAll(listOf("-c:a", "pcm_s24le")))
    }

    @Test fun pcm16ToFlacUses16Bit() {
        val p = Planner.plan(info("pcm_s16le"), Formats.FLAC, Settings())
        assertEquals(Mode.LOSSLESS_EXACT, p.mode)
        assertTrue(p.args.containsAll(listOf("-sample_fmt", "s16")))
    }

    @Test fun floatToFlacIsNotExact() {
        val p = Planner.plan(info("pcm_f32le", fmt = "flt"), Formats.FLAC, Settings())
        assertEquals(Mode.LOSSY, p.mode)
        assertTrue(Warning.FLOAT_TO_INT in p.warnings)
    }

    @Test fun floatToWavFloatIsExact() {
        // pcm_f64le source: not copyable with an explicit float32 request, but float -> float stays exact.
        val p = Planner.plan(info("flac", bits = 24, fmt = "s32"), Formats.WAV, Settings(bitDepth = 24))
        assertEquals(Mode.LOSSLESS_EXACT, p.mode)
        assertTrue(p.args.containsAll(listOf("-c:a", "pcm_s24le")))
        val c = Planner.plan(info("pcm_f32le", fmt = "flt"), Formats.WAV, Settings())
        assertEquals(Mode.COPY, c.mode)
    }

    @Test fun lossyToLosslessWarns() {
        val p = Planner.plan(mp3, Formats.FLAC, Settings())
        assertEquals(Mode.LOSSLESS_LOSSY_SOURCE, p.mode)
        assertTrue(Warning.LOSSY_SOURCE_TO_LOSSLESS in p.warnings)
    }

    @Test fun losslessToLossyWarns() {
        val p = Planner.plan(flac16, Formats.MP3, Settings())
        assertEquals(Mode.LOSSY, p.mode)
        assertTrue(Warning.LOSSY_TARGET in p.warnings)
        assertTrue(p.args.containsAll(listOf("-c:a", "libmp3lame", "-q:a", "0")))
    }

    @Test fun reducingBitDepthIsFlagged() {
        val p = Planner.plan(flac24, Formats.FLAC, Settings(bitDepth = 16))
        assertEquals(Mode.LOSSY, p.mode)
        assertTrue(Warning.BIT_DEPTH_REDUCED in p.warnings)
    }

    @Test fun resamplingIsFlagged() {
        val p = Planner.plan(flac16, Formats.FLAC, Settings(sampleRate = 22050))
        assertEquals(Mode.LOSSY, p.mode)
        assertTrue(p.args.containsAll(listOf("-ar", "22050")))
    }

    @Test fun opusForcesSupportedRate() {
        val p = Planner.plan(flac16, Formats.OPUS, Settings())
        assertTrue(p.args.containsAll(listOf("-ar", "48000")))
        assertTrue(Warning.RATE_ADJUSTED in p.warnings)
    }

    @Test fun amrIsMonoEightKhz() {
        val p = Planner.plan(flac16, Formats.AMR, Settings())
        assertTrue(p.args.containsAll(listOf("-ar", "8000", "-ac", "1")))
    }

    @Test fun surroundDownmixedForMp3() {
        val p = Planner.plan(info("flac", ch = 6, bits = 16), Formats.MP3, Settings())
        assertTrue(Warning.CHANNELS_ADJUSTED in p.warnings)
        assertTrue(p.args.containsAll(listOf("-ac", "2")))
    }

    @Test fun dsdGoesToPcmWithWarning() {
        val p = Planner.plan(info("dsd_lsbf_planar", rate = 352800, fmt = "fltp"), Formats.FLAC, Settings())
        assertTrue(Warning.DSD_TO_PCM in p.warnings)
        assertTrue(p.args.containsAll(listOf("-ar", "88200")))
        assertFalse(p.mode == Mode.COPY)
    }

    @Test fun originalCopiesIntoMatchingContainer() {
        val p = Planner.plan(info("opus", fmt = "fltp"), Formats.ORIGINAL, Settings())
        assertEquals(Mode.COPY, p.mode)
        assertEquals("opus", p.outputFormat.extension)
        val q = Planner.plan(info("dts"), Formats.ORIGINAL, Settings())
        assertEquals("mka", q.outputFormat.extension)
    }

    @Test fun coverIsKeptWhenSupportedAndDroppedOtherwise() {
        val src = info("flac", bits = 16, cover = 1, coverCodec = "mjpeg")
        val mp3 = Planner.plan(src, Formats.MP3, Settings())
        assertTrue(mp3.args.containsAll(listOf("-map", "0:1", "-c:v", "copy")))
        val wav = Planner.plan(src, Formats.WAV, Settings())
        assertFalse(wav.args.contains("0:1"))
        assertTrue(Warning.NO_COVER in wav.warnings)
        val webp = Planner.plan(info("flac", bits = 16, cover = 1, coverCodec = "webp"), Formats.MP3, Settings())
        assertTrue(webp.args.containsAll(listOf("-c:v", "mjpeg")))
    }

    @Test fun parsesProbeJson() {
        val json = """{"streams":[{"index":0,"codec_name":"mjpeg","codec_type":"video","disposition":{"attached_pic":1}},
            {"index":1,"codec_name":"flac","codec_type":"audio","sample_rate":"96000","channels":2,"sample_fmt":"s32",
             "bits_per_raw_sample":"24","duration":"12.5"}],"format":{"format_name":"flac","tags":{"TITLE":"x"}}}"""
        val i = AudioInfo.fromProbeJson(json)!!
        assertEquals(1, i.streamIndex); assertEquals(0, i.coverStreamIndex)
        assertEquals(24, i.sourceDepth); assertEquals(96000, i.sampleRate); assertEquals("x", i.tags["title"])
    }
}
