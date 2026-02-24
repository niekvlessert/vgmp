/*
 * vgmplayer_jni.cpp
 *
 * JNI glue layer between Android/Kotlin and libvgm/libgme/libopenmpt/libADLMIDI.
 * Supports VGM/VGZ via libvgm, NSF/NSFE/GBS/SPC/etc via libgme,
 * MOD/XM/S3M/IT/etc via libopenmpt, and MIDI via libADLMIDI.
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

// libopenmpt for tracker formats (MOD, XM, S3M, IT, etc.)
#include "libopenmpt/libopenmpt.h"

// libkss for KSS format (MSX music files)
#include "kssplay.h"
#include "kss/kss.h"

// libADLMIDI for MIDI files (OPL3 FM synthesis)
#include "adlmidi.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "VgmJNI", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "VgmJNI", __VA_ARGS__)

// Player type enumeration
enum class PlayerType { NONE, LIBVGM, LIBGME, LIBOPENMPT, LIBKSS, LIBADLMIDI };

static PlayerType gPlayerType = PlayerType::NONE;
static VGMPlayer *gVgmPlayer = nullptr;
static Music_Emu *gGmePlayer = nullptr;
static openmpt_module *gOpenmptModule = nullptr;
static KSS *gKss = nullptr;
static KSSPLAY *gKssPlay = nullptr;
static ADL_MIDIPlayer *gAdlPlayer = nullptr;
static DATA_LOADER *gLoader = nullptr;
static char *gTitleBuf = nullptr;
static char *gChipBuf = nullptr;
static UINT32 gSampleRate = 44100;
static std::string gRomPath = "";

// Current track index for libgme (NSF can have multiple tracks)
static int gGmeTrackIndex = 0;
static int gGmeTrackCount = 0;

// Current track index for libkss (KSS can have multiple tracks)
static int gKssTrackIndex = 0;
static int gKssTrackCount = 0;

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
  
  // libgme supported formats (KSS removed - now using libkss)
  return (strcmp(lowerExt, "nsf") == 0 ||
          strcmp(lowerExt, "nsfe") == 0 ||
          strcmp(lowerExt, "gbs") == 0 ||
          strcmp(lowerExt, "gym") == 0 ||
          strcmp(lowerExt, "hes") == 0 ||
          strcmp(lowerExt, "ay") == 0 ||
          strcmp(lowerExt, "sap") == 0 ||
          strcmp(lowerExt, "spc") == 0);
}

// Check if file extension is KSS format (MSX music)
static bool isKssFormat(const char *path) {
  const char *ext = strrchr(path, '.');
  if (!ext) return false;
  ext++; // skip the dot
  
  // Convert to lowercase for comparison
  char lowerExt[8] = {0};
  for (int i = 0; ext[i] && i < 7; i++) {
    lowerExt[i] = tolower(ext[i]);
  }
  
  // KSS and related MSX formats
  return (strcmp(lowerExt, "kss") == 0 ||
          strcmp(lowerExt, "mgs") == 0 ||
          strcmp(lowerExt, "bgm") == 0 ||
          strcmp(lowerExt, "opx") == 0 ||
          strcmp(lowerExt, "mpk") == 0 ||
          strcmp(lowerExt, "mbm") == 0);
}

// Check if file extension is a tracker format supported by libopenmpt
static bool isOpenmptFormat(const char *path) {
  const char *ext = strrchr(path, '.');
  if (!ext) return false;
  ext++; // skip the dot
  
  // Convert to lowercase for comparison
  char lowerExt[8] = {0};
  for (int i = 0; ext[i] && i < 7; i++) {
    lowerExt[i] = tolower(ext[i]);
  }
  
  // libopenmpt supported formats (most common ones)
  return (strcmp(lowerExt, "mod") == 0 ||
          strcmp(lowerExt, "xm") == 0 ||
          strcmp(lowerExt, "s3m") == 0 ||
          strcmp(lowerExt, "it") == 0 ||
          strcmp(lowerExt, "mptm") == 0 ||
          strcmp(lowerExt, "669") == 0 ||
          strcmp(lowerExt, "amf") == 0 ||
          strcmp(lowerExt, "ams") == 0 ||
          strcmp(lowerExt, "dbm") == 0 ||
          strcmp(lowerExt, "digi") == 0 ||
          strcmp(lowerExt, "dmf") == 0 ||
          strcmp(lowerExt, "dsm") == 0 ||
          strcmp(lowerExt, "far") == 0 ||
          strcmp(lowerExt, "gdm") == 0 ||
          strcmp(lowerExt, "imf") == 0 ||
          strcmp(lowerExt, "j2b") == 0 ||
          strcmp(lowerExt, "mdl") == 0 ||
          strcmp(lowerExt, "med") == 0 ||
          strcmp(lowerExt, "mt2") == 0 ||
          strcmp(lowerExt, "mtm") == 0 ||
          strcmp(lowerExt, "okt") == 0 ||
          strcmp(lowerExt, "plm") == 0 ||
          strcmp(lowerExt, "psm") == 0 ||
          strcmp(lowerExt, "ptm") == 0 ||
          strcmp(lowerExt, "rtm") == 0 ||
          strcmp(lowerExt, "stm") == 0 ||
          strcmp(lowerExt, "ult") == 0 ||
          strcmp(lowerExt, "umx") == 0 ||
          strcmp(lowerExt, "wow") == 0);
}

// Check if file extension is a MIDI format supported by libADLMIDI
static bool isMidiFormat(const char *path) {
  const char *ext = strrchr(path, '.');
  if (!ext) return false;
  ext++; // skip the dot
  
  // Convert to lowercase for comparison
  char lowerExt[8] = {0};
  for (int i = 0; ext[i] && i < 7; i++) {
    lowerExt[i] = tolower(ext[i]);
  }
  
  // MIDI file formats
  return (strcmp(lowerExt, "mid") == 0 ||
          strcmp(lowerExt, "midi") == 0 ||
          strcmp(lowerExt, "rmi") == 0 ||
          strcmp(lowerExt, "smf") == 0);
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
  
  // Cleanup libopenmpt
  if (gOpenmptModule) {
    openmpt_module_destroy(gOpenmptModule);
    gOpenmptModule = nullptr;
  }
  
  // Cleanup libkss
  if (gKssPlay) {
    KSSPLAY_delete(gKssPlay);
    gKssPlay = nullptr;
  }
  if (gKss) {
    KSS_delete(gKss);
    gKss = nullptr;
  }
  
  // Cleanup libADLMIDI
  if (gAdlPlayer) {
    adl_close(gAdlPlayer);
    gAdlPlayer = nullptr;
  }
  
  gPlayerType = PlayerType::NONE;
  gGmeTrackIndex = 0;
  gGmeTrackCount = 0;
  gKssTrackIndex = 0;
  gKssTrackCount = 0;
  
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

  // Check if this is a KSS format for libkss
  if (isKssFormat(path)) {
    LOGD("Detected KSS format: %s", path);
    
    // Read the entire file into memory for libkss
    FILE *f = fopen(path, "rb");
    
    if (!f) {
      LOGE("Failed to open KSS file: %s", path);
      env->ReleaseStringUTFChars(jpath, path);
      return JNI_FALSE;
    }
    
    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);
    LOGD("KSS file size: %ld bytes", fileSize);
    
    std::vector<uint8_t> fileData(fileSize);
    if (fread(fileData.data(), 1, fileSize, f) != (size_t)fileSize) {
      LOGE("Failed to read KSS file");
      fclose(f);
      env->ReleaseStringUTFChars(jpath, path);
      return JNI_FALSE;
    }
    fclose(f);
    
    // Get filename for KSS_bin2kss (it uses filename for MBM detection)
    const char *filename = strrchr(path, '/');
    filename = filename ? filename + 1 : path;
    
    env->ReleaseStringUTFChars(jpath, path);
    
    // Log first 16 bytes for debugging
    LOGD("KSS header: %02X %02X %02X %02X %02X %02X %02X %02X", 
         fileData[0], fileData[1], fileData[2], fileData[3],
         fileData[4], fileData[5], fileData[6], fileData[7]);
    
    // Create KSS object using KSS_bin2kss which properly parses the header
    // KSS_bin2kss handles KSCC, KSSX, MGS, BGM, OPX, MPK, MBM formats
    gKss = KSS_bin2kss(fileData.data(), fileSize, filename);
    if (!gKss) {
      LOGE("KSS_bin2kss failed");
      return JNI_FALSE;
    }
    LOGD("KSS_bin2kss success, type=%d, mode=%d", gKss->type, gKss->mode);
    
    // Create KSSPLAY object
    gKssPlay = KSSPLAY_new(gSampleRate, 2, 16);  // stereo, 16-bit
    if (!gKssPlay) {
      LOGE("KSSPLAY_new failed");
      KSS_delete(gKss);
      gKss = nullptr;
      return JNI_FALSE;
    }
    
    // Set KSS data to player
    int setDataResult = KSSPLAY_set_data(gKssPlay, gKss);
    LOGD("KSSPLAY_set_data result: %d", setDataResult);
    
    // Get track range
    gKssTrackCount = gKss->trk_max - gKss->trk_min + 1;
    if (gKssTrackCount < 1) gKssTrackCount = 1;
    gKssTrackIndex = gKss->trk_min;
    
    // Reset and start first track
    KSSPLAY_reset(gKssPlay, gKssTrackIndex, 0);  // track, cpu_speed=0 (auto)
    
    gPlayerType = PlayerType::LIBKSS;
    LOGD("nOpen: libkss success, %d tracks (min=%d, max=%d), sampleRate=%u, fmpac=%d, sn76489=%d", 
         gKssTrackCount, gKss->trk_min, gKss->trk_max, gSampleRate, gKss->fmpac, gKss->sn76489);
    return JNI_TRUE;
  }

  // Check if this is a tracker format for libopenmpt
  if (isOpenmptFormat(path)) {
    LOGD("Detected tracker format: %s", path);
    
    // Read the entire file into memory for libopenmpt
    FILE *f = fopen(path, "rb");
    env->ReleaseStringUTFChars(jpath, path);
    
    if (!f) {
      LOGE("Failed to open tracker file");
      return JNI_FALSE;
    }
    
    fseek(f, 0, SEEK_END);
    long fileSize = ftell(f);
    fseek(f, 0, SEEK_SET);
    
    std::vector<char> fileData(fileSize);
    if (fread(fileData.data(), 1, fileSize, f) != (size_t)fileSize) {
      LOGE("Failed to read tracker file");
      fclose(f);
      return JNI_FALSE;
    }
    fclose(f);
    
    gOpenmptModule = openmpt_module_create_from_memory2(
        fileData.data(), fileSize,
        openmpt_log_func_silent, nullptr,
        openmpt_error_func_ignore, nullptr,
        nullptr, nullptr, nullptr);
    
    if (!gOpenmptModule) {
      LOGE("openmpt_module_create_from_memory2 failed");
      return JNI_FALSE;
    }
    
    // Sample rate is set during creation, no need to set it separately
    // libopenmpt uses the sample rate passed to the read functions
    
    gPlayerType = PlayerType::LIBOPENMPT;
    LOGD("nOpen: libopenmpt success, sampleRate=%u", gSampleRate);
    return JNI_TRUE;
  }

  // Check if this is a MIDI format for libADLMIDI
  if (isMidiFormat(path)) {
    LOGD("Detected MIDI format: %s", path);
    
    // Initialize ADLMIDI with sample rate
    gAdlPlayer = adl_init(gSampleRate);
    if (!gAdlPlayer) {
      LOGE("adl_init failed");
      env->ReleaseStringUTFChars(jpath, path);
      return JNI_FALSE;
    }
    
    // Set OPL3 emulator (more accurate than OPL2)
    adl_setNumChips(gAdlPlayer, 2);  // Use 2 OPL3 chips for better polyphony
    adl_setBank(gAdlPlayer, 14);     // Bank 14 = DMX (Bobby Prince v2) - Doom bank!
    adl_setSoftPanEnabled(gAdlPlayer, 1);  // Enable stereo panning
    
    // Open the MIDI file
    int result = adl_openFile(gAdlPlayer, path);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (result != 0) {
      LOGE("adl_openFile failed: %s", adl_errorInfo(gAdlPlayer));
      adl_close(gAdlPlayer);
      gAdlPlayer = nullptr;
      return JNI_FALSE;
    }
    
    gPlayerType = PlayerType::LIBADLMIDI;
    LOGD("nOpen: libADLMIDI success, sampleRate=%u, bank=58 (DMXOP2)", gSampleRate);
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

// Endless loop mode - disable track end detection
static bool gEndlessLoopMode = false;

JNIEXPORT jboolean JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nIsEnded(JNIEnv *env, jclass cls) {
  // In endless loop mode, never report track as ended
  if (gEndlessLoopMode) {
    return JNI_FALSE;
  }
  
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    return (gVgmPlayer->GetState() & PLAYSTATE_END) ? JNI_TRUE : JNI_FALSE;
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    return gme_track_ended(gGmePlayer) ? JNI_TRUE : JNI_FALSE;
  }
  if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    // Tracker modules loop forever by default - check if we've reached end
    // openmpt doesn't have a built-in "ended" check, so we rely on position
    return JNI_FALSE;  // Tracker files typically loop forever
  }
  if (gPlayerType == PlayerType::LIBKSS && gKssPlay) {
    // KSS files can detect stop via KSSPLAY_get_stop_flag
    return KSSPLAY_get_stop_flag(gKssPlay) ? JNI_TRUE : JNI_FALSE;
  }
  if (gPlayerType == PlayerType::LIBADLMIDI && gAdlPlayer) {
    // libADLMIDI: check if position >= total length
    double position = adl_positionTell(gAdlPlayer);
    double total = adl_totalTimeLength(gAdlPlayer);
    if (total > 0 && position >= total) {
      return JNI_TRUE;
    }
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetEndlessLoop(
    JNIEnv *env, jclass cls, jboolean enabled) {
  gEndlessLoopMode = (enabled == JNI_TRUE);
  
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    // For GME, ignore silence to prevent track end detection
    gme_ignore_silence(gGmePlayer, enabled ? 1 : 0);
    // Also disable autoload playback limit - this prevents SPC files from
    // automatically fading out based on their embedded track length metadata
    gme_set_autoload_playback_limit(gGmePlayer, enabled ? 0 : 1);
  }
  // For VGM, the endless loop is handled by the gEndlessLoopMode flag in nIsEnded
}

JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetEndlessLoop(
    JNIEnv *env, jclass cls) {
  return gEndlessLoopMode ? JNI_TRUE : JNI_FALSE;
}

// Playback speed control (0.25 to 1.0 for 25% to 100%)
static double gPlaybackSpeed = 1.0;

JNIEXPORT void JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nSetPlaybackSpeed(
    JNIEnv *env, jclass cls, jdouble speed) {
  gPlaybackSpeed = speed;
  
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    gVgmPlayer->SetPlaybackSpeed(speed);
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    // gme_set_tempo uses tempo where 1.0 = normal, 2.0 = double speed
    gme_set_tempo(gGmePlayer, speed);
  }
  if (gPlayerType == PlayerType::LIBKSS && gKssPlay) {
    // KSSPLAY uses CPU speed multiplier (1.0 = normal, 2.0 = double speed)
    KSSPLAY_set_speed(gKssPlay, speed);
  }
  // libopenmpt doesn't have a direct speed control API
}

JNIEXPORT jdouble JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetPlaybackSpeed(
    JNIEnv *env, jclass cls) {
  return gPlaybackSpeed;
}

JNIEXPORT jlong JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTotalSamples(JNIEnv *env,
                                                         jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    // VGM files have accurate length from GD3 tags, use directly
    return (jlong)gVgmPlayer->Tick2Sample(gVgmPlayer->GetTotalTicks());
  }
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    gme_info_t *info;
    if (gme_track_info(gGmePlayer, &info, gGmeTrackIndex) == 0) {
      int length_ms = info->play_length;
      int intro_ms = info->intro_length;
      int loop_ms = info->loop_length;
      
      // Use intro + 2 loops for better estimate if available
      if (intro_ms > 0 && loop_ms > 0) {
        length_ms = intro_ms + loop_ms * 2;
      }
      
      // If length is unreasonably short (< 30 seconds), default to 3 minutes
      if (length_ms < 30000) {
        length_ms = 180000;
      }
      
      gme_free_info(info);
      // Cast to jlong BEFORE multiplication to avoid integer overflow
      return (jlong)length_ms * gSampleRate / 1000;
    }
  }
  if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    // Tracker modules don't have a fixed duration - they loop
    // Return a reasonable default (3 minutes)
    return (jlong)180 * gSampleRate;
  }
  if (gPlayerType == PlayerType::LIBKSS && gKss) {
    // KSS files may have info about track duration
    // Check if current track has info
    if (gKss->info && gKss->info_num > 0) {
      for (uint16_t i = 0; i < gKss->info_num; i++) {
        if (gKss->info[i].song == gKssTrackIndex && gKss->info[i].time_in_ms > 0) {
          return (jlong)gKss->info[i].time_in_ms * gSampleRate / 1000;
        }
      }
    }
    // Default to 3 minutes for KSS files
    return (jlong)180 * gSampleRate;
  }
  if (gPlayerType == PlayerType::LIBADLMIDI && gAdlPlayer) {
    // MIDI files have variable length - get from libADLMIDI
    double totalSeconds = adl_totalTimeLength(gAdlPlayer);
    if (totalSeconds > 0) {
      return (jlong)(totalSeconds * gSampleRate);
    }
    // Default to 3 minutes if duration unknown
    return (jlong)180 * gSampleRate;
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
    // Cast to jlong BEFORE multiplication to avoid integer overflow
    return (jlong)ms * gSampleRate / 1000;
  }
  if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    double seconds = openmpt_module_get_position_seconds(gOpenmptModule);
    return (jlong)(seconds * gSampleRate);
  }
  if (gPlayerType == PlayerType::LIBKSS && gKssPlay) {
    // KSS doesn't have a direct position function
    // We track position via decoded_length which is updated during calc
    // Return 0 for now - proper position tracking would need a timer
    return 0;
  }
  if (gPlayerType == PlayerType::LIBADLMIDI && gAdlPlayer) {
    double positionSeconds = adl_positionTell(gAdlPlayer);
    return (jlong)(positionSeconds * gSampleRate);
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
  if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    double seconds = (double)samplePos / gSampleRate;
    openmpt_module_set_position_seconds(gOpenmptModule, seconds);
  }
  if (gPlayerType == PlayerType::LIBADLMIDI && gAdlPlayer) {
    double seconds = (double)samplePos / gSampleRate;
    adl_positionSeek(gAdlPlayer, seconds);
  }
  // KSS doesn't support seeking - would need to reset and render silent
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
  } else if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    // libopenmpt outputs stereo interleaved
    written = (jint)openmpt_module_read_interleaved_stereo(gOpenmptModule, gSampleRate, frames, dst);
    
    // Feed mono samples to FFT ring buffer
    for (jint i = 0; i < written; i++) {
      float sample = (float)dst[i * 2] / 32768.0f + (float)dst[i * 2 + 1] / 32768.0f;
      gFftRingBuffer[gFftWriteIdx] = sample / 2.0f;
      gFftWriteIdx = (gFftWriteIdx + 1) % FFT_SIZE;
    }
  } else if (gPlayerType == PlayerType::LIBKSS && gKssPlay) {
    // libkss outputs stereo interleaved 16-bit
    KSSPLAY_calc(gKssPlay, dst, frames);
    written = frames;
    
    // Debug: log first few samples occasionally
    static int kssLogCounter = 0;
    if (kssLogCounter++ % 500 == 0) {
      LOGD("KSS samples: L=%d R=%d, stop_flag=%d", 
           dst[0], dst[1], KSSPLAY_get_stop_flag(gKssPlay));
    }
    
    // Feed mono samples to FFT ring buffer
    for (jint i = 0; i < written; i++) {
      float sample = (float)dst[i * 2] / 32768.0f + (float)dst[i * 2 + 1] / 32768.0f;
      gFftRingBuffer[gFftWriteIdx] = sample / 2.0f;
      gFftWriteIdx = (gFftWriteIdx + 1) % FFT_SIZE;
    }
  } else if (gPlayerType == PlayerType::LIBADLMIDI && gAdlPlayer) {
    // libADLMIDI outputs stereo interleaved 16-bit
    // adl_play returns number of samples rendered (stereo pairs)
    int samplesRendered = adl_play(gAdlPlayer, frames * 2, dst);
    if (samplesRendered > 0) {
      written = samplesRendered / 2;  // Convert sample count to frame count
      
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
 * Convert UTF-16LE to UTF-8.
 * Simple implementation for Android where iconv is not available.
 * Handles BMP characters (U+0000 to U+FFFF).
 */
