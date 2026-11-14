#!/usr/bin/env bash
# Cross-compiles ffmpeg + ffprobe (as PIE executables) for Android, with
# LAME, Opus, Vorbis/Ogg and OpenCORE-AMR. Output: app/src/main/jniLibs/<abi>/{libffmpeg.so,libffprobe.so}
# (executables named lib*.so so Android extracts them into nativeLibraryDir)
# Usage: native/build-ffmpeg.sh [abi...]   (default: arm64-v8a armeabi-v7a x86_64)
set -euo pipefail
cd "$(dirname "$0")"
ROOT="$PWD"
NDK="${ANDROID_NDK_HOME:-${ANDROID_HOME:-$HOME/Android/Sdk}/ndk/28.2.13676358}"
API=26
FFMPEG_V=9.0.2; LAME_V=3.100; OPUS_V=1.5.2; OGG_V=1.3.5; VORBIS_V=1.3.7; AMR_V=0.1.6
JOBS=$(nproc)
SRC="$ROOT/build/src"; mkdir -p "$SRC" out
TC="$NDK/toolchains/llvm/prebuilt/linux-x86_64"

fetch() { # name url archive
  [ -d "$SRC/$1" ] && return
  [ -f "$SRC/$3" ] || curl -fL --retry 3 -o "$SRC/$3" "$2"
  mkdir -p "$SRC/$1" && tar -xf "$SRC/$3" -C "$SRC/$1" --strip-components=1
}
fetch ffmpeg "https://ffmpeg.org/releases/ffmpeg-$FFMPEG_V.tar.xz" ffmpeg.tar.xz
fetch lame "https://downloads.sourceforge.net/project/lame/lame/$LAME_V/lame-$LAME_V.tar.gz" lame.tar.gz
fetch opus "https://downloads.xiph.org/releases/opus/opus-$OPUS_V.tar.gz" opus.tar.gz
fetch ogg "https://downloads.xiph.org/releases/ogg/libogg-$OGG_V.tar.xz" ogg.tar.xz
fetch vorbis "https://downloads.xiph.org/releases/vorbis/libvorbis-$VORBIS_V.tar.xz" vorbis.tar.xz
fetch amr "https://downloads.sourceforge.net/project/opencore-amr/opencore-amr/opencore-amr-$AMR_V.tar.gz" amr.tar.gz

DECODERS="8svx_exp,8svx_fib,aac,aac_fixed,aac_latm,ac3,ac3_fixed,acelp.kelvin,adpcm_4xm,adpcm_adx,adpcm_afc,adpcm_agm,adpcm_aica,adpcm_argo,adpcm_circus,adpcm_ct,adpcm_dtk,adpcm_ea,adpcm_ea_maxis_xa,adpcm_ea_r1,adpcm_ea_r2,adpcm_ea_r3,adpcm_ea_xas,g722,g726,g726le,adpcm_ima_acorn,adpcm_ima_alp,adpcm_ima_amv,adpcm_ima_apc,adpcm_ima_apm,adpcm_ima_cunning,adpcm_ima_dat4,adpcm_ima_dk3,adpcm_ima_dk4,adpcm_ima_ea_eacs,adpcm_ima_ea_sead,adpcm_ima_escape,adpcm_ima_hvqm2,adpcm_ima_hvqm4,adpcm_ima_iss,adpcm_ima_magix,adpcm_ima_moflex,adpcm_ima_mtf,adpcm_ima_oki,adpcm_ima_pda,adpcm_ima_qt,adpcm_ima_rad,adpcm_ima_smjpeg,adpcm_ima_ssi,adpcm_ima_wav,adpcm_ima_ws,adpcm_ima_xbox,adpcm_ms,adpcm_mtaf,adpcm_n64,adpcm_psx,adpcm_psxc,adpcm_sanyo,adpcm_sbpro_2,adpcm_sbpro_3,adpcm_sbpro_4,adpcm_swf,adpcm_thp,adpcm_thp_le,adpcm_vima,adpcm_xa,adpcm_xmd,adpcm_yamaha,adpcm_zork,ahx,alac,amrnb,amrwb,anull,apac,ape,aptx,aptx_hd,atrac1,atrac3,atrac3al,atrac3plus,atrac3plusal,atrac9,on2avc,binkaudio_dct,binkaudio_rdft,bmv_audio,bonk,cbd2_dpcm,comfortnoise,cook,derf_dpcm,dfpwm,dolby_e,dsd_lsbf,dsd_lsbf_planar,dsd_msbf,dsd_msbf_planar,dsicinaudio,dss_sp,dst,dca,dvaudio,eac3,evrc,fastaudio,flac,ftr,g723_1,g728,g729,gremlin_dpcm,gsm,gsm_ms,hca,hcom,iac,ilbc,imc,interplay_dpcm,interplayacm,mace3,mace6,metasound,misc4,mlp,mp1,mp1float,mp2,mp2float,mp3float,mp3,mp3adufloat,mp3adu,mp3on4float,mp3on4,als,msnsiren,mpc7,mpc8,nellymoser,opus,osq,paf_audio,pcm_alaw,pcm_bluray,pcm_dvd,pcm_f16le,pcm_f24le,pcm_f32be,pcm_f32le,pcm_f64be,pcm_f64le,pcm_lxf,pcm_mulaw,pcm_s16be,pcm_s16be_planar,pcm_s16le,pcm_s16le_planar,pcm_s24be,pcm_s24daud,pcm_s24le,pcm_s24le_planar,pcm_s32be,pcm_s32le,pcm_s32le_planar,pcm_s64be,pcm_s64le,pcm_s8,pcm_s8_planar,pcm_sga,pcm_u16be,pcm_u16le,pcm_u24be,pcm_u24le,pcm_u32be,pcm_u32le,pcm_u8,pcm_vidc,qcelp,qdm2,qdmc,qoa,real_144,real_288,ralf,rka,roq_dpcm,s302m,sbc,sdx2_dpcm,shorten,sipr,siren,smackaud,sol_dpcm,speex,tak,truehd,truespeech,tta,twinvq,vmdaudio,vorbis,wady_dpcm,wavarc,wavesynth,wavpack,ws_snd1,wmalossless,wmapro,wmav1,wmav2,wmavoice,xan_dpcm,xma1,xma2,mjpeg,png,bmp,webp"
ENCODERS="aac,ac3,eac3,alac,flac,wavpack,wmav2,wmav1,mp2,libmp3lame,libopus,libvorbis,libopencore_amrnb,mjpeg,png,pcm_s16le,pcm_s24le,pcm_s32le,pcm_u8,pcm_f32le,pcm_f64le,pcm_s16be,pcm_s24be,pcm_s32be,pcm_alaw,pcm_mulaw,adpcm_ima_wav,adpcm_ms,tta"
MUXERS="mp3,ipod,mp4,adts,ogg,opus,flac,wav,aiff,matroska,webm,mka,wv,ac3,eac3,mp2,amr,asf,asf_stream,tta,caf,w64,mov,image2,null,pcm_s16le,pcm_s16be,pcm_f32le,s16le,s24le,s32le,f32le"

