# OpenMultiConverter

Open-source, **fully offline** Android app that converts files from one format to another, **losslessly whenever possible**.

> **v0.1.0 scope: audio.** More media types will follow in later releases.

## Features (v0.1.0)

- **Inputs:** FLAC, WAV, ALAC, AIFF, WavPack, APE, TTA, DSD (DSF/DFF), MP3, AAC/M4A, OGG Vorbis, Opus, WMA, AMR, AC-3, MP2, MKA… and the audio track of video files (MP4, MKV, WebM, MOV…).
- **Outputs:**
  - Lossless: FLAC, WAV, ALAC, AIFF, WavPack
  - Lossy: MP3, AAC (M4A), OGG Vorbis, Opus, WMA, AC-3, MP2, AMR-NB
  - **Original (no re-encode):** copies the audio stream untouched into the matching container (ideal to extract audio from videos).
- **Smart, honest conversion:**
  - same codec → stream copy, nothing is re-encoded;
  - lossless → lossless → bit-exact, sample rate and bit depth preserved;
  - anything that loses information (lossy target, lossy → lossy, downsampling, bit-depth reduction, down-mixing) is flagged with a clear warning *before* you convert.
- Batch conversion (multiple files or a whole folder), progress notification with cancel, runs in a foreground service.
- Keeps tags and embedded cover art whenever the output container supports it.
- Advanced settings: sample rate, channels, bit depth, bitrate, FLAC compression level.
- Material 3 UI with dynamic colors, light/dark theme.
- **No network permission, no telemetry.**

## Install

Download the signed APK from the [Releases](https://github.com/LinuxKernel44/OpenMultiConverter/releases) page (Android 8.0+). Verify it against the published SHA-256.

## How it works

FFmpeg (with LAME, Opus, Vorbis and OpenCORE-AMR) is cross-compiled with the Android NDK by `native/build-ffmpeg.sh` and shipped as `ffmpeg` / `ffprobe` executables inside the APK (named `libffmpeg.so` / `libffprobe.so` so Android extracts them to the native library directory). The Kotlin / Jetpack Compose app drives them as child processes: input files are handed over through file descriptors (no copy), output is written to a temporary file and then moved into the folder you chose through the Storage Access Framework.

The core decision logic lives in [`Planner.kt`](app/src/main/kotlin/io/github/linuxkernel44/openmulticonverter/core/Planner.kt) and is unit-tested.

## Building

Requirements: JDK 17, Android SDK (platform 36, build-tools), Android NDK 28.2 (`28.2.13676358`), `nasm`, `make`, `curl`, `pkg-config`.

```sh
native/build-ffmpeg.sh          # downloads & builds FFmpeg + codecs for arm64-v8a, armeabi-v7a, x86_64
./gradlew testDebugUnitTest     # unit tests
./gradlew assembleDebug         # debug APK
scripts/release.sh              # signed release APK + GitHub release (maintainer only, see script)
```

## License

GPLv3 — see [LICENSE](LICENSE). The bundled FFmpeg is built with `--enable-gpl --enable-version3`; third-party codecs: LAME (LGPL), Opus, libogg/libvorbis (BSD), OpenCORE-AMR (Apache 2.0).