static std::string utf16le_to_utf8(const UINT8* data, size_t byteLen) {
  std::string result;
  const UINT8* ptr = data;
  const UINT8* end = data + byteLen;
  
  while (ptr + 1 < end) {
    UINT16 codeUnit = ptr[0] | (ptr[1] << 8);  // UTF-16LE
    ptr += 2;
    
    if (codeUnit == 0) break;  // null terminator
    
    if (codeUnit < 0x80) {
      // 1-byte UTF-8
      result += (char)codeUnit;
    } else if (codeUnit < 0x800) {
      // 2-byte UTF-8
      result += (char)(0xC0 | (codeUnit >> 6));
      result += (char)(0x80 | (codeUnit & 0x3F));
    } else {
      // 3-byte UTF-8 (BMP characters)
      result += (char)(0xE0 | (codeUnit >> 12));
      result += (char)(0x80 | ((codeUnit >> 6) & 0x3F));
      result += (char)(0x80 | (codeUnit & 0x3F));
    }
  }
  
  return result;
}

/**
 * Read GD3 tags directly from VGM file data.
 * This bypasses libvgm's GetTags() which relies on iconv (not available on Android).
 * Returns a string in the format: "KEY1|||VALUE1|||KEY2|||VALUE2|||..."
 */
static std::string readVgmGd3Tags(const UINT8* fileData, const VGM_HEADER* hdr) {
  if (!hdr->gd3Ofs || hdr->gd3Ofs >= hdr->eofOfs) {
    return "";
  }
  
  // Check GD3 magic "Gd3 "
  if (memcmp(&fileData[hdr->gd3Ofs], "Gd3 ", 4) != 0) {
    return "";
  }
  
  // GD3 structure: "Gd3 " (4) + version (4) + data size (4) + data
  UINT32 dataSize = *(UINT32*)(&fileData[hdr->gd3Ofs + 8]);
  UINT32 dataStart = hdr->gd3Ofs + 12;
  UINT32 dataEnd = dataStart + dataSize;
  
  if (dataEnd > hdr->eofOfs) {
    dataEnd = hdr->eofOfs;
  }
  
  // GD3 tag order (all UTF-16LE, null-terminated):
  // 0: Track title (English)
  // 1: Track title (Japanese)
  // 2: Game name (English)
  // 3: Game name (Japanese)
  // 4: System name (English)
  // 5: System name (Japanese)
  // 6: Artist (English)
  // 7: Artist (Japanese)
  // 8: Release date
  // 9: VGM creator (dumper)
  // 10: Notes
  
  const char* tagKeys[] = {
    "TITLE", "TITLE-JPN", "GAME", "GAME-JPN", "SYSTEM", "SYSTEM-JPN",
    "ARTIST", "ARTIST-JPN", "DATE", "ENCODED_BY", "COMMENT"
  };
  const int tagCount = 11;
  
  std::string result;
  UINT32 pos = dataStart;
  
  for (int i = 0; i < tagCount && pos < dataEnd; i++) {
    // Find null terminator for this string
    UINT32 start = pos;
    while (pos + 1 < dataEnd) {
      UINT16 ch = fileData[pos] | (fileData[pos + 1] << 8);
      pos += 2;
      if (ch == 0) break;
    }
    
    // Convert UTF-16LE to UTF-8
    std::string value = utf16le_to_utf8(&fileData[start], pos - start - 2);
    
    // Add to result
    result += tagKeys[i];
    result += "|||";
    result += value;
    result += "|||";
  }
  
  return result;
}

