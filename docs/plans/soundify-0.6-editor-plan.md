# Soundify 0.6.0 — Audio editor foundation

Status: in progress
Baseline: `feat/clean-media-audio` at `56cbfa5` (0.5.0 audio input + G1 eval)
Branch: `feat/audio-editor`
Worktree: `/run/media/neo/Local Disk/Projects/Soundify-worktrees/0.6`

This is the 0.6.0 slice of the 0.6–1.0 audio-editor track. It ships a
single-track, clip-sequence editor for one imported file. No DAW timeline,
no multi-track, no effects in this release (effects are 0.8.0).

0.5.0 is not released yet; the G1 listening verdict and the release decision
are still open. This branch does not publish anything and must not be merged
before that decision.

## Global constraints

- Offline: no `INTERNET`, no cloud, no accounts, no tracking.
- Free, ad-free; MIT app code; debug-signed release APK.
- No new permission in 0.6.0. `RECORD_AUDIO` arrives in 0.9.0.
- No new native library, no new model asset, no new dependency.
- FFmpeg through the existing `ffmpeg-kit-audio:8.1.7`.
- Non-destructive: sources are copied into app storage; originals are never
  modified; exports are new files through SAF.
- Export format is 44.1 kHz stereo for every `EditorFormat`.
- Clean Media's Save sheet and formats stay unchanged. The editor has its own
  `EditorFormat`.
- Stem Mixer and existing project flows stay unchanged.
- API 26 minimum, compile/target 35, JDK 17.
- Existing 76 JVM and 23 instrumented tests stay green.
- Preserve user data: deleting a project removes only `files/editor/<id>/`.

## Task 1: Editor model, store and importer

### Goal

A persisted, validated editor project that can be created, saved, listed,
reopened and deleted, plus an importer that copies a picked audio file into
app storage with SHA-256 dedup.

### Files

New:
- `app/src/main/java/com/soundify/app/editor/EditorProject.kt`
- `app/src/main/java/com/soundify/app/editor/EditorStore.kt`
- `app/src/main/java/com/soundify/app/editor/EditorImporter.kt`
- `app/src/test/java/com/soundify/app/editor/EditorProjectTest.kt`
- `app/src/test/java/com/soundify/app/editor/EditorStoreTest.kt`
- `app/src/androidTest/java/com/soundify/app/EditorImporterTest.kt`

### Model

`EditorProject.kt`:

```kotlin
@Serializable
enum class DenoiseMode { OFF, LIGHT, STRONG }

@Serializable
enum class EqPreset { FLAT, VOICE, BASS, TREBLE, WARM }

@Serializable
data class EditorClip(
    val id: String = UUID.randomUUID().toString(),
    val sourceHash: String,
    val sourceFile: String,              // "sources/<hash>.<ext>"
    val displayName: String,
    val sourceDurationMs: Long,
    val sourceSampleRate: Int,
    val sourceChannels: Int,
    val trimStartMs: Long = 0L,
    val trimEndMs: Long = sourceDurationMs,
    val fadeInMs: Long = 0L,
    val fadeOutMs: Long = 0L,
    val gainDb: Float = 0f,
    val speed: Float = 1f,
    val pitchSemitones: Int = 0,
    val denoise: DenoiseMode = DenoiseMode.OFF,
    val clarity: Boolean = false,
    val eqPreset: EqPreset = EqPreset.FLAT,
    val eqLowDb: Float = 0f,
    val eqMidDb: Float = 0f,
    val eqHighDb: Float = 0f,
    val compThresholdDb: Float = -18f,
    val compRatio: Float = 3f,
    val compMakeupDb: Float = 0f,
    val crossfadeMs: Long = 0L
)

@Serializable
data class EditorProject(
    val schemaVersion: Int = 1,
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val outputSampleRate: Int = 44100,
    val outputChannels: Int = 2,
    val clips: List<EditorClip> = emptyList()
)
```

Rules:
- `EditorClip.renderedDurationMs = ((trimEndMs - trimStartMs) / speed)`
  rounded to Long, at least 1.
