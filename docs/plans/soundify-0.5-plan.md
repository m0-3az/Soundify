# Soundify 0.5.0 — Clean Media audio input

Status: in progress
Baseline: 0.4.0, commit 5802a8c
Branch: `feat/clean-media-audio`
Worktree: `/run/media/neo/Local Disk/Projects/Soundify-worktrees/0.5`

0.5.0 ships before any audio-editor work. The editor track (0.6.0–1.0.0) is
documented separately and must not be started until 0.5.0 is released.

## Why the order changed

The original 0.5.0 order is: six-clip context experiment, checkpoint
bake-off, residual tuning, then audio input. The first three need the six
Arabic Reels and the owner's ears; neither is available in the repository.
The audio-input slice is independent of chunking and model choice, so it is
executed first while those owner gates wait. This does not change what 0.5.0
ships.

## Global constraints

- Offline: no `INTERNET` permission, no cloud, no accounts, no tracking.
- Free and ad-free; MIT app code; debug-signed release APK.
- No new permission in 0.5.0. `RECORD_AUDIO` belongs to 0.9.0.
- No new native library and no new model asset.
- Do not port BandIt. Do not rerun the 37-clip corpus.
- MRX quality work uses only the six Arabic Reels.
- Preserve user data: sources are never modified; saved SAF files are never
  deleted by cleanup.
- Clean Media's audio save formats stay WAV, FLAC, MP3 192 kbps and M4A
  192 kbps. The audio Save sheet itself is unchanged.
- Stem Mixer and existing project flows are untouched.
- API 26 minimum, compile/target 35, JDK 17.
- Existing 69 JVM and 21 instrumented tests must stay green.

## Owner gates

These are not implementable in this worktree until the owner supplies the
inputs. They stay open in the ledger.

- **G1 — Six-clip context experiment.** Needs the six Arabic Reels.
  Compare: 6 s chunk / 1 s overlap baseline, whole-file at ≤15 s,
  15 s chunks / 2 s overlap. Produce A/B previews. No app change until the
  owner listens.
- **G2 — Official MRX checkpoint bake-off.** Needs the official checkpoints
  and the owner's listening. Swap `app/src/main/assets/mrx_mask.onnx` only if
  one wins.
- **G3 — Residual / mask-consistency tuning.** Needs G1 and G2 results.
- **G4 — 0.4.1 decision.** If G1 wins, implement whole-file or 15 s
  processing as a separate patch release before 0.5.0.

## Task 1: Clean Media accepts audio input end-to-end

### Goal

The existing Clean Video screen accepts an audio file as well as a video,
separates it with the same MRX pipeline, and offers only audio saves when the
source has no video track. Cover art must not count as a video track.

### Files

New:
- `app/src/main/java/com/soundify/app/cleanvideo/MediaProbe.kt`
- `app/src/test/java/com/soundify/app/cleanvideo/MediaProbeTest.kt`

Modified:
- `app/src/main/java/com/soundify/app/cleanvideo/StreamingCleaner.kt`
- `app/src/main/java/com/soundify/app/cleanvideo/CleanVideoExporter.kt`
- `app/src/main/java/com/soundify/app/cleanvideo/CleanVideoHistoryStore.kt`
- `app/src/main/java/com/soundify/app/CleanVideoViewModel.kt`
- `app/src/main/java/com/soundify/app/ui/CleanVideoScreen.kt`
- `app/src/main/java/com/soundify/app/ui/LibraryScreen.kt`
- `app/src/test/java/com/soundify/app/cleanvideo/CleanVideoHistoryStoreTest.kt`
- `README.md`

### Model changes

1. New `MediaProbe.kt`:

```kotlin
data class MediaStreamInfo(
    val type: String?,
    val codec: String?,
    val attachedPic: Boolean,
)

data class MediaProbe(
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val videoCodec: String?,
    val durationMs: Long,
) {
    companion object {
        /**
         * A video stream counts only when it is not cover art. An audio file
         * with an embedded JPEG must behave as audio-only.
         */
        fun classify(streams: List<MediaStreamInfo>, durationMs: Long): MediaProbe
    }
}
```

`classify` rules:
- `hasAudio` is true when any stream has `type == "audio"`.
- A real video stream is `type == "video" && !attachedPic`.
- `hasVideo` is true when at least one real video stream exists.
- `videoCodec` is the first real video stream's codec, else `null`.
- `durationMs` is passed through.

2. `StreamingCleaner.kt`:
- Replace `VideoProbe` with `MediaProbe`.
- `probeVideo` maps FFprobe streams:

```kotlin
private fun StreamInformation.isAttachedPic(): Boolean =
    runCatching {
        getProperty("disposition")?.optInt("attached_pic", 0) == 1
    }.getOrDefault(false)
```