/**
 * Get track tags as a single string:
 * "TrkE|||TrkJ|||GmE|||GmJ|||SysE|||SysJ|||AutE|||AutJ|||..."
 */
JNIEXPORT jstring JNICALL
Java_org_vlessert_vgmp_engine_VgmEngine_nGetTags(JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBVGM && gVgmPlayer) {
    // Read GD3 tags directly from file data to bypass iconv dependency
    const VGM_HEADER* hdr = gVgmPlayer->GetFileHeader();
    UINT8* fileData = DataLoader_GetData(gLoader);
    
    if (hdr && fileData) {
      std::string tags = readVgmGd3Tags(fileData, hdr);
      return env->NewStringUTF(tags.c_str());
    }
    return env->NewStringUTF("");
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
  
  // Handle tracker formats via libopenmpt
  if (gPlayerType == PlayerType::LIBOPENMPT && gOpenmptModule) {
    std::string s;
    
    // TITLE
    s += "TITLE";
    s += "|||";
    const char *title = openmpt_module_get_metadata(gOpenmptModule, "title");
    s += title ? title : "";
    s += "|||";
    if (title) openmpt_free_string(title);
    
    // GAME (use message or tracker)
    s += "GAME";
    s += "|||";
    const char *message = openmpt_module_get_metadata(gOpenmptModule, "message");
    s += message ? message : "";
    s += "|||";
    if (message) openmpt_free_string(message);
    
    // GAME-JPN
    s += "GAME-JPN";
    s += "|||";
    s += "|||";
    
    // SYSTEM (tracker type)
    s += "SYSTEM";
    s += "|||";
    const char *tracker = openmpt_module_get_metadata(gOpenmptModule, "tracker");
    s += tracker ? tracker : "Tracker";
    s += "|||";
    if (tracker) openmpt_free_string(tracker);
    
    // SYSTEM-JPN
    s += "SYSTEM-JPN";
    s += "|||";
    s += "|||";
    
    // ARTIST
    s += "ARTIST";
    s += "|||";
    const char *artist = openmpt_module_get_metadata(gOpenmptModule, "artist");
    s += artist ? artist : "";
    s += "|||";
    if (artist) openmpt_free_string(artist);
    
    // ARTIST-JPN
    s += "ARTIST-JPN";
    s += "|||";
    s += "|||";
    
    // DATE
    s += "DATE";
    s += "|||";
    const char *date = openmpt_module_get_metadata(gOpenmptModule, "date");
    s += date ? date : "";
    s += "|||";
    if (date) openmpt_free_string(date);
    
    // ENCODED_BY
    s += "ENCODED_BY";
    s += "|||";
    s += "|||";
    
    // COMMENT
    s += "COMMENT";
    s += "|||";
    s += "|||";
    
    return env->NewStringUTF(s.c_str());
  }
  
  // Handle KSS format via libkss
  if (gPlayerType == PlayerType::LIBKSS && gKss) {
    std::string s;
    
    // TITLE - get from KSS title or track info
    s += "TITLE";
    s += "|||";
    const char *kssTitle = KSS_get_title(gKss);
    if (kssTitle && kssTitle[0]) {
      s += kssTitle;
    } else if (gKss->info && gKss->info_num > 0) {
      // Try to get track-specific title
      for (uint16_t i = 0; i < gKss->info_num; i++) {
        if (gKss->info[i].song == gKssTrackIndex) {
          s += gKss->info[i].title;
          break;
        }
      }
    }
    s += "|||";
    
    // TITLE-JPN
    s += "TITLE-JPN";
    s += "|||";
    s += "|||";
    
    // GAME - use KSS title as game name
    s += "GAME";
    s += "|||";
    s += kssTitle ? kssTitle : "";
    s += "|||";
    
    // GAME-JPN
    s += "GAME-JPN";
    s += "|||";
    s += "|||";
    
    // SYSTEM - MSX or Sega Master System
    s += "SYSTEM";
    s += "|||";
    if (gKss->mode == 0) {
      s += "MSX";
    } else if (gKss->mode == 1) {
      s += "Sega Master System";
    } else if (gKss->mode == 2) {
      s += "Sega Game Gear";
    } else {
      s += "MSX";  // Default
    }
    s += "|||";
    
    // SYSTEM-JPN
    s += "SYSTEM-JPN";
    s += "|||";
    s += "|||";
    
    // ARTIST
    s += "ARTIST";
    s += "|||";
    s += "|||";
    
    // ARTIST-JPN
    s += "ARTIST-JPN";
    s += "|||";
    s += "|||";
    
    // DATE
    s += "DATE";
    s += "|||";
    s += "|||";
    
    // ENCODED_BY
    s += "ENCODED_BY";
    s += "|||";
    s += "|||";
    
    // COMMENT
    s += "COMMENT";
    s += "|||";
    s += "|||";
    
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
    int intro_ms = info->intro_length;
    int loop_ms = info->loop_length;
    
    // Use intro + 2 loops for better estimate if available
    if (intro_ms > 0 && loop_ms > 0) {
      length_ms = intro_ms + loop_ms * 2;
    }
    
    // If length is unreasonably short (< 30 seconds), default to 3 minutes
    // SPC and NSF files typically loop and don't have a fixed duration
    if (length_ms < 30000) {
      length_ms = 180000; // 3 minutes default
    }
    
    gme_free_info(info);
    gme_delete(tempEmu);
    
    // Cast to jlong BEFORE multiplication to avoid integer overflow
    return (jlong)length_ms * gSampleRate / 1000;
  }

  // Use libvgm for VGM/VGZ - VGM files have accurate length from GD3 tags
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

  // VGM files have accurate length from GD3 tags, use directly
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
  // libgme doesn't support per-voice volume control, so return 0 to hide sliders
  // (gme_voice_count returns number of voices/channels, but they can't be controlled)
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
    gVgmPlayer->SetDeviceVolume(id, (UINT16)vol);
  }
  // libgme doesn't support per-device volume
}

