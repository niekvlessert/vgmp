/*
 * vgmplayer_jni.cpp
 *
 * JNI glue layer between Android/Kotlin and libvgm/libgme.
 * Supports VGM/VGZ via libvgm and NSF/NSFE/GBS/SPC/etc via libgme.
 */

#include <algorithm>
#include <android/log.h>
#include <cmath>
#include <complex>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <jni.h>
#include <string>
#include <vector>

#include "libvgm/emu/EmuStructs.h"
#include "libvgm/emu/Resampler.h"
#include "libvgm/player/playerbase.hpp"
#include "libvgm/player/vgmplayer.hpp"
#include "libvgm/utils/DataLoader.h"
#include "libvgm/utils/FileLoader.h"

// libgme for NSF and other formats
#include "gme.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "VgmJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VgmJNI", __VA_ARGS__)

// Player type enumeration
enum class PlayerType { NONE, LIBVGM, LIBGME };

static PlayerType gPlayerType = PlayerType::NONE;
static VGMPlayer *gVgmPlayer = nullptr;
static Music_Emu *gGmePlayer = nullptr;
static DATA_LOADER *gLoader = nullptr;
static char *gTitleBuf = nullptr;
static char *gChipBuf = nullptr;
static UINT32 gSampleRate = 44100;
static std::string gRomPath = "";

// Current track index for libgme (NSF can have multiple tracks)
static int gGmeTrackIndex = 0;
static int gGmeTrackCount = 0;

// FFT / Spectrum State
#define FFT_SIZE 1024
static float gFftRingBuffer[FFT_SIZE];
static int gFftWriteIdx = 0;

typedef std::complex<float> Complex;
static void fft_process(std::vector<Complex> &a) {
  int n = a.size();
  for (int i = 1, j = 0; i < n; i++) {
    int bit = n >> 1;
    for (; j & bit; bit >>= 1)
      j ^= bit;
    j ^= bit;
    if (i < j)
      std::swap(a[i], a[j]);
  }
  for (int len = 2; len <= n; len <<= 1) {
    float ang = 2.0f * 3.14159265f / (float)len;
    Complex wlen(std::cos(ang), std::sin(ang));
    for (int i = 0; i < n; i += len) {
      Complex w(1.0f, 0.0f);
      for (int j = 0; j < len / 2; j++) {
        Complex u = a[i + j];
        Complex v = a[i + j + len / 2] * w;
        a[i + j] = u + v;
        a[i + j + len / 2] = u - v;
        w *= wlen;
      }
    }
  }
}

static DATA_LOADER *RequestFileCallback(void *userParam, PlayerBase *player,
                                        const char *fileName) {
  DATA_LOADER *dLoad = FileLoader_Init(fileName);
  UINT8 retVal = DataLoader_Load(dLoad);
  if (!retVal)
    return dLoad;
  DataLoader_Deinit(dLoad);

  // If not found and we have a ROM path, try there
  if (!gRomPath.empty()) {
    std::string fullPath = gRomPath;
    if (fullPath.back() != '/')
      fullPath += "/";
    fullPath += fileName;
    dLoad = FileLoader_Init(fullPath.c_str());
    retVal = DataLoader_Load(dLoad);
    if (!retVal)
      return dLoad;
    DataLoader_Deinit(dLoad);
  }

  return nullptr;
}

// Check if file extension is supported by libgme
static bool isGmeFormat(const char *path) {
  const char *ext = strrchr(path, '.');
  if (!ext) return false;
  ext++; // skip the dot
  
  // Convert to lowercase for comparison
  char lowerExt[8] = {0};
  for (int i = 0; ext[i] && i < 7; i++) {
    lowerExt[i] = tolower(ext[i]);
  }
  
  // libgme supported formats
  return (strcmp(lowerExt, "nsf") == 0 ||
          strcmp(lowerExt, "nsfe") == 0 ||
          strcmp(lowerExt, "gbs") == 0 ||
          strcmp(lowerExt, "gym") == 0 ||
          strcmp(lowerExt, "hes") == 0 ||
          strcmp(lowerExt, "kss") == 0 ||
          strcmp(lowerExt, "ay") == 0 ||
          strcmp(lowerExt, "sap") == 0 ||
          strcmp(lowerExt, "spc") == 0);
}