- `EditorProject.durationMs = sum(rendered) - sum(crossfade)` clamped at 0.
- `sanitized()` returns a copy with:
  - trimStart in `0..sourceDurationMs - 250`
  - trimEnd in `trimStart + 250..sourceDurationMs`
  - fadeIn, fadeOut >= 0 and `fadeIn + fadeOut <= renderedDurationMs`
  - speed in `0.5..2`
  - pitch in `-12..12`
  - gain in `-24..24`
  - eq gains in `-12..12`, comp threshold `-40..0`, ratio `1..10`,
    makeup `0..12`
  - crossfade <= half the shorter adjacent rendered duration
  - duplicate clip ids and blank sourceHash drop the clip
- `sanitized()` never throws and never drops a project.
- `isFutureSchema = schemaVersion > CURRENT_SCHEMA`.

### Store

`EditorStore(private val root: File)`:

```
files/editor/<projectId>/
  project.json
  project.json.tmp
  sources/<sha256>.<ext>
  peaks/<sha256>.bin
```

Methods:
- `list(): List<EditorSummary>` — id, name, updatedAt, durationMs, bytes.
  Sorted by `updatedAt` descending. Corrupt projects are skipped, not deleted.
- `load(id): EditorProject?` — apply `sanitized()`; future schema is returned
  as-is with `isFutureSchema` true so the UI can open it read-only.
- `save(project)` — update `updatedAt`, write `project.json.tmp`, `fsync`,
  rename; fall back to copy + delete.
- `create(name): EditorProject` — mkdir, save, return.
- `delete(id): Boolean` — direct-child canonical check; delete recursively;
  return false for traversal, blank id, or the root itself.
- `sourceFile(hash, extension): File`, `peaksFile(hash): File`,
  `projectDir(id): File`.
- `registerSource(temp: File, hash: String, extension: String): File` —
  if `sources/<hash>.<ext>` exists, delete `temp` and return the existing
  file; else rename `temp` into place.
- `bytesOf(id): Long`.

### Importer

`EditorImporter(context, store)`:
- `suspend fun import(uri: Uri, displayName: String?): ImportedSource`
- Copy the URI to `cacheDir/editor/import.tmp` while feeding SHA-256.
- Extension from the display name, sanitized to `[a-z0-9]{1,5}`, default `wav`.
- Dedup through `store.registerSource`.
- Probe the stored file with FFprobeKit:
  first audio stream's `sampleRate`, `channels`; duration from
  `MediaInformation.duration` (seconds × 1000, rounded).
- If probing fails or there is no audio stream, delete the temp (and the
  registered source only if this import created it) and throw
  `IllegalStateException("This file has no readable audio track.")`.
- `ImportedSource(hash, file, displayName, durationMs, sampleRate, channels)`

### Tests

JVM:
- `sanitized()` clamps every range, fixes inverted trim, caps fade and
  crossfade, drops duplicate ids, keeps valid clips.
- `renderedDurationMs` with speed.
- project duration with crossfades.
- store save/load round-trip, atomic tmp is gone after save, list sorted.
- corrupt `project.json` is skipped by `list()` and returns null by `load()`.
- `delete` refuses `../`, `/`, blank, and the root path.
- `registerSource` dedups an identical hash and keeps one file.
- future `schemaVersion` is reported.

Instrumented:
- import a generated 2 s WAV through `Uri.fromFile`; assert file exists under
  `sources/`, duration ≈ 2000 ms, sample rate 44100, channels 2.
- import the same file twice; assert one source file and the same hash.

### Acceptance

- A project survives save → new store instance → load.
- Deleting a project removes only its directory.
- Importing the same file twice does not duplicate bytes.
- Corrupt project JSON never crashes the list.
- Both suites green.

## Task 2: Waveform peaks and cache

### Files

New:
- `app/src/main/java/com/soundify/app/editor/Waveform.kt`
- `app/src/test/java/com/soundify/app/editor/WaveformPeaksTest.kt`
- `app/src/androidTest/java/com/soundify/app/WaveformDecodeTest.kt`

### Requirements

- `Waveform.buildPeaks(samples: ShortArray, buckets: Int): FloatArray`
  returns `2 * buckets` floats: min, max per bucket, normalized to `-1..1`.
  Empty input returns zeros. Bucket count is `2000` by default, capped by
  sample count (no empty buckets).
- Cache format:
  `magic "SNDP" | version 1 (int) | buckets (int) | durationMs (long) |
  2*buckets floats`, little-endian.
- `Waveform.writeCache(file, peaks, durationMs)` atomic via `.tmp` + rename.
- `Waveform.readCache(file): PeakData?` rejects wrong magic, wrong version,
  impossible bucket counts and truncated files.
