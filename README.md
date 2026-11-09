# Soundify

**Bring your stems. Mix them anywhere. Or remove the music from a video.**

Soundify is a free, offline Android app with two modes:

- **Stem Mixer** — play 2–8 stems you already have, with gain, pan, mute, solo,
  offset, looping and WAV export.
- **Clean Video (beta)** — pick a video, remove its music on-device, keep voice
  and sound effects, then export cleaned audio (MP3 or M4A) or a cleaned video
  with the original video stream copied.

Clean Video uses a bundled on-device model. The app does **not** request the
`INTERNET` permission, and no audio or video leaves the phone.

## Download

Get the latest APK from the [Releases](https://github.com/m0-3az/Soundify/releases)
page. The release APK includes the separation model.

## Screenshots

| Library | Mixer |
|---|---|
| ![Library](docs/screenshots/library.png) | ![Mixer](docs/screenshots/mixer.png) |

## Status

- Stem Mixer: MVP complete and verified on a physical phone.
- Clean Video: beta. End-to-end on-device tests pass for 12 s and 60 s clips.
  A 20-minute video is processed in roughly 29 minutes on a Samsung SM-A576B;
  the pipeline streams chunks and writes stems to disk, so memory stays bounded.
- Open issues: separation quality varies by mix, and Clean Video currently
  exports a cleaned mix rather than an interactive three-stem mixer.

## Features

### Stem Mixer

- Import 2–8 audio files through the system file picker
- WAV, FLAC, MP3, M4A/AAC at 44.1 kHz or 48 kHz
- Automatic channel-role detection from filenames
- Sample-aligned playback with one transport clock
- Per-stem gain, pan, mute and solo
- Per-stem offset nudge for compressed stems with decoder delay
- Loop region for practice
- Background playback with a media notification
- Local project library with saved mix state
- WAV export (16-bit PCM)

### Clean Video

- Import any video through the system file picker
- Extract audio at its original level to 44.1 kHz stereo float
- Separate music / voice / sound effects with the bundled MRX model
- Match the cleaned mix to -16 LUFS / -1.5 dBTP after separation; near-silent
  mixes keep their existing level instead of being boosted into noise
- Stream 6-second chunks to disk so 20-minute videos do not exhaust RAM
- Four outputs: voice, sound effects, music and clean mix, each with playback and a level control
- Foreground service with a progress notification, chunk count, ETA and Cancel
- Cancelling or failing removes the partial stem files automatically
- **Delete stems** removes the four outputs from the app without touching files you saved
- Save one track as WAV, FLAC, MP3 or M4A through the system document picker
- Put a track back into the video, copying the original video stream
- MP4 is offered only for H.264/HEVC/AV1/MPEG-4 video; MKV is the fallback for
  other codecs (VP8/VP9, MPEG-2, Theora, unknown) and WebM only for VP8/VP9/AV1
- Clear errors for videos without an audio track and for FFmpeg failures

## Why

- **Free:** no ads, subscriptions, accounts or in-app purchases.
- **Offline:** no `INTERNET` permission; the model and FFmpeg are bundled.
- **Private:** your media never leaves the device.
- **Local AI:** one small on-device model, no cloud processing, no tracking.

## Limitations

- Clean Video quality depends on the mix. Music with vocals, heavy compression,
  or loud sound effects can bleed between stems.
- Near-silent clean mixes are kept at their existing level instead of being
  amplified into separation noise; the result screen explains why.
- A 20-minute video takes roughly 29 minutes on the tested phone. Processing
  continues in the background with a notification.
- The release APK is currently signed with the debug key (personal build). Future
  releases must keep the same signing key to update in place.
- Stem Mixer requires all stems in a project to share the same sample rate.
- MP3/AAC stems may need a manual offset because of encoder delay.

## Build

Requirements:

- JDK 17
- Android SDK 35
- Gradle 8.9 (the wrapper is included)
- The separation model, which is not stored in git because it is 117 MB

Download `mrx_mask.onnx` from the latest release assets and place it here:

```
app/src/main/assets/mrx_mask.onnx
```

Then:

```bash
./gradlew assembleDebug
```

The APK is written to:

```
app/build/outputs/apk/debug/app-debug.apk
```

For a release build:

```bash
./gradlew assembleRelease
```

The release APK is signed with the local debug key for now.

## Test

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # requires a device or emulator
```

The instrumentation tests include synthetic short and 60-second videos, so the
separation pipeline is exercised without copyrighted sample media.

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or copy the APK to a device and open it.

## Usage

### Stem Mixer

1. Tap **New project**.
2. Choose 2–8 stem files.
3. Confirm the project name and import.
4. Play the project and adjust gain, pan, mute and solo.
5. Toggle **Loop** to repeat a section.
6. Tap **Export WAV** to render the current mix.

### Clean Video

1. On the library screen, tap **Clean Video**.
2. Choose a video. Videos without an audio track are rejected with a clear message.
3. Tap **Clean video** and keep the app open.
4. Play the cleaned result, then share the MP3, M4A or cleaned video.
5. Tap **Delete stems** to remove the four outputs from the app. Files you saved
   to another folder are kept. Cancelling a run also removes its partial stems.

The result screen shows the levels you will get when saving: voice, sound
effects and music are restored by +6 dB on export, while the clean mix is
already normalized.

## Project structure

```
app/src/main/java/com/soundify/app/
  data/         Project and stem models, local store, role mapping
  audio/        MediaCodec decoders, ring buffer, mixer, WAV writer
  cleanvideo/   STFT/iSTFT, ONNX mask model, streaming cleaner, FFmpeg
  ui/           Compose screens and theme
```

## Privacy

Soundify does not collect, transmit or sell data. It has no network permission.
Imported media and exports live in app-private storage and are removed when you
delete a project or uninstall the app.

## License

MIT. See [LICENSE](LICENSE).

Third-party components and model notices are listed in
[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md).

## Contributing

Issues and pull requests are welcome. Please keep the app free, offline and
private by default.
