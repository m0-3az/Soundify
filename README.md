# Soundify

**Bring your stems. Mix them anywhere. Or remove the music from a video or audio file.**

Soundify is a free, offline Android app with three modes:

- **Stem Mixer** — play 2–8 stems you already have, with gain, pan, mute, solo,
  offset, looping and WAV export.
- **Clean Media (beta)** — pick an audio or video file, remove its music
  on-device, keep voice and sound effects, then export cleaned audio (MP3 or
  M4A) or, when the source has a video track, a cleaned video with the original
  video stream copied.
- **Audio editor** — import one audio file, trim it on a waveform, split it,
  fade it, set clip volume, reorder clips and export a new file without ever
  modifying the original.

Clean Media uses a bundled on-device model. The app does **not** request the
`INTERNET` permission, and no audio or video leaves the phone.

## Download

Get the latest APK from the [Releases](https://github.com/m0-3az/Soundify/releases)
page. The release APK includes the separation model.

Release APKs are signed with a dedicated release key. Builds from before
0.6.0 used the debug key, so upgrading from one of those requires a one-time
uninstall (app data is removed).

### Update with Obtainium

1. Install [Obtainium](https://github.com/ImranR98/Obtainium).
2. Add the repository URL: `https://github.com/m0-3az/Soundify`.
3. Enable update notifications for the app.

Obtainium checks GitHub Releases; Soundify itself stays offline and has no
network permission. Updates still require the Android install confirmation.

## Screenshots

| Library | Mixer |
|---|---|
| ![Library](docs/screenshots/library.png) | ![Mixer](docs/screenshots/mixer.png) |

## Status

- Stem Mixer: MVP complete and verified on a physical phone.
- Clean Media: beta. End-to-end on-device tests pass for 12 s and 60 s clips.
  A 20-minute video is processed in roughly 29 minutes on a Samsung SM-A576B;
  the pipeline streams chunks and writes stems to disk, so memory stays bounded.
- Audio editor: 0.6.0. Import, trim, split, fade, volume, clip reorder,
  preview and WAV/FLAC/MP3/M4A export are implemented and covered by 187 JVM
  and 49 device tests.
- Open issues: separation quality varies by mix, and Clean Media currently
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

### Clean Media

- Import any audio or video file through the system file picker
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
- Put a track back into the video, copying the original video stream; video
  saves are hidden for audio-only sources, including audio with cover art
- MP4 is offered only for H.264/HEVC/AV1/MPEG-4 video; MKV is the fallback for
  other codecs (VP8/VP9, MPEG-2, Theora, unknown) and WebM only for VP8/VP9/AV1
- Clear errors for files without an audio track and for FFmpeg failures

### Audio editor

- Import one audio file through the system picker; the app keeps its own copy
  and never modifies the picked original
- Waveform view with draggable trim handles and exact millisecond fields
- Split at the playhead, fade in/out, and per-clip volume from −24 to +24 dB
- Clip list: add, reorder, select and delete clips; undo/redo (50 steps)
- Preview playback with trim-end stop, seek and a project timeline
- Export WAV, FLAC, MP3 or M4A at 44.1 kHz stereo through the system document
  picker, with progress and Cancel
- Fades, crossfade and effects are applied on export; preview is approximate
- Future-schema projects open read-only so a newer version's data is not
  rewritten

## Why

- **Free:** no ads, subscriptions, accounts or in-app purchases.
- **Offline:** no `INTERNET` permission; the model and FFmpeg are bundled.
- **Private:** your media never leaves the device.
- **Local AI:** one small on-device model, no cloud processing, no tracking.

## Limitations

- Clean Media quality depends on the mix. Music with vocals, heavy compression,
  or loud sound effects can bleed between stems.
- Near-silent clean mixes are kept at their existing level instead of being
  amplified into separation noise; the result screen explains why.
- A 20-minute video takes roughly 29 minutes on the tested phone. Processing
  continues in the background with a notification.
- The Audio editor is single-track and clip-based, not a multi-track timeline.
  Recording, merge, batch convert and real-time effects are later releases.
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

For a release build, provide the signing material through environment
variables (never commit it):

```bash
export SOUNDIFY_KEYSTORE=/path/to/soundify-release.jks
export SOUNDIFY_KEYSTORE_PASSWORD_FILE=/path/to/storepass.txt
export SOUNDIFY_KEY_PASSWORD_FILE=/path/to/storepass.txt
export SOUNDIFY_KEY_ALIAS=soundify
./gradlew assembleRelease
```

The signed APK is written to:

```
app/build/outputs/apk/release/app-release.apk
```

Without those variables the release build is unsigned, so a debug key can
never silently ship. See [docs/releases/README.md](docs/releases/README.md) for
the full release checklist.

## Test

```bash
./gradlew testDebugUnitTest
./gradlew connectedDebugAndroidTest   # requires a device or emulator
```

The instrumentation tests include synthetic short and 60-second videos, so the
separation pipeline is exercised without copyrighted sample media.

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or copy the APK to a device and open it. A build signed with the release key
cannot update a debug-signed build; uninstall the old app once. After that,
every release keeps the same key and updates in place.

## Usage

### Stem Mixer

1. Tap **New project**.
2. Choose 2–8 stem files.
3. Confirm the project name and import.
4. Play the project and adjust gain, pan, mute and solo.
5. Toggle **Loop** to repeat a section.
6. Tap **Export WAV** to render the current mix.

### Clean Media

1. On the library screen, tap **Clean Media**.
2. Choose an audio or video file. Files without an audio track are rejected with
   a clear message.
3. Tap **Clean** and keep the app open.
4. Play the cleaned result, then save the MP3, M4A or, for a video source, the
   cleaned video.
5. Tap **Delete stems** to remove the four outputs from the app. Files you saved
   to another folder are kept. Cancelling a run also removes its partial stems.

The result screen shows the levels you will get when saving: voice, sound
effects and music are restored by +6 dB on export, while the clean mix is
already normalized.

### Audio editor

1. On the library screen, tap **Audio editor**.
2. Tap **New edit** and choose one audio file. The app copies it; the original
   is never modified.
3. Drag the waveform handles or type exact start/end milliseconds.
4. Use **Split** at the playhead, **Fade** and **Volume** on the selected clip.
5. Add more clips, reorder them, and select a clip from the list to edit it.
6. Tap **Save audio**, choose a format and name, then pick a destination.
7. Tap **Export**; progress and Cancel stay visible while it runs.

Exports are always 44.1 kHz stereo. Fades, crossfade and effects are applied on
export; preview is approximate. Deleting an edit removes only the app's copy.

## Project structure

```
app/src/main/java/com/soundify/app/
  data/         Project and stem models, local store, role mapping
  audio/        MediaCodec decoders, ring buffer, mixer, WAV writer
  cleanvideo/   STFT/iSTFT, ONNX mask model, streaming cleaner, FFmpeg
  editor/       Editor model/store/importer, waveform, player, exporter
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