- `Waveform.decode(context, source: File, hash: String, durationMs: Long):
  PeakData?`:
  - FFmpeg: `-y -i <src> -ac 1 -ar 8000 -c:a pcm_s16le -f wav <tmp>`
  - read PCM in a streaming loop, bucket, delete tmp, write
    `peaks/<hash>.bin`.
  - cache hit returns without decoding.
  - decode failure returns null; the UI shows a flat placeholder.
- `WaveformView(peaks, playheadFraction, selection, onSeek)` Compose Canvas:
  envelope fill, playhead line, selection overlay, trim handles. Theme colors
  only; no hard-coded colors.

### Tests

JVM: known sine/silence buckets, all-zero input, one-sample input, cache
round-trip, corrupt magic/version/length rejection, bucket count cap.

Instrumented: decode a generated 2 s WAV; assert non-empty peaks, duration
within 50 ms, second call uses the cache (delete the source after the first
call and confirm the peaks still load).

### Acceptance

- 5-minute file peaks build in under 5 s on the emulator; cache load is
  instant.
- No full-resolution PCM is held in memory.
- Cache is keyed by source hash, so split clips share one file.

## Task 3: EditorFormat and FilterGraph

### Files

New:
- `app/src/main/java/com/soundify/app/editor/EditorFormat.kt`
- `app/src/main/java/com/soundify/app/editor/FilterGraph.kt`
- `app/src/test/java/com/soundify/app/editor/EditorFormatTest.kt`
- `app/src/test/java/com/soundify/app/editor/FilterGraphTest.kt`

### EditorFormat

```kotlin
enum class EditorFormat(
    val label: String,
    val extension: String,
    val mime: String,
) {
    WAV("WAV (lossless)", "wav", "audio/wav"),
    FLAC("FLAC (lossless)", "flac", "audio/flac"),
    MP3("MP3 192 kbps", "mp3", "audio/mpeg"),
    M4A("M4A AAC 192 kbps", "m4a", "audio/mp4")
}
```

No OGG/Opus in 0.6.0.

### FilterGraph

Pure builder over `EditorProject` and an ordered list of clips.

Per-clip chain, exact order:

```
atrim=start={startSec}:end={endSec},
asetpts=PTS-STARTPTS,
aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,
{afftdn, if denoise},
{highpass/equalizer/acompressor, if clarity},
{equalizer low/mid/high, if any custom EQ},
acompressor, if custom compressor differs from defaults,
volume={linearGain},
atempo={speed}, if speed != 1,
asetrate={round(44100 * 2^(pitch/12))}, aresample=44100,
atempo={2^(-pitch/12)}, if pitch != 0,
afade=t=in:st=0:d={fadeInSec}, if fadeIn > 0,
afade=t=out:st={renderedSec - fadeOutSec}:d={fadeOutSec}, if fadeOut > 0,
aresample=44100,
aformat=sample_fmts=fltp:sample_rates=44100:channel_layouts=stereo,
asetpts=PTS-STARTPTS
```

- Linear values: `volume = 10^(dB/20)` with 6 decimals; compressor threshold
  and makeup likewise. Never emit dB suffixes.
- Denoise: LIGHT `afftdn=nr=6:nf=-30`; STRONG `afftdn=nr=12:nf=-25`.
- Clarity: `highpass=f=90,equalizer=f=2500:t=q:w=1.2:g=2,acompressor=...`.
- Join:
  - no crossfade: `[a0][a1]...concat=n=N:v=0:a=1[out]`
  - crossfade: pairwise `acrossfade=d={sec}:c1=tri:c2=tri`, chained.
- Final `alimiter=limit=0.891:attack=5:release=50:level=false`.
- `FilterPlan(filterComplex, outputLabel = "[out]", expectedDurationMs,
  outputArgs)`.
- `expectedDurationMs = sum(rendered) - sum(crossfade)`.
- Selected-clip export builds one branch.
- Fade bounds are validated before building; an invalid project is sanitized
  first.

### Tests

Golden strings for: no effects, each effect alone, all effects, speed 0.5
and 2, pitch ±12, speed+pitch combined, fades, single clip, three-clip
concat, two and three-clip crossfade chains, custom EQ and compressor.

Math: linear gain, atempo range, rendered duration, crossfade cap, expected
duration, format args and estimated bytes.

### Acceptance

