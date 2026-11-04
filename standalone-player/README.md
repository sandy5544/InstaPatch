# Auralis Player
A standalone Android music player project inside the InstaPatch repository. Existing patch-source files are preserved and untouched.

## Implemented
- Android app using Media3 ExoPlayer and MediaSessionService for background-capable playback.
- Local audio import through Android's Storage Access Framework.
- Queue, previous/next, play/pause, shuffle and repeat-one.
- HTTPS-only addon transport with manifest discovery, search parsing and stream resolution.
- Optional LastWave-compatible HMAC-SHA256 request headers.
- Normalized stream metadata model and explicit quality states.
- GitHub Actions debug APK build.

## Build
Use Android Studio with JDK 17 and Gradle 8.11.1. Open this directory as a standalone project, or run `gradle :app:assembleDebug` from this directory.
APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Honest capability notes
This is an independent implementation, not copied source from BitChord or LastWave. Local playback and the MediaSession foundation are implemented. The addon client is a reusable protocol layer but is not yet connected to a complete in-app addon catalogue/search screen. USB-exclusive output, native DSP, beat-grid Automix and bit-perfect verification are not implemented in this first build. The app must never label a stream lossless solely because a provider was asked for FLAC; actual delivered format and device output must be inspected.

Providers must be configured by the user and must be authorized to serve the audio. No service credentials or private signing secrets are bundled in the APK.

## License
The new player source is licensed GPL-3.0-or-later to align with this repository's existing license.