// libgme-specific: get track count for multi-track files (NSF, GBS, etc.)
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackCount(
    JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBGME && gGmePlayer) {
    return gGmeTrackCount;
  }
  if (gPlayerType == PlayerType::LIBKSS && gKss) {
    return gKssTrackCount;
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
  if (gPlayerType == PlayerType::LIBKSS && gKssPlay && gKss) {
    // KSS track index passed from Kotlin is the actual KSS track number
    // (not a 0-based index) - use it directly
    int actualTrack = trackIndex;
    LOGD("nSetTrack: KSS request track %d (valid range: %d-%d)", 
         actualTrack, gKss->trk_min, gKss->trk_max);
    if (actualTrack >= gKss->trk_min && actualTrack <= gKss->trk_max) {
      KSSPLAY_reset(gKssPlay, actualTrack, 0);
      gKssTrackIndex = actualTrack;
      LOGD("nSetTrack: KSS track set to %d", actualTrack);
      return JNI_TRUE;
    }
    LOGE("nSetTrack: KSS track %d out of range", actualTrack);
  }
  return JNI_FALSE;
}

// libgme-specific: get current track index
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetCurrentTrack(
    JNIEnv *env, jclass cls) {
  if (gPlayerType == PlayerType::LIBKSS && gKss) {
    // Return actual KSS track number (not 0-based index)
    return gKssTrackIndex;
  }
  return gGmeTrackIndex;
}