- Every generated graph parses in FFmpeg on a 2 s clip (instrumented smoke in
  Task 4).
- Bidirectional `atempo` factors stay within `[0.5, 2]` for the locked ranges.

## Task 4: Ffmpeg async and EditorExporter

### Files

Modified:
- `app/src/main/java/com/soundify/app/cleanvideo/Ffmpeg.kt`

New:
- `app/src/main/java/com/soundify/app/editor/EditorExporter.kt`
- `app/src/test/java/com/soundify/app/editor/EditorExporterTest.kt`
- `app/src/androidTest/java/com/soundify/app/EditorExportTest.kt`

### Ffmpeg

- Make `friendlyMessage` `internal` and add editor cases: cancel, no audio,
  disk full, unknown encoder.
- Add:
  ```kotlin
  fun runAsync(
      command: String,
      onStatistics: (Statistics) -> Unit,
      onComplete: (Boolean, String) -> Unit,
  ): FFmpegSession
  ```
  using `FFmpegKit.executeAsync(command, complete, log, statistics)`.
- Keep the blocking `run` unchanged.

### Exporter

`EditorExporter(context)`:
- `export(project, clips, format, target, onProgress, onCancelReady): Result`
- Build the `FilterPlan`; write to `cacheDir/editor/export/`.
- Free-space check:
  `estimateBytes(format, duration) * 2 + 50 MB`; fail before FFmpeg with
  `"Not enough storage. Free some space and try again."`
- Progress: `statistics.time * 1000 / expectedDurationMs`, clamped 0..100.
- Cancel: `FFmpegKit.cancel(sessionId)`; delete temp output.
- On success copy to SAF with `"wt"` on API 30+, else `"w"`, then delete temp.
- Estimated bytes: WAV `44100 * 2 * 2 * seconds`; FLAC 60% of WAV;
  MP3/M4A 192 kbps; fixed 10% container overhead.
- Output args: `-ar 44100 -ac 2 -c:a <codec> [bitrate] <file>`.

### Tests

JVM: size estimate per format, output args, progress clamp, target name.

Instrumented: export a 2 s generated clip as WAV; decode with
`WavStreamReader`; assert duration within one sample, 44100 Hz, 2 channels,
peak ≤ -1 dBFS. Export the same clip with no effects and compare decoded PCM
to the source trim (null test within tolerance).

### Acceptance

- Cancel leaves no temp file and keeps the project.
- Low-storage fails before starting.
- Export progress reaches 100 and the SAF file is playable.

## Task 5: EditorPlayer

### Files

New:
- `app/src/main/java/com/soundify/app/editor/EditorPlayer.kt`
- `app/src/androidTest/java/com/soundify/app/EditorPlayerTest.kt`

### Requirements

- `MediaPlayer` based; `load(clip: EditorClip, file: File)`.
- Seek to `trimStartMs`; play; poll every 50 ms; pause at `trimEndMs`.
- `onClipFinished` advances to the next clip in the project order.
- `setGainDb` uses `setVolume` with linear gain capped at 1.0; boosts are
  export-only.
- Speed/pitch through `PlaybackParams`:
  `setSpeed(speed).setPitch(2^(semitones/12))`; catch
  `IllegalArgumentException` and `IllegalStateException`, reset to 1×, and
  report `previewUnavailable`.
- `release()` always on dispose.
- No real-time fades or crossfades; the UI says "Applied on export".

### Tests

Instrumented: generated 2 s WAV, trim 500..1500 ms, play, assert position
starts near 500 ms and stops by 1600 ms; gain 0.5 does not throw; unsupported
PlaybackParams falls back without a crash.

### Acceptance

- Leaving the editor releases the player.
- Preview never runs past `trimEndMs`.
- No crash on a device that rejects PlaybackParams.

## Task 6: Navigation, EditorViewModel and EditorHomeScreen

### Files

Modified:
- `app/src/main/java/com/soundify/app/SoundifyViewModel.kt`
- `app/src/main/java/com/soundify/app/ui/SoundifyApp.kt`
- `app/src/main/java/com/soundify/app/ui/LibraryScreen.kt`

New:
- `app/src/main/java/com/soundify/app/editor/EditorViewModel.kt`
- `app/src/main/java/com/soundify/app/ui/EditorHomeScreen.kt`

### Requirements

- `Screen` adds:
  ```kotlin
  data object EditorHome : Screen
  data class Editor(val projectId: String) : Screen
  ```
