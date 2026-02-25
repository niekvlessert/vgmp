# VGMP — Video Game Music Player for Android

VGMP is a fast, offline‑first Android player for video game music formats. It scans local packs and you can search the whole VGMRips archive and browse SNESMusic.org to download music into the player. It plays a wide range of chip‑tune, console, and tracker formats with smooth playback and a visual spectrum analyzer.

## Features
- Wide format support (VGM/VGZ, NSF/GBS/SPC, KSS, trackers, MIDI, Doom MUS/LMP, RSN)
- Offline library with favorites, search, and shuffle/loop modes, slow down to play along
- VGM Rips downloader with search and chip filters
- Spectrum analyzer with multiple styles
- Bluetooth media controls (AVRCP‑style playback/seek/metadata)
- Android Auto/MediaSession integration

## Supported Formats
VGMP supports the following file types (case‑insensitive):
- **VGM/VGZ**: `.vgm`, `.vgz`
- **GME formats**: `.nsf`, `.nsfe`, `.gbs`, `.gym`, `.hes`, `.ay`, `.sap`, `.spc`
- **KSS/MSX**: `.kss`, `.mgs`, `.bgm`, `.opx`, `.mpk`, `.mbm`
- **Tracker**: `.mod`, `.xm`, `.s3m`, `.it`, `.mptm`, `.stm`, `.far`, `.ult`, `.med`, `.mtm`, `.psm`, `.amf`, `.okt`, `.dsm`, `.dtm`, `.umx`
- **MIDI**: `.mid`, `.midi`, `.rmi`, `.smf`
- **Doom MUS/LMP**: `.mus`, `.lmp`
- **RSN (SPC archives)**: `.rsn`

## Tech Stack
- Kotlin + AndroidX
- Native engines via CMake + NDK
- Room database for library
- WorkManager for downloads

## Build Requirements
- Android Studio (or command line tools) with JDK 17
- Android SDK (compile/target SDK 35)
- NDK + CMake (configured by the project)
- Git submodules initialized

## Setup
```bash
git submodule update --init --recursive
```

## Build
Debug APK:
```bash
./gradlew assembleDebug
```

Release APK:
```bash
./gradlew assembleRelease
```

The APKs will be in `app/build/outputs/apk/`.

## Usage
1. Launch VGMP.
2. Some packs are bundled with the apk, or tap the download button to fetch packs from VGM Rips.
3. Search and play from the library.
4. Use Settings to choose spectrum analyzer style and which VGM types are shown/playable.

## Settings
- **Analyzer**: enable/disable and select style
- **VGM types**: choose which formats appear in the library and are playable
- **Playback**: favorites‑only mode, loop/shuffle, etc.

## Bluetooth / Media Controls
VGMP exposes a MediaSession, so Bluetooth head units and controllers can use play/pause/next/previous/seek and see track metadata.

## Project Structure
- `app/src/main/java/org/vlessert/vgmp` — app code
- `app/src/main/cpp` — native audio engines + JNI
- `app/src/main/res` — UI resources

## Credits
VGMP integrates several excellent open‑source audio engines:
- libvgm
- Game Music Emu (libgme)
- libopenmpt
- libkss
- libADLMIDI
- libMusDoom

## License
MIT

## Contributing
Issues and PRs are welcome once the repository is public.