// Check if file is a multi-track format (NSF, GBS, KSS, etc.)
JNIEXPORT jboolean JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nIsMultiTrack(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  bool result = isGmeFormat(path) || isKssFormat(path);
  env->ReleaseStringUTFChars(jpath, path);
  return result ? JNI_TRUE : JNI_FALSE;
}

// Get track length for a specific track index (for multi-track files like NSF)
JNIEXPORT jlong JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackLength(
    JNIEnv *env, jclass cls, jstring jpath, jint trackIndex) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);

  // Check if this is a libgme format
  if (isGmeFormat(path)) {
    Music_Emu *tempEmu;
    gme_err_t err = gme_open_file(path, &tempEmu, gSampleRate);
    env->ReleaseStringUTFChars(jpath, path);
    
    if (err || !tempEmu) {
      return 0;
    }
    
    int trackCount = gme_track_count(tempEmu);
    int actualTrackIndex = (trackIndex >= 0 && trackIndex < trackCount) ? trackIndex : 0;
    
    gme_info_t *info;
    if (gme_track_info(tempEmu, &info, actualTrackIndex) != 0) {
      gme_delete(tempEmu);
      return 0;
    }
    
    // For NSF/SPC files, play_length is often a default or incorrect value
    // intro_length + loop_length gives more accurate estimate if available
    int length_ms = info->play_length;
    int intro_ms = info->intro_length;
    int loop_ms = info->loop_length;
    
    // Use intro + 2 loops for better estimate if available
    if (intro_ms > 0 && loop_ms > 0) {
      length_ms = intro_ms + loop_ms * 2;
    }
    
    // If length is unreasonably short (< 30 seconds), default to 3 minutes
    // SPC and NSF files typically loop and don't have a fixed duration
    if (length_ms < 30000) {
      length_ms = 180000; // 3 minutes default
    }
    
    gme_free_info(info);
    gme_delete(tempEmu);
    
    // Cast to jlong BEFORE multiplication to avoid integer overflow
    return (jlong)length_ms * gSampleRate / 1000;
  }

  // For VGM files, use the regular function (track index is ignored)
  env->ReleaseStringUTFChars(jpath, path);
  return Java_org_vlessert_vgmp_engine_VgmEngine_nGetTrackLengthDirect(env, cls, jpath);
}