- Probe failure keeps the 0.4 behavior: `hasAudio = true`, `hasVideo = true`,
  `videoCodec = null`, `durationMs = 0`. Never block a user because probing
  failed.
- Message change: `"This file has no audio track."`
- Pass `hasVideo = probe.hasVideo` into the `CleanVideoResult` constructor.
- The extraction command already uses `-vn`; no change needed.

3. `CleanVideoResult` (same file) gets `val hasVideo: Boolean = true` as the
   last constructor parameter, after `cleanNote`. The default preserves every
   existing call site and every test fixture.

4. `CleanVideoHistoryEntry` gets `val hasVideo: Boolean = true` as the last
   parameter. Old `history.json` files decode with `true`, so an old video
   result keeps offering video save.
- `entryFor` stores `result.hasVideo`.
- `CleanVideoViewModel.resultFor` restores `hasVideo = entry.hasVideo`.

5. `CleanVideoExporter.exportVideo` starts with:

```kotlin
require(result.hasVideo) { "This file has no video track." }
```

### UI changes

1. Picker MIME types become `arrayOf("audio/*", "video/*")` in
   `CleanVideoScreen.kt`.
2. Copy:
   - top bar and library card: `Clean Media`
   - library subtitle: `Remove music from audio or video`
   - screen description: `Remove music from an audio or video file while
     keeping voice and sound effects. Everything runs on this device; no
     internet is used.`
   - picker button: `Choose audio or video` / `Choose another file`
   - the pre-run action stays `Clean`
3. `SaveSheet` takes `hasVideo: Boolean`:
   - when `hasVideo` is false, omit the `Video` choice entirely and show
     `This file has no video track, so only audio can be saved.`
   - audio choices are unchanged.
4. `saveVideo` is not reachable for audio-only results. Keep the exporter
   `require` as the second line of defense.
5. Result copy: `Four on-device outputs` stays; add a small
   `Source: audio` / `Source: video` label only if it fits without layout
   churn. This is optional.

### Tests

JVM — `MediaProbeTest`:
- audio-only stream list → `hasAudio = true`, `hasVideo = false`,
  `videoCodec = null`
- real video + audio → `hasVideo = true`, `videoCodec` set
- `attached_pic = true` video stream + audio → `hasVideo = false`
- attached picture plus a real video stream → `hasVideo = true`
- empty/unknown stream types → no crash, `hasAudio = false`,
  `hasVideo = false`

JVM — `CleanVideoHistoryStoreTest`:
- entry with `hasVideo = false` round-trips through `save`/`list`
- legacy JSON without `hasVideo` decodes to `true`
- `entryFor` copies `result.hasVideo`

Instrumented (only if it runs in under a minute):
- generate a 1 s WAV in `cacheDir` with `WavWriter`
- probe it through the same `FFprobeKit` path used by `StreamingCleaner`
- assert `hasAudio = true`, `hasVideo = false`, duration ≈ 1000 ms
- skip full separation; the 117 MB model and minutes-long run are not a unit
  test

### Acceptance

- Picking an MP3, M4A, WAV, FLAC or OGG opens the same screen and completes
  the existing separation pipeline.
- The result offers WAV, FLAC, MP3 and M4A saves only; no Video choice.
- Picking an MP4 still offers video saves and muxes exactly as in 0.4.0.
- An audio file with embedded cover art is treated as audio-only.
- An audio-only result in history reopens with video save still hidden.
- An old history entry from 0.4.0 still offers video save.
- `INTERNET` is still absent from the merged manifest.
- Existing 69 JVM and 21 instrumented tests pass; new tests pass.

### Verification commands

```bash
JAVA_HOME="/run/media/neo/Local Disk/Toolchains/soundify/jdk17" \
ANDROID_HOME="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
bash gradlew testDebugUnitTest --console=plain
```

Instrumented, with `emulator-5554` running:

```bash
JAVA_HOME="/run/media/neo/Local Disk/Toolchains/soundify/jdk17" \
ANDROID_HOME="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
bash gradlew connectedDebugAndroidTest --console=plain
```

## Task 2: 0.5.0 release

Blocked until G1–G4 are ruled on by the owner.

Scope:
- `versionCode 5`, `versionName "0.5.0"`.
- `docs/releases/v0.5.0.md` with the MRX experiment outcome, the audio-input
  change and test counts.
- README: Clean Media accepts audio and video.
- Build the debug-signed release APK.
- Install on SM-A576B and smoke: MP3 import, MP4 import, audio save, video
  save, history reopen, delete.
- Verify merged manifest has no `INTERNET` and no `RECORD_AUDIO`.
- Tag, publish APK + `SHA256SUMS.txt`, keep 0.4.0 as fallback.

## Out of scope for 0.5.0

- Any editor screen, editor model or editor storage.
- OGG/Opus export.
- Metadata write.
- Recording.
- Stem Mixer practice controls.
- New model assets before G2.
