/*
 * vgmplayer_jni.cpp
 *
 * JNI glue layer between Android/Kotlin and libvgm.
 * Based on vgmplay_glue.cpp from vgmplay-js-2 but outputs int16 PCM for
 * AudioTrack.
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

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "VgmJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VgmJNI", __VA_ARGS__)

static VGMPlayer *gPlayer = nullptr;
static DATA_LOADER *gLoader = nullptr;
static char *gTitleBuf = nullptr;
static char *gChipBuf = nullptr;
static UINT32 gSampleRate = 44100;
static std::string gRomPath = "";

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

static void cleanup() {
  if (gPlayer) {
    gPlayer->Stop();
    gPlayer->UnloadFile();
    delete gPlayer;
    gPlayer = nullptr;
  }
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

extern "C" {

// org.vlessert.vgmp.engine.VgmEngine native methods

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetSampleRate(
    JNIEnv *env, jclass cls, jint rate) {
  gSampleRate = (UINT32)rate;
  if (gPlayer)
    gPlayer->SetSampleRate(gSampleRate);
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetRomPath(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  gRomPath = path;
  env->ReleaseStringUTFChars(jpath, path);
  LOGD("nSetRomPath: %s", gRomPath.c_str());
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

JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nOpen(
    JNIEnv *env, jclass cls, jstring jpath) {
  cleanup();

  const char *path = env->GetStringUTFChars(jpath, nullptr);
  LOGD("nOpen: %s", path);

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

  gPlayer = new VGMPlayer();
  gPlayer->SetSampleRate(gSampleRate);
  gPlayer->SetFileReqCallback(RequestFileCallback, nullptr);

  VGM_PLAY_OPTIONS opts;
  memset(&opts, 0, sizeof(opts));
  opts.playbackHz = 0;
  gPlayer->SetPlayerOptions(opts);

  if (gPlayer->LoadFile(gLoader)) {
    LOGE("LoadFile failed");
    delete gPlayer;
    gPlayer = nullptr;
    DataLoader_Deinit(gLoader);
    gLoader = nullptr;
    return JNI_FALSE;
  }

  gPlayer->SetSampleRate(gSampleRate);
  gPlayer->Start();
  LOGD("nOpen: success, sampleRate=%u", gSampleRate);
  return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nClose(JNIEnv *env, jclass cls) {
  cleanup();
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nPlay(JNIEnv *env, jclass cls) {
  if (gPlayer) {
    gPlayer->SetSampleRate(gSampleRate);
    gPlayer->Start();
  }
}

JNIEXPORT void JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nStop(JNIEnv *env, jclass cls) {
  if (gPlayer)
    gPlayer->Stop();
}

JNIEXPORT jboolean JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nIsEnded(JNIEnv *env, jclass cls) {
  if (!gPlayer)
    return JNI_TRUE;
  return (gPlayer->GetState() & PLAYSTATE_END) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTotalSamples(JNIEnv *env,
                                                         jclass cls) {
  if (!gPlayer)
    return 0;
  return (jlong)gPlayer->Tick2Sample(gPlayer->GetTotalTicks());
}

JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetCurrentSample(JNIEnv *env,
                                                          jclass cls) {
  if (!gPlayer)
    return 0;
  return (jlong)gPlayer->Tick2Sample(gPlayer->GetCurPos(PLAYPOS_TICK));
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSeek(
    JNIEnv *env, jclass cls, jlong samplePos) {
  if (!gPlayer)
    return;
  // Seek via absolute sample position
  gPlayer->Seek(PLAYPOS_SAMPLE, (UINT32)samplePos);
}

/**
 * Fill a short[] buffer with stereo int16 PCM samples.
 * buffer layout: [L0, R0, L1, R1, ...]  (interleaved stereo)
 * Returns number of sample frames written.
 */
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nFillBuffer(
    JNIEnv *env, jclass cls, jshortArray buffer, jint frames) {
  if (!gPlayer || frames <= 0)
    return 0;

  enum { MAX_FRAMES = 4096 };
  static WAVE_32BS buf[MAX_FRAMES];

  jint remaining = frames;
  jint written = 0;

  jshort *dst = (jshort *)env->GetShortArrayElements(buffer, nullptr);

  while (remaining > 0) {
    jint chunk = (remaining > MAX_FRAMES) ? MAX_FRAMES : remaining;
    memset(buf, 0, chunk * sizeof(WAVE_32BS));
    UINT32 got = gPlayer->Render((UINT32)chunk, buf);
    if (got == 0) {
      LOGD("nFillBuffer: Render returned 0");
      break;
    }

    for (jint i = 0; i < (jint)got; i++) {
      // Convert 24-bit fixed-point to 16-bit
      INT32 l = buf[i].L >> 8;
      INT32 r = buf[i].R >> 8;
      if (l > 32767)
        l = 32767;
      if (l < -32768)
        l = -32768;
      if (r > 32767)
        r = 32767;
      if (r < -32768)
        r = -32768;
      dst[(written + i) * 2] = (jshort)l;
      dst[(written + i) * 2 + 1] = (jshort)r;

      // Feed mono sample to FFT ring buffer
      gFftRingBuffer[gFftWriteIdx] = (float)(l + r) / 65536.0f;
      gFftWriteIdx = (gFftWriteIdx + 1) % FFT_SIZE;
    }
    written += (jint)got;
    remaining -= (jint)got;
  }

  env->ReleaseShortArrayElements(buffer, dst, 0);

  // Occasional logging to avoid flooding
  static int logCounter = 0;
  if (logCounter++ % 100 == 0) {
    LOGD("nFillBuffer: wrote %d frames", written);
    if (written > 0) {
      LOGD("nFillBuffer: samples[0] L=%d, R=%d", dst[0], dst[1]);
    }
  }

  return written;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetSpectrum(
    JNIEnv *env, jclass cls, jfloatArray outMagnitudes) {
  int n = FFT_SIZE;
  std::vector<Complex> a(n);

  // Read from ring buffer (start from current writeIdx to get latest)
  for (int i = 0; i < n; i++) {
    a[i] = Complex(gFftRingBuffer[(gFftWriteIdx + i) % n], 0.0f);
  }

  // Apply Hanning window
  for (int i = 0; i < n; i++) {
    float multiplier =
        0.5f *
        (1.0f - std::cos(2.0f * 3.14159265f * (float)i / (float)(n - 1)));
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

  env->ReleaseFloatArrayElements(outMagnitudes, dst, 0);
}

/**
 * Get track tags as a single string:
 * "TrkE|||TrkJ|||GmE|||GmJ|||SysE|||SysJ|||AutE|||AutJ|||..."
 */
JNIEXPORT jstring JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTags(JNIEnv *env, jclass cls) {
  if (!gPlayer)
    return env->NewStringUTF("");
  const char *const *t = gPlayer->GetTags();
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

/**
 * Scan a VGM file to get its length in samples without loading it as the active
 * track.
 */
JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackLengthDirect(JNIEnv *env,
                                                              jclass cls,
                                                              jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);

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
  if (!gPlayer)
    return 0;
  std::vector<PLR_DEV_INFO> devs;
  if (gPlayer->GetSongDeviceInfo(devs) <= 0x01) {
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
  return 0;
}

JNIEXPORT jstring JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetDeviceName(JNIEnv *env, jclass cls,
                                                       jint id) {
  if (!gPlayer)
    return env->NewStringUTF("");
  std::vector<PLR_DEV_INFO> devs;
  if (gPlayer->GetSongDeviceInfo(devs) <= 0x01) {
    for (size_t i = 0; i < devs.size(); i++) {
      if (devs[i].id == (UINT32)id) {
        const char *name = (devs[i].devDecl && devs[i].devDecl->name)
                               ? devs[i].devDecl->name(devs[i].devCfg)
                               : "Unknown";
        return env->NewStringUTF(name ? name : "Unknown");
      }
    }
  }
  return env->NewStringUTF("");
}

JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetDeviceVolume(
    JNIEnv *env, jclass cls, jint id) {
  if (!gPlayer)
    return 0x100;
  std::vector<PLR_DEV_INFO> devs;
  if (gPlayer->GetSongDeviceInfo(devs) <= 0x01) {
    for (size_t i = 0; i < devs.size(); i++) {
      if (devs[i].id == (UINT32)id)
        return (jint)devs[i].volume;
    }
  }
  return 0x100;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetDeviceVolume(
    JNIEnv *env, jclass cls, jint id, jint vol) {
  if (gPlayer)
    gPlayer->SetDeviceVolume(id, (UINT32)vol);
}

} // extern "C"