static void cleanup() {
  // Cleanup libvgm
  if (gVgmPlayer) {
    gVgmPlayer->Stop();
    gVgmPlayer->UnloadFile();
    delete gVgmPlayer;
    gVgmPlayer = nullptr;
  }
  
  // Cleanup libgme
  if (gGmePlayer) {
    gme_delete(gGmePlayer);
    gGmePlayer = nullptr;
  }
  
  gPlayerType = PlayerType::NONE;
  gGmeTrackIndex = 0;
  gGmeTrackCount = 0;
  
  if (gLoader) {
    DataLoader_Deinit(gLoader);
    gLoader = nullptr;
  }
  if (gTitleBuf) {
    free(gTitleBuf);
    gTitleBuf = nullptr;
  }
  if (gChipBuf) {
    free(gChipBuf);
    gChipBuf = nullptr;
  }
  std::memset(gFftRingBuffer, 0, sizeof(gFftRingBuffer));
  gFftWriteIdx = 0;
}

#include "libvgm/utils/StrUtils.h"

// -----------------------------------------------------------------------------------------
// Dummy CPConv (charset conversion) stubs to allow linking without iconv.
// -----------------------------------------------------------------------------------------
extern "C" {
UINT8 CPConv_Init(CPCONV **retCPC, const char *cpFrom, const char *cpTo) {
  *retCPC = nullptr;
  return 1; // Error: feature disabled
}
void CPConv_Deinit(CPCONV *cpc) {}
UINT8 CPConv_StrConvert(CPCONV *cpc, size_t *outSize, char **outStr,
                        size_t inSize, const char *inStr) {
  *outSize = 0;
  *outStr = nullptr;
  return 1;
}
}

extern "C" {

// org.vlessert.vgmp.engine.VgmEngine native methods

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetSampleRate(
    JNIEnv *env, jclass cls, jint rate) {
  gSampleRate = (UINT32)rate;
  if (gVgmPlayer)
    gVgmPlayer->SetSampleRate(gSampleRate);
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetRomPath(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  gRomPath = path;
  env->ReleaseStringUTFChars(jpath, path);
  LOGD("nSetRomPath: %s", gRomPath.c_str());
}

JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nOpen(
    JNIEnv *env, jclass cls, jstring jpath) {
  cleanup();

  const char *path = env->GetStringUTFChars(jpath, nullptr);
  LOGD("nOpen: %s", path);

  // Check if this is a libgme format
  if (isGmeFormat(path)) {
    LOGD("Detected libgme format: %s", path);
    
    gme_err_t err = gme_open_file(path, &gGmePlayer, gSampleRate);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (err) {
      LOGE("gme_open_file failed: %s", err);
      gGmePlayer = nullptr;
      return JNI_FALSE;
    }
    
    gPlayerType = PlayerType::LIBGME;
    gGmeTrackCount = gme_track_count(gGmePlayer);
    gGmeTrackIndex = 0;
    
    // Start first track
    err = gme_start_track(gGmePlayer, 0);
    if (err) {
      LOGE("gme_start_track failed: %s", err);
      gme_delete(gGmePlayer);
      gGmePlayer = nullptr;
      gPlayerType = PlayerType::NONE;
      return JNI_FALSE;
    }
    
    LOGD("nOpen: libgme success, %d tracks, sampleRate=%u", gGmeTrackCount, gSampleRate);
    return JNI_TRUE;
  }

  // Use libvgm for VGM/VGZ files
  gLoader = FileLoader_Init(path);
  if (!gLoader) {
    LOGE("FileLoader_Init failed for %s", path);
    env->ReleaseStringUTFChars(jpath, path);
    return JNI_FALSE;
  }
  if (DataLoader_Load(gLoader)) {
    LOGE("DataLoader_Load failed for %s", path);
    env->ReleaseStringUTFChars(jpath, path);
    DataLoader_Deinit(gLoader);
    gLoader = nullptr;
    return JNI_FALSE;
  }

  env->ReleaseStringUTFChars(jpath, path);

  gVgmPlayer = new VGMPlayer();
  gVgmPlayer->SetSampleRate(gSampleRate);
  gVgmPlayer->SetFileReqCallback(RequestFileCallback, nullptr);

  VGM_PLAY_OPTIONS opts;
  memset(&opts, 0, sizeof(opts));
  opts.playbackHz = 0;
  gVgmPlayer->SetPlayerOptions(opts);

  if (gVgmPlayer->LoadFile(gLoader)) {
    LOGE("LoadFile failed");
    delete gVgmPlayer;
    gVgmPlayer = nullptr;
    DataLoader_Deinit(gLoader);
    gLoader = nullptr;
    return JNI_FALSE;
  }

  gPlayerType = PlayerType::LIBVGM;
  gVgmPlayer->SetSampleRate(gSampleRate);
  gVgmPlayer->Start();
  LOGD("nOpen: libvgm success, sampleRate=%u", gSampleRate);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nClose(JNIEnv *env, jclass cls) {
  cleanup();
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nPlay(JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    gVgmPlayer->SetSampleRate(gSampleRate);
    gVgmPlayer->Start();
  }
  // libgme doesn't have a separate play function - it plays via gme_play()
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nStop(JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    gVgmPlayer->Stop();
  }
  // libgme doesn't have a separate stop function
}

JNIEXPORT jboolean JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nIsEnded(JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    return (gVgmPlayer->GetState() & PLAYSTATE_END) ? JNI_TRUE : JNI_FALSE;
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    return gme_track_ended(gGmePlayer) ? JNI_TRUE : JNI_FALSE;
  }
  return JNI_TRUE;
}

JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTotalSamples(JNIEnv *env,
                                                         jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    return (jlong)gVgmPlayer->Tick2Sample(gVgmPlayer->GetTotalTicks());
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    gme_info_t *info;
    if (gme_track_info(gGmePlayer, &info, gGmeTrackIndex) == 0) {
      int length_ms = info->play_length;
      gme_free_info(info);
      return (jlong)(length_ms * gSampleRate / 1000);
    }
  }
  return 0;
}

JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetCurrentSample(JNIEnv *env,
                                                          jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    return (jlong)gVgmPlayer->Tick2Sample(gVgmPlayer->GetCurPos(PLAYPOS_TICK));
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    int ms = gme_tell(gGmePlayer);
    return (jlong)(ms * gSampleRate / 1000);
  }
  return 0;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSeek(
    JNIEnv *env, jclass cls, jlong samplePos) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    gVgmPlayer->Seek(PLAYPOS_SAMPLE, (UINT32)samplePos);
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    int ms = (int)(samplePos * 1000 / gSampleRate);
    gme_seek(gGmePlayer, ms);
  }
}