// Get KSS track count directly from file path (without opening as active track)
JNIEXPORT jint JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetKssTrackCountDirect(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  
  if (!isKssFormat(path)) {
    env->ReleaseStringUTFChars(jpath, path);
    return 1; // Not a KSS file, return 1 track
  }
  
  // Get filename for KSS_bin2kss
  const char *filename = strrchr(path, '/');
  filename = filename ? filename + 1 : path;
  
  // Read file into memory
  FILE *f = fopen(path, "rb");
  env->ReleaseStringUTFChars(jpath, path);
  
  if (!f) {
    LOGE("Failed to open KSS file for track count");
    return 1;
  }
  
  fseek(f, 0, SEEK_END);
  long fileSize = ftell(f);
  fseek(f, 0, SEEK_SET);
  
  std::vector<uint8_t> fileData(fileSize);
  fread(fileData.data(), 1, fileSize, f);
  fclose(f);
  
  // Create temporary KSS object using KSS_bin2kss which properly parses headers
  KSS *kss = KSS_bin2kss(fileData.data(), fileSize, filename);
  if (!kss) {
    LOGE("Failed to create KSS object for track count");
    return 1;
  }
  
  int trackCount = kss->trk_max - kss->trk_min + 1;
  int trkMin = kss->trk_min;
  int trkMax = kss->trk_max;
  
  KSS_delete(kss);
  
  LOGD("nGetKssTrackCountDirect: %d tracks (min=%d, max=%d)", trackCount, trkMin, trkMax);
  return trackCount;
}

