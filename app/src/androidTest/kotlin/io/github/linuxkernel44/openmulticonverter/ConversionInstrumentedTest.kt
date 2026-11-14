package io.github.linuxkernel44.openmulticonverter

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.linuxkernel44.openmulticonverter.core.AudioInfo
import io.github.linuxkernel44.openmulticonverter.core.Ffmpeg
import io.github.linuxkernel44.openmulticonverter.core.Formats
import io.github.linuxkernel44.openmulticonverter.core.InputHandle
import io.github.linuxkernel44.openmulticonverter.core.Mode
import io.github.linuxkernel44.openmulticonverter.core.OutputFormat
import io.github.linuxkernel44.openmulticonverter.core.Planner
import io.github.linuxkernel44.openmulticonverter.core.Settings
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** Runs the real bundled ffmpeg/ffprobe through the planner on a device. */
class ConversionInstrumentedTest {
    private lateinit var ctx: Context
    private lateinit var ffmpeg: Ffmpeg
    private lateinit var work: File

    @Before fun setUp() {
        val inst = InstrumentationRegistry.getInstrumentation()
        ctx = inst.targetContext
        ffmpeg = Ffmpeg(ctx)
        work = File(ctx.cacheDir, "itest").apply { deleteRecursively(); mkdirs() }
        for (n in listOf("src24.flac", "src16.flac", "tagged.flac", "vid.mp4")) {
            inst.context.assets.open(n).use { i -> File(work, n).outputStream().use { i.copyTo(it) } }
        }
    }

    private fun convert(src: String, target: OutputFormat, s: Settings = Settings()): Triple<File, AudioInfo, io.github.linuxkernel44.openmulticonverter.core.Plan> {
        val input = File(work, src)
        val info = ffmpeg.probe(input.path)
        val plan = Planner.plan(info, target, s)
        val out = File(work, "$src.${target.id}.${plan.outputFormat.extension}")
        var last = -1.0
        ffmpeg.convert(input.path, out, plan) { last = it }
        assertTrue("output empty", out.length() > 0)
        return Triple(out, ffmpeg.probe(out.path), plan)
    }

    /** Decodes to 32-bit PCM WAV without metadata so two files can be compared byte for byte. */
    private fun pcm(f: File, rate: Int? = null): ByteArray {
        val out = File(work, f.name + ".pcm.wav")
        val plan = Planner.plan(ffmpeg.probe(f.path), Formats.WAV, Settings(bitDepth = 32, sampleRate = rate))
        val args = plan.args + listOf("-fflags", "+bitexact", "-flags:a", "+bitexact")
        ffmpeg.convert(f.path, out, plan.copy(args = args)) {}
        return out.readBytes()
    }

    @Test fun everyTargetFormatEncodesFromLosslessSource() {
        for (fmt in Formats.all) {
            val (out, info, plan) = convert("src24.flac", fmt)
            assertTrue("${fmt.id} produced no audio", info.durationSec!! > 2.5)
            println("${fmt.id}: ${plan.mode} codec=${info.codec} rate=${info.sampleRate} fmt=${info.sampleFmt} bits=${info.bitsPerSample} size=${out.length()}")
        }
    }

    @Test fun flacToAlacToFlacIsBitExact24Bit() {
        val (alac, ai, plan) = convert("src24.flac", Formats.ALAC)
        assertEquals(Mode.LOSSLESS_EXACT, plan.mode)
        assertEquals("alac", ai.codec); assertEquals(96000, ai.sampleRate); assertEquals(24, ai.sourceDepth)
        assertArrayEquals(pcm(File(work, "src24.flac")), pcm(alac))
    }

    @Test fun flacToWavpackAndWavAreBitExact() {
        val ref = pcm(File(work, "src24.flac"))
        for (fmt in listOf(Formats.WAVPACK, Formats.WAV, Formats.AIFF)) {
            val (out, info, plan) = convert("src24.flac", fmt)
            assertEquals(fmt.id, Mode.LOSSLESS_EXACT, plan.mode)
            assertEquals(fmt.id, 24, info.sourceDepth)
            assertArrayEquals(fmt.id, ref, pcm(out))
        }
    }

    @Test fun sixteenBitStaysSixteenBit() {
        val ref = pcm(File(work, "src16.flac"))
        for (fmt in listOf(Formats.FLAC, Formats.ALAC, Formats.WAVPACK, Formats.WAV)) {
            val (out, info, _) = convert("src16.flac", fmt, if (fmt == Formats.FLAC) Settings(compressionLevel = 12) else Settings())
            assertEquals(fmt.id, 16, info.sourceDepth)
            assertArrayEquals(fmt.id, ref, pcm(out))
        }
    }

    @Test fun sameCodecIsStreamCopyAndIdentical() {
        val (out, _, plan) = convert("src16.flac", Formats.FLAC)
        assertEquals(Mode.COPY, plan.mode)
        assertArrayEquals(pcm(File(work, "src16.flac")), pcm(out))
    }

    @Test fun tagsAndCoverSurviveConversion() {
        for (fmt in listOf(Formats.MP3, Formats.ALAC, Formats.AAC, Formats.FLAC)) {
            val (_, info, _) = convert("tagged.flac", fmt)
            assertEquals(fmt.id, "Test Title", info.tags["title"])
            assertEquals(fmt.id, "Tester", info.tags["artist"])
            assertNotNull("${fmt.id} lost cover", info.coverStreamIndex)
        }
    }

    @Test fun lossyBitrateRespected() {
        val (_, info, _) = convert("src16.flac", Formats.MP3, Settings(bitrate = 128_000))
        assertEquals("mp3", info.codec)
        assertTrue("bitrate ${info.bitRate}", info.bitRate!! in 120_000..140_000)
    }

    @Test fun opusAndAmrAdjustRate() {
        assertEquals(48000, convert("src16.flac", Formats.OPUS).second.sampleRate)
        val amr = convert("src16.flac", Formats.AMR).second
        assertEquals(8000, amr.sampleRate); assertEquals(1, amr.channels)
    }

    @Test fun extractAudioFromVideo() {
        val (out, info, plan) = convert("vid.mp4", Formats.ORIGINAL)
        assertEquals(Mode.COPY, plan.mode); assertEquals("aac", info.codec); assertEquals("m4a", out.extension)
        val (_, flac, _) = convert("vid.mp4", Formats.FLAC)
        assertEquals("flac", flac.codec)
        assertEquals(null, flac.coverStreamIndex)
    }

    @Test fun fdSymlinkInputWorks() {
        val pfdHandle = InputHandle.open(ctx, android.net.Uri.fromFile(File(work, "src16.flac")), "src16.flac")
        pfdHandle.use {
            val info = ffmpeg.probe(it.path)
            assertEquals("flac", info.codec)
        }
    }
}