build_abi() {
  local abi=$1 triple cpu arch extra=""
  case $abi in
    arm64-v8a)   triple=aarch64-linux-android;   cpu=armv8-a; arch=aarch64 ;;
    armeabi-v7a) triple=armv7a-linux-androideabi; cpu=armv7-a; arch=arm ;;
    x86_64)      triple=x86_64-linux-android;    cpu=x86-64;  arch=x86_64 ;;
    *) echo "bad abi $abi"; exit 1 ;;
  esac
  local PFX="$ROOT/build/$abi/prefix" B="$ROOT/build/$abi"; mkdir -p "$PFX" "$B"
  export CC="$TC/bin/${triple}$API-clang" CXX="$TC/bin/${triple}$API-clang++"
  export AR="$TC/bin/llvm-ar" RANLIB="$TC/bin/llvm-ranlib" STRIP="$TC/bin/llvm-strip" NM="$TC/bin/llvm-nm"
  export CFLAGS="-Os -fPIC -ffunction-sections -fdata-sections" LDFLAGS="-Wl,-z,max-page-size=16384"
  local host=$triple; [ $abi = armeabi-v7a ] && host=arm-linux-androideabi
  export PKG_CONFIG_LIBDIR="$PFX/lib/pkgconfig" PKG_CONFIG_PATH="$PFX/lib/pkgconfig"
  conf() { # name [args]
    local n=$1; shift
    if [ ! -f "$PFX/.$n.done" ]; then
      rm -rf "$B/$n"; mkdir -p "$B/$n"; ( cd "$B/$n"
        "$SRC/$n/configure" --host=$host --prefix="$PFX" --disable-shared --enable-static --with-pic "$@" >/dev/null
        make -j$JOBS >/dev/null && make install >/dev/null ); touch "$PFX/.$n.done"
    fi; }
  conf ogg
  conf vorbis --with-ogg="$PFX" --disable-examples
  conf opus --disable-doc --disable-extra-programs --disable-intrinsics --disable-rtcd
  conf amr --disable-compile-tools 2>/dev/null || true
  conf lame --disable-frontend --disable-decoder
  rm -rf "$B/ffmpeg"; mkdir -p "$B/ffmpeg"; cd "$B/ffmpeg"
  "$SRC/ffmpeg/configure" --prefix="$B/ffinstall" --enable-cross-compile --target-os=android \
    --arch=$arch --cpu=$cpu --cc="$CC" --cxx="$CXX" --ar="$AR" --ranlib="$RANLIB" --strip="$STRIP" --nm="$NM" \
    --pkg-config=pkg-config --pkg-config-flags=--static --enable-pic --enable-gpl --enable-version3 \
    --disable-shared --enable-static --disable-doc --disable-ffplay --disable-network --disable-autodetect \
    --disable-avdevice --disable-debug --disable-filters --enable-filter=aresample,aformat,anull,atrim,asetpts,volume,pan,channelmap,channelsplit,aresample,scale,format,null,nullsink,anullsink,afade,loudnorm,dynaudnorm,adelay,apad,atempo --disable-protocols --enable-protocol=file,pipe --enable-small --disable-symver --disable-hwaccels --disable-vulkan \
    --disable-encoders --enable-encoder="$ENCODERS" --disable-decoders --enable-decoder="$DECODERS" \
    --disable-muxers --enable-muxer="$MUXERS" --disable-bsfs --enable-bsf=aac_adtstoasc,extract_extradata,null,setts,opus_metadata,vp9_superframe,mp3_header_decompress,noise \
    --enable-libmp3lame --enable-libopus --enable-libvorbis --enable-libopencore-amrnb --enable-libopencore-amrwb \
    --extra-cflags="-I$PFX/include $CFLAGS" --extra-ldflags="-L$PFX/lib $LDFLAGS -pie" $extra
  make -j$JOBS >/dev/null
  local O="$ROOT/../app/src/main/jniLibs/$abi"; mkdir -p "$O"
  "$STRIP" -o "$O/libffmpeg.so" ffmpeg; "$STRIP" -o "$O/libffprobe.so" ffprobe
  echo "built $abi"; ls -la "$O"
}
for a in ${@:-arm64-v8a armeabi-v7a x86_64}; do build_abi "$a"; done