// Get KSS track range (min and max track numbers)
JNIEXPORT jintArray JNICALL Java_org_vlessert_vgmp_engine_VgmEngine_nGetKssTrackRange(
    JNIEnv *env, jclass cls, jstring jpath) {
  const char *path = env->GetStringUTFChars(jpath, nullptr);
  
  jintArray result = env->NewIntArray(2);
  if (!result) {
    env->ReleaseStringUTFChars(jpath, path);
    return nullptr;
  }
  
  jint defaults[] = {1, 1}; // Default: track 1 only
  env->SetIntArrayRegion(result, 0, 2, defaults);
  
  if (!isKssFormat(path)) {
    env->ReleaseStringUTFChars(jpath, path);
    return result;
  }
  
  // Get filename for KSS_bin2kss
  const char *filename = strrchr(path, '/');
  filename = filename ? filename + 1 : path;
  
  // Read file into memory
  FILE *f = fopen(path, "rb");
  env->ReleaseStringUTFChars(jpath, path);
  
  if (!f) {
    return result;
  }
  
  fseek(f, 0, SEEK_END);
  long fileSize = ftell(f);
  fseek(f, 0, SEEK_SET);
  
  std::vector<uint8_t> fileData(fileSize);
  fread(fileData.data(), 1, fileSize, f);
  fclose(f);
  
  // Create temporary KSS object using KSS_bin2kss which properly parses headers
  KSS *kss = KSS_bin2kss(fileData.data(), fileSize, filename);
  if (!kss) {
    return result;
  }
  
  jint range[] = {kss->trk_min, kss->trk_max};
  env->SetIntArrayRegion(result, 0, 2, range);
  
  KSS_delete(kss);
  
  return result;
}

} // extern "C"
