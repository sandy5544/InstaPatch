# Auralis Player

A standalone Android music player project inside the InstaPatch repository. Existing patch-source files are preserved and untouched.

## Current implementation

- Android app using Media3 ExoPlayer and MediaSessionService for background playback and Android media controls.
- Local audio import through Android's Storage Access Framework.
- Queue, previous/next, play/pause, shuffle and repeat-one controls.
- In-app provider connection using an HTTPS base URL and optional secret token.
- Provider manifest discovery, track search, result listing and playback of returned HTTPS stream URLs.
- Optional LastWave-compatible HMAC-SHA256 request headers in the provider transport.
- Stream metadata fields for MIME type, bitrate and sample rate.
- GitHub Actions debug APK build and a dedicated Auralis GitHub Release.

## Provider compatibility

A provider must expose `/manifest.json` and `/search?q=<encoded query>`. Search responses may use a `results` or `tracks` array. Each result should include `id`, `title`, `artist`, and a directly playable HTTPS `url` or `streamUrl`. Optional fields include `album`, `duration`, `mimeType`, `bitrate`, and `sampleRate`.

When a provider requires LastWave request signing, enter its token in the provider token field. The app signs GET requests with HMAC-SHA256 using the LastWave header convention. Tokens are kept in memory only and are not written to disk.

## Build

Use Android Studio with JDK 17 and Gradle 8.11.1. Open this directory as a standalone project, or run `gradle :app:assembleDebug` from this directory.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

## Known limitations

This is an independent Android implementation, not a direct source-code merge of BitChord's Electron renderer and LastWave's Android application. BitChord's YouTube Music discovery, signed-in account/library flows, LastWave's native YouTube stream extractor, encrypted .lwp module runtime, synced lyrics, downloads, audio effects, USB-exclusive output, beat-grid Automix and bit-perfect output verification are not yet integrated. The provider screen currently requires providers to return a direct HTTPS stream URL; it does not execute provider scripts or decrypt proprietary module packages.

The app must never label a stream lossless solely because a provider was asked for FLAC. Actual delivered format and device output must be inspected.

## License

The new player source is licensed GPL-3.0-or-later to align with this repository's existing license.