/**
 * Fill a short[] buffer with stereo int16 PCM samples.
 * buffer layout: [L0, R0, L1, R1, ...]  (interleaved stereo)
 * Returns number of sample frames written.
 */
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nFillBuffer(
    JNIEnv *env, jclass cls, jshortArray buffer, jint frames) {
  if (frames <= 0)
    return 0;

  jshort *dst = (jshort *)env->GetShortArrayElements(buffer, nullptr);
  jint written = 0;

  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    enum { MAX_FRAMES = 4096 };
    static WAVE_32BS buf[MAX_FRAMES];

    jint remaining = frames;

    while (remaining > 0) {
      jint chunk = (remaining > MAX_FRAMES) ? MAX_FRAMES : remaining;
      memset(buf, 0, chunk * sizeof(WAVE_32BS));
      UINT32 got = gVgmPlayer->Render((UINT32)chunk, buf);
      if (got == 0) {
        LOGD("nFillBuffer: Render returned 0");
        break;
      }

      for (jint i = 0; i < (jint)got; i++) {
        INT32 l = buf[i].L >> 8;
        INT32 r = buf[i].R >> 8;
        if (l > 32767) l = 32767;
        if (l < -32768) l = -32768;
        if (r > 32767) r = 32767;
        if (r < -32768) r = -32768;
        dst[(written + i) * 2] = (jshort)l;
        dst[(written + i) * 2 + 1] = (jshort)r;

        gFftRingBuffer[gFftWriteIdx] = (float)(l + r) / 65536.0f;
        gFftWriteIdx = (gFftWriteIdx + 1) % FFT_SIZE;
      }
      written += (jint)got;
      remaining -= (jint)got;
    }
  } else if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    // libgme outputs directly in 16-bit stereo interleaved format
    gme_err_t err = gme_play(gGmePlayer, frames * 2, dst);
    if (err) {
      LOGE("gme_play error: %s", err);
    } else {
      written = frames;
      
      // Feed mono samples to FFT ring buffer
      for (jint i = 0; i < written; i++) {
        float sample = (float)dst[i * 2] / 32768.0f + (float)dst[i * 2 + 1] / 32768.0f;
        gFftRingBuffer[gFftWriteIdx] = sample / 2.0f;
        gFftWriteIdx = (gFftWriteIdx + 1) % FFT_SIZE;
      }
    }
  }

  env->ReleaseShortArrayElements(buffer, dst, 0);

  // Occasional logging to avoid flooding
  static int logCounter = 0;
  if (logCounter++ % 100 == 0) {
    LOGD("nFillBuffer: wrote %d frames, playerType=%d", written, (int)gPlayerType);
  }

  return written;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetSpectrum(
    JNIEnv *env, jclass cls, jfloatArray outMagnitudes) {
  int n = FFT_SIZE;
  std::vector<Complex> a(n);

  for (int i = 0; i < n; i++) {
    a[i] = Complex(gFftRingBuffer[(gFftWriteIdx + i) % n], 0.0f);
  }

  for (int i = 0; i < n; i++) {
    float multiplier =
        0.5f * (1.0f - std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1)));
    a[i] *= multiplier;
  }

  fft_process(a);

  jfloat *dst = env->GetFloatArrayElements(outMagnitudes, nullptr);
  if (!dst)
    return;

  int outSize = n / 2;
  float maxMag = 0.0f;
  for (int i = 0; i < outSize; i++) {
    dst[i] = std::abs(a[i]);
    if (dst[i] > maxMag)
      maxMag = dst[i];
  }

  if (maxMag > 0.0f) {
    float scale = 255.0f / maxMag;
    for (int i = 0; i < outSize; i++) {
      dst[i] = dst[i] * scale;
    }
  }

  env->ReleaseFloatArrayElements(outMagnitudes, dst, 0);
}