- `SoundifyViewModel.back()`:
  - `Editor` → `EditorHome`
  - `EditorHome` → `Library`
- `SoundifyApp` routes both screens.
- `LibraryScreen` adds an `AudioEditorCard` beside `CleanVideoCard` in both
  the empty and populated branches:
  title `Audio editor`, subtitle `Trim, merge, convert and record audio`.
- `EditorViewModel`:
  - `projects: StateFlow<List<EditorSummary>>`
  - `open(projectId)`, `create(name)`, `delete(id)`
  - `active: StateFlow<EditorProject?>`
  - `undo`/`redo` stacks, cap 50, coalescing 500 ms
  - `save()` debounced 500 ms and forced before export/background
  - `message` for errors
- `EditorHomeScreen`:
  - list with name, duration, date, size
  - `New edit` opens `OpenDocument` with `audio/*`
  - empty state: `Import an audio file to trim, split or fade it.`
  - delete confirmation
  - tapping a project opens `Screen.Editor(id)`

### Tests

JVM: `EditorHistory` undo/redo, cap, coalescing, no-op at bounds.
Instrumented: launch editor home, empty state visible, back returns to
Library.

### Acceptance

- Library → Audio editor → New edit → Editor; back returns one level.
- No editor screen is reachable from the Stem Mixer flow.
- Existing navigation unchanged.

## Task 7: EditorScreen, tools and save sheet

### Files

New:
- `app/src/main/java/com/soundify/app/ui/EditorScreen.kt`
- `app/src/main/java/com/soundify/app/ui/EditorToolsSheet.kt`

Modified:
- `app/src/main/java/com/soundify/app/ui/SoundifyApp.kt`

### Requirements

- Top bar: project name, save, overflow with `Delete project`.
- Waveform card using `WaveformView`.
- Transport: play/pause, current/total time, seek.
- Tool rail: Trim, Split, Fade, Volume.
  - Trim: numeric start/end fields plus waveform handles.
  - Split at playhead: two clips, same source, crossfade on the second is 0.
  - Fade: in/out sliders capped by rendered duration.
  - Volume: -24..+24 dB.
- Clip list: add clip, reorder up/down, delete, per-clip selection.
- Save sheet: `EditorFormat` choices, name, SAF `CreateDocument`.
- Note: `Original is never modified.` and
  `Crossfade and effects are applied on export.`
- Use the exporter; show progress and Cancel.
- Disable editing while exporting.

### Tests

Instrumented smoke: import a generated 2 s WAV, trim 0.5..1.5 s, export WAV,
assert the exported duration is 1 s ± one sample.

### Acceptance

- Import → waveform → trim → export works end to end.
- Split creates two clips whose rendered durations sum to the source trim.
- Delete project removes only app copies.
- The original picked file is unchanged (hash before/after).

## Task 8: 0.6.0 release prep

Blocked until the 0.5.0 listening/release decision is made.

- `versionCode 6`, `versionName "0.6.0"`.
- `docs/releases/v0.6.0.md`.
- README: three modes and the editor's limits.
- Build the debug-signed APK.
- Install on SM-A576B, smoke import → trim → split → export → reopen.
- Verify merged manifest has no `INTERNET` and no `RECORD_AUDIO`.
- Tag and publish only after 0.5.0 is published; keep 0.5.0 as fallback.

## Out of scope for 0.6.0

- Merge/crossfade export, convert/batch, effects, recording, metadata,
  practice tools.
- OGG/Opus, real-time DSP, multi-track timeline, cloud, accounts.
- Any change to Clean Media or Stem Mixer behavior.

## Build and test commands

```bash
JAVA_HOME="/run/media/neo/Local Disk/Toolchains/soundify/jdk17" \
ANDROID_HOME="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
ANDROID_SDK_ROOT="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
bash gradlew testDebugUnitTest --console=plain
```

Instrumented:

```bash
JAVA_HOME="/run/media/neo/Local Disk/Toolchains/soundify/jdk17" \
ANDROID_HOME="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
ANDROID_SDK_ROOT="/run/media/neo/Local Disk/Toolchains/soundify/android-sdk" \
bash gradlew connectedDebugAndroidTest --console=plain
```

`local.properties` is untracked and already present in the worktree.
`app/src/main/assets/mrx_mask.onnx` is gitignored and already copied.