/**
 * Get track tags as a single string:
 * "TrkE|||TrkJ|||GmE|||GmJ|||SysE|||SysJ|||AutE|||AutJ|||..."
 */
JNIEXPORT jstring JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTags(JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    const char *const *t = gVgmPlayer->GetTags();
    if (!t)
      return env->NewStringUTF("");

    std::string s;
    while (*t) {
      s += *t;
      s += "|||";
      ++t;
    }
    return env->NewStringUTF(s.c_str());
  }
  
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    gme_info_t *info;
    if (gme_track_info(gGmePlayer, &info, gGmeTrackIndex) != 0) {
      return env->NewStringUTF("");
    }
    
    // Build tags string in similar format to VGM
    std::string s;
    
    // TITLE
    s += "TITLE";
    s += "|||";
    s += info->song ? info->song : "";
    s += "|||";
    
    // TITLE-JPN (not available in gme)
    s += "TITLE-JPN";
    s += "|||";
    s += "|||";
    
    // GAME
    s += "GAME";
    s += "|||";
    s += info->game ? info->game : "";
    s += "|||";
    
    // GAME-JPN
    s += "GAME-JPN";
    s += "|||";
    s += "|||";
    
    // SYSTEM
    s += "SYSTEM";
    s += "|||";
    s += info->system ? info->system : "";
    s += "|||";
    
    // SYSTEM-JPN
    s += "SYSTEM-JPN";
    s += "|||";
    s += "|||";
    
    // ARTIST
    s += "ARTIST";
    s += "|||";
    s += info->author ? info->author : "";
    s += "|||";
    
    // ARTIST-JPN
    s += "ARTIST-JPN";
    s += "|||";
    s += "|||";
    
    // DATE
    s += "DATE";
    s += "|||";
    s += info->copyright ? info->copyright : "";
    s += "|||";
    
    // ENCODED_BY (dumper)
    s += "ENCODED_BY";
    s += "|||";
    s += info->dumper ? info->dumper : "";
    s += "|||";
    
    // COMMENT
    s += "COMMENT";
    s += "|||";
    s += info->comment ? info->comment : "";
    s += "|||";
    
    gme_free_info(info);
    return env->NewStringUTF(s.c_str());
  }
  
  return env->NewStringUTF("");
}

/**
 * Scan a VGM file to get its length in samples without loading it as the active
 * track. For multi-track files (NSF), returns length for track 0.
 * Use nGetTrackLength for specific track index.
 */
JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackLengthDirect(JNIEnv *env,
                                                              jclass cls,
                                                              jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);

  // Check if this is a libgme format
  if (isGmeFormat(path)) {
    Music_Emu *tempEmu;
    gme_err_t err = gme_open_file(path, &tempEmu, gSampleRate);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (err || !tempEmu) {
      return 0;
    }
    
    gme_info_t *info;
    if (gme_track_info(tempEmu, &info, 0) != 0) {
      gme_delete(tempEmu);
      return 0;
    }
    
    // For NSF/SPC files, play_length is often a default or incorrect value
    // intro_length + loop_length gives more accurate estimate if available
    int length_ms = info->play_length;
    
    // Use intro + 2 loops for better estimate if available
    if (info->intro_length > 0 && info->loop_length > 0) {
      length_ms = info->intro_length + info->loop_length * 2;
    }
    
    // If length is unreasonably short (< 30 seconds), default to 3 minutes
    // SPC and NSF files typically loop and don't have a fixed duration
    if (length_ms < 30000) {
      length_ms = 180000; // 3 minutes default
    }
    
    gme_free_info(info);
    gme_delete(tempEmu);
    
    return (jlong)(length_ms * gSampleRate / 1000);
  }

  // Use libvgm for VGM/VGZ
  DATA_LOADER *locLoader = FileLoader_Init(path);
  env->ReleaseStringUTFChars(jpath, path);

  if (!locLoader)
    return 0;
  if (DataLoader_Load(locLoader)) {
    DataLoader_Deinit(locLoader);
    return 0;
  }

  VGMPlayer *locPlayer = new VGMPlayer();
  locPlayer->SetSampleRate(gSampleRate);
  if (locPlayer->LoadFile(locLoader)) {
    delete locPlayer;
    DataLoader_Deinit(locLoader);
    return 0;
  }

  jlong length = (jlong)locPlayer->Tick2Sample(locPlayer->GetTotalTicks());
  locPlayer->UnloadFile();
  delete locPlayer;
  DataLoader_Deinit(locLoader);
  return length;
}

JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetDeviceCount(
    JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    std::vector<PLR_DEV_INFO> devs;
    if (gVgmPlayer->GetSongDeviceInfo(devs) <= 0x01) {
      std::vector<UINT32> ids;
      for (auto &d : devs) {
        bool found = false;
        for (auto e : ids) {
          if (e == d.id) {
            found = true;
            break;
          }
        }
        if (!found)
          ids.push_back(d.id);
      }
      return (jint)ids.size();
    }
  }
  // libgme doesn't expose device info in the same way
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    return gme_voice_count(gGmePlayer);
  }
  return 0;
}

JNIEXPORT jstring JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetDeviceName(JNIEnv *env, jclass cls,
                                                       jint id) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    std::vector<PLR_DEV_INFO> devs;
    if (gVgmPlayer->GetSongDeviceInfo(devs) <= 0x01) {
      for (size_t i = 0; i < devs.size(); i++) {
        if (devs[i].id == (UINT32)id) {
          const char *name = (devs[i].devDecl && devs[i].devDecl->name)
                                 ? devs[i].devDecl->name(devs[i].devCfg)
                                 : "Unknown";
          return env->NewStringUTF(name ? name : "Unknown");
        }
      }
    }
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    const char *name = gme_voice_name(gGmePlayer, id);
    return env->NewStringUTF(name ? name : "Unknown");
  }
  return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetDeviceVolume(
    JNIEnv *env, jclass cls, jint id) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    std::vector<PLR_DEV_INFO> devs;
    if (gVgmPlayer->GetSongDeviceInfo(devs) <= 0x01) {
      for (size_t i = 0; i < devs.size(); i++) {
        if (devs[i].id == (UINT32)id)
          return (jint)devs[i].volume;
      }
    }
  }
  return 0x100;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetDeviceVolume(
    JNIEnv *env, jclass cls, jint id, jint vol) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    gVgmPlayer->SetDeviceVolume(id, (UINT32)vol);
  }
  // libgme doesn't support per-device volume
}

// libgme-specific: get track count for multi-track files (NSF, GBS, etc.)
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackCount(
    JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    return gGmeTrackCount;
  }
  // VGM files typically have 1 track
  return 1;
}

// libgme-specific: set current track index
JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetTrack(
    JNIEnv *env, jclass cls, jint trackIndex) {
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    if (trackIndex >= 0 && trackIndex < gGmeTrackCount) {
      gme_err_t err = gme_start_track(gGmePlayer, trackIndex);
      if (err) {
        LOGE("gme_start_track(%d) failed: %s", trackIndex, err);
        return JNI_FALSE;
      }
      gGmeTrackIndex = trackIndex;
      return JNI_TRUE;
    }
  }
  return JNI_FALSE;
}

// libgme-specific: get current track index
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetCurrentTrack(
    JNIEnv *env, jclass cls) {
  return gGmeTrackIndex;
}

// Check if file is a multi-track format (NSF, GBS, etc.)
JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nIsMultiTrack(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  bool result = isGmeFormat(path);
  env->ReleaseStringUTFChars(jpath, path);
  return result ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
