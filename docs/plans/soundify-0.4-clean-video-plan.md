# Soundify 0.4 — Clean Video quality, history, in-app storage

Status: approved for implementation
Owner decisions: a slower optional "Best quality" mode is allowed; this plan lives in the repo.
Baseline: v0.3.0 (versionCode 3), tag `v0.3.0`, HEAD `5925d14`
Repo: `/run/media/neo/Local Disk/Projects/Soundify`

## 1. Problem statement

The owner tested 0.3.0 on the phone (SM-A576B, Android 17) and reports:

1. The bundled model still separates badly: voice, SFX and music bleed into each other, and parts of the mix go missing.
2. There is no in-app way to free the storage Clean Video results consume; the only option is Android Settings.
3. There is no history. Re-opening the same video starts a new separation and creates another copy of the stems.

Constraints that still apply: free, offline, ad-free, MIT app code, no cloud, no accounts, no tracking. The Audio Editor APK is a UX reference only — do not copy code, native libraries, models, strings or assets. Moises is not to be reused either.

## 2. Evidence gathered before planning

### 2.1 The model is the correct family, but the inference contract is wrong

- `THIRD_PARTY_LICENSES.md` identifies the model as MERL MRX ("The Cocktail Fork Problem", ICASSP 2022), MIT.
- Official `separate.py` (merlresearch/cocktail-fork-separation) calls `separate_soundtrack(..., input_lufs=-27.0)`:
  - it computes a gain that brings the input to **-27 LUFS**,
  - applies it before the model,
  - divides every output stem by the same gain afterwards.
- Commit `57cc41d` ("Fix Clean Video loudness staging") removed the `-27` normalization from extraction and now separates at native level. That fixed the 13 dB quiet output, but it also moved inference outside the model's training distribution.
- MRX's `_EncoderBlock` starts with `BatchNorm1d` using fixed running statistics from -27 LUFS-normalized DnR audio. A native-level Reel is roughly 13 dB hotter, so the first activations shift by ~4.5x and the tanh/LSTM path saturates. The model is not scale-invariant.
- The rest of the DSP already matches the official implementation:
  - FFT sizes 1024/2048/8192, hop 256,
  - orthonormal FFT scaling (`Fft.kt`), matching `torch.stft(normalized=True)`,
  - multi-resolution mask sum, 9 mask outputs, source-major order.
- The official pipeline also has `dnr_consistency` (residual assignment: `pass` / `all` / `music_sfx`). Soundify currently has no residual stage, so the three stems do not reconstruct the mixture; this can present as "missing audio".

Conclusion: restore the official inference contract before judging or replacing the model. This is the highest-value, lowest-risk change.

### 2.2 Published quality ceiling of MRX

From the official README, DnR test-set SI-SDR:

| | Speech | Music | SFX |
|---|---:|---:|---:|
| Unprocessed | 1.0 | -6.8 | -5.0 |
| MRX (repo) | 12.5 | 4.2 | 5.7 |

Music and SFX separation is inherently weak. Even after the contract fix, a model bake-off may be required.

### 2.3 Codebase facts used by this plan

- Processing: `cleanvideo/StreamingCleaner.kt` — 6 s chunks, 1 s overlap, 5 s hop, stems written at -6 dB headroom, clean mix normalized to -16 LUFS / -1.5 dBTP.
- Model: `cleanvideo/OnnxSeparator.kt`, `ChunkSeparator.kt`, `Stft.kt`, `Fft.kt`.
- Storage: `CleanVideoStorage` writes to `files/exports/cleanvideo/<timestamp>/`; `ProjectStore.storageUsedBytes()` counts `files/projects` + `files/exports`; `cacheDir` and the 117 MB model are not counted.
- History: none. `CleanVideoController` keeps only the current `CleanVideoResult` in memory; app restart loses the result and leaves stem directories orphaned.
- Settings: `ui/SettingsScreen.kt` only prints "Storage used: …"; there is no action.
- Navigation: `SoundifyViewModel.Screen` is a sealed interface (`Library`, `Import`, `Player`, `Settings`, `CleanVideo`); `ui/SoundifyApp.kt` switches on it.
- Result screen: `ui/CleanVideoScreen.kt` already has per-run "Delete stems" and the Save sheet; that is the only cleanup today.
- Existing tests: 41 JVM, 19 instrumented, 0 failures at 0.3.0.

### 2.4 Baseline assets available

- `Spike/` contains 37 MP4s (28.1 min total, all with AAC stereo). It is git-ignored.
- A temporary harness `app/src/androidTest/java/com/soundify/app/SpikeAudioEvalTest.kt` runs the real pipeline over `files/spike-in` and copies stems to `files/spike-out`. Outputs from a full 37-clip native-level run are being written to `Spike/out/`. This harness must not ship; either delete it or promote it to `tools/eval/` before release.
- Previews are MP3 192k; the clean mix is gain-matched to -16 LUFS for fair A/B.

## 3. Goals and non-goals

### Goals

1. Restore correct separation quality, then measure whether MRX is good enough. Ship the best configuration that runs offline on the phone.
2. Persist Clean Video history: browse, reopen, save and delete previous results across restarts and updates.
3. Never process the same video twice by accident: fingerprint the source and offer the existing result.
4. Add an in-app storage screen with a real cleanup action and an accurate byte breakdown.
5. Keep the four-stem flow, single Save sheet, MP3/M4A, themes, foreground service and cancellation behavior intact.

### Non-goals

- Cloud separation, accounts, ads, telemetry, subscriptions.
- Training a model from scratch.
- Reusing Moises or Audio Editor code, models, native libraries, strings or assets.
- Linux/desktop app work.
- Changing the Stem Mixer unless a shared component requires it.

## 4. Phase 0 — Baseline and measurement harness (0.5–1 day)

Purpose: make "better" measurable instead of subjective.

1. Finish and archive the 37-clip baseline:
   - outputs: `Spike/out/<clip>/` with `clean/voice/sfx/music.wav` and `stats.txt`;
   - rank by music-vs-voice energy to separate "music-heavy" clips from "speech-only" clips;
   - keep the baseline table in `docs/eval/clean-video-baseline.md` (new, small, text only).
2. Build a ground-truth set:
   - preferred: a handful of DnR test clips (Zenodo 6949108), which is the dataset MRX was trained/evaluated on;
   - fallback: synthesize mixtures from clean speech, music and SFX files with known stems;
   - keep the set small (≤ 10 clips, ≤ 30 s each).
3. Metric script (desktop, no app dependency): SI-SDR per stem, plus integrated loudness and true peak. Reuse the official formula so numbers are comparable to the README table.
4. Identify which of the four official checkpoints produced `mrx_mask.onnx` (`default`, `paper`, `adapted_loudness`, `adapted_eq`). The ONNX has no metadata, so compare exported weights or re-export and hash.
5. Remove or relocate `SpikeAudioEvalTest.kt`; do not merge it into the shipped test suite.

**Gate:** baseline SI-SDR table and a listening-ranked shortlist of clips exist before any model change.

## 5. Phase 1 — Restore the official inference contract (highest priority)

### 5.1 Input loudness normalization

- In `StreamingCleaner.clean`, after extracting the float WAV and before chunking:
  - measure integrated loudness of the **whole file** with the existing `Loudness`/FFmpeg loudnorm path;
  - compute `gain = 10^((-27 - measuredI) / 20)`;
  - apply that scalar to every sample fed into `ChunkSeparator` (during decode or with an FFmpeg `volume` filter — one place, not per chunk);
  - after separation and before `WavStreamWriter`, divide every stem by the same `gain` so stems return to native level;
  - keep the existing 6 dB stem headroom and the two-pass clean-mix normalization to -16 LUFS / -1.5 dBTP.
- Guardrail: if measurement fails, fall back to gain 1.0 and surface a note; never fail the run for a loudness measurement.
- Remove the stale comments that claim the model must receive native-level audio.

### 5.2 Residual consistency

- Add a configurable residual stage after `separator.separate`:
  - `residual = scaledInput - (music + speech + sfx)`;
  - default mode `music` (add residual to music) or `music_sfx` (split between music and SFX), matching the official `dnr_consistency` idea;
  - compute before the inverse gain, so it stays in the model domain;
  - keep `clean = voice + sfx` semantics, so adding residual to music does not put music back into Clean.
- Make the mode a constant first; only expose it if the bake-off shows it matters.

### 5.3 Acceptance for Phase 1

- On the ground-truth set, every stem's SI-SDR is ≥ the 0.3.0 baseline and, where the same checkpoint is used, reaches the official repo range (speech ~12.5, music ~4.2, SFX ~5.7 on DnR).
- Clean mix still measures -16 ±1 LUFS and ≤ -1.5 dBTP, no clipping.
- Listening on at least the music-heavy Spike clips: speech intelligible, music audibly reduced, SFX not swallowed, no new warbling.
- If the contract fix does not move the numbers, stop and go to Phase 3 with evidence; do not stack speculative DSP changes.

## 6. Phase 2 — Chunking and overlap (0.5–1 day)

Only after Phase 1 is measured.

- Compare 6 s vs 10 s chunks and 1 s vs 2 s overlap on the ground-truth set and 3–4 Spike clips.
- Record peak memory and wall time per configuration on the emulator and, if available, the phone.
- Watch for BiLSTM context effects at seams; re-create the seam probe under `tools/eval/` if it is kept.
- Choose the default by listening plus SI-SDR; document the memory ceiling. Do not increase chunk size if it risks OOM on 4 GB devices.
- If 10 s/2 s wins and memory is acceptable, update `CHUNK`/`OVERLAP`/`HOP` in `StreamingCleaner.kt` and the progress math (`chunkIndex`, ETA).

## 7. Phase 3 — Checkpoint and model bake-off (3–5 days, can run in parallel with Phases 4–5)

Run in this order; stop when the listening bar is met.

1. **MRX checkpoints** (lowest risk, same architecture/interface):
   - export `adapted_loudness` and `adapted_eq` checkpoints to ONNX with the same 9-mask interface;
   - compare against the current checkpoint on the ground-truth set and Spike;
   - pick the winner by SI-SDR and listening; keep MIT notices.
2. **BandIt / BandIt v2** (cinematic dialogue/music/effects, Apache-2.0 repo):
   - verify weight license and ONNX availability (v2 weights on Zenodo; a music-only ONNX export exists on Hugging Face, which is not sufficient);
   - export a full 3-stem ONNX if needed;
   - measure model size, memory, and real-time factor on the phone. If RTF makes a 1-minute video take more than ~4–5 minutes, it is only viable as the optional "Best quality" mode approved by the owner.
3. **DeepFilterNet3** (speech enhancement, not a separator):
   - test as an optional post-filter on the voice stem at 48 kHz;
   - accept only if it removes music/SFX bleed without thinning speech or adding musical noise;
   - it must never run on the clean mix or music stem.
4. Reject any candidate that cannot ship offline, adds a network permission, or cannot be licensed for an MIT app.

**Decision point:** if Phase 1 alone meets the listening bar, ship it and defer external models. If not, integrate the best candidate behind a "Balanced / Best quality" selector, defaulting to Balanced, with an explicit time estimate in the UI.

## 8. Phase 4 — Clean Video history (2–3 days)

### 8.1 Data model

New `data/CleanVideoHistoryStore.kt` (or `cleanvideo/CleanVideoHistoryStore.kt`) with:

```
CleanVideoHistoryEntry(
  id, fingerprint, displayName, createdAt,
  durationMs, videoCodec, cleanNote,
  stems: List<StemRecord(kind, fileName, durationMs, rmsDb, peakDb)>,
  totalBytes, sourceUri, appVersion
)
```

- Persist `history.json` under `files/exports/cleanvideo/` (atomic tmp+rename, same pattern as `ProjectStore`).
- Store each run's stems in `files/exports/cleanvideo/<id>/`; write a `meta.json` there as a second source of truth.
- Migration: scan existing timestamp directories once and import them as `Previous cleanup` entries (date from directory mtime, stats recomputed with `Wav.analyze`); entries that fail validation are offered for cleanup, not silently deleted.

### 8.2 Fingerprint and duplicate prevention

- Compute `SHA-256(size + first 256 KiB + last 256 KiB + durationMs)` when a video is selected. Hashing only the edges keeps selection fast on large files.
- On selection, look up the fingerprint:
  - match + complete stems → dialog "You already cleaned this video" with **Open result** (default) and **Clean again** (secondary; confirm replacing the old entry to avoid duplicate storage);
  - no match → normal flow.
- Persist the fingerprint in the entry and in `meta.json`.

### 8.3 UI and navigation

- Add `Screen.CleanVideoHistory` to `SoundifyViewModel.Screen` and route it in `ui/SoundifyApp.kt`.
- Entry points:
  - history icon in the Clean Video top bar;
  - a "History" row/card on the Library screen next to Clean Video.
- History screen (`ui/CleanVideoHistoryScreen.kt`):
  - newest first; name, date, duration, total size, stem count;
  - tap = open result and restore `CleanVideoController` state so playback/Save work;
  - per-entry delete (stems only) and "Clear all" with confirmation;
  - total size summary and a link to Storage.
- Reopening a result:
  - audio Save works from the stored WAVs;
  - video Save needs the original source. Probe the stored `sourceUri`; if unreadable, disable Video save and explain that the original video is no longer accessible, while Audio save stays enabled.
- Back behavior: return to the previous screen, matching the existing `BackHandler` pattern.

### 8.4 Persistence and process death

- `CleanVideoService`/`CleanVideoController.finish` must persist the entry before reporting Done.
- If persistence fails, keep the result in memory and surface a non-blocking error; never lose the just-finished stems.
- On app start, load history asynchronously; the Library/Clean Video screens must not block on it.

## 9. Phase 5 — In-app storage cleanup (1–2 days)

### 9.1 Usage model

Extend `ProjectStore` or add `data/StorageManager.kt`:

```
StorageUsage(
  projectsBytes, cleanVideoBytes, otherExportsBytes,
  cacheBytes, modelBytes, freeBytes, totalBytes
)
```

- `cleanVideoBytes` = history entries + orphan directories under `files/exports/cleanvideo`.
- `cacheBytes` = `cacheDir/cleanvideo` plus stale temp files.
- `modelBytes` = `files/mrx_mask.onnx` (required; show as "App & model", not reclaimable).
- Keep the existing `storageUsedBytes()` behavior for projects+exports so current callers do not break, and add the richer breakdown alongside it.

### 9.2 Actions

- `cleanTemporary()`: delete `cacheDir/cleanvideo` and stale temp files; never touch history or projects.
- `deleteHistoryEntry(id)`: delete that entry's directory and remove it from `history.json`.
- `deleteAllCleanVideo()`: delete all history entries and orphan stem directories; keep projects and files the user saved through SAF.
- `deleteProjects()`: not part of the one-tap cleanup; project deletion stays per-project in the Library.
- All deletions must canonical-path check against the allowed roots (reuse `CleanVideoStorage.deleteExportDir` pattern).

### 9.3 UI

- Settings screen gains a **Storage** section above About:
  - rows: Projects, Clean Video results, Temporary files, App & model;
  - primary button **Free up space** → confirmation listing exactly what will be removed and what is kept;
  - secondary buttons: **Delete Clean Video results**, **Delete temporary files**;
  - after cleanup, refresh and show the freed amount ("Freed 812 MB").
- Clean Video history screen shows total size and the same cleanup actions.
- Clean Video result screen keeps its per-run Delete action; after deletion, remove the history entry too.
- Optional: a Library banner when Clean Video storage exceeds ~1 GB, linking to Storage.

### 9.4 Acceptance

- Cleanup frees the reported bytes; the in-app number matches `du`/system storage within a small margin.
- Projects, saved SAF exports and the model are untouched.
- History is consistent after cleanup (no entries pointing at deleted stems).
- No destructive action without a confirmation dialog.

## 10. Phase 6 — Integration, tests, release (1–2 days)

### Tests to add

JVM:
- history JSON round-trip, unknown-field tolerance, atomic write;
- fingerprint determinism and duplicate lookup;
- storage breakdown math and cleanup path guards (cannot delete outside roots);
- gain math for the -27 LUFS contract (given a measured LUFS, expected gain; restore cancels the gain);
- residual assignment preserves `clean = voice + sfx`.

Instrumented:
- run one clip through the fixed contract; assert stems exist, clean is -16 ±1 LUFS and ≤ -1.5 dBTP, no clipping;
- reopen a history entry after a process restart and play/save audio;
- delete one entry and verify only its directory disappears;
- one-tap cleanup removes stems/temp but keeps a seeded project;
- re-selecting the same video produces the "already cleaned" path, not a second separation.

Regression:
- the existing 41 JVM + 19 instrumented tests must stay green;
- `Spike` evaluation (37 clips) re-run with the winning configuration; store the table in `docs/eval/`.

### Release

- Version `0.4.0` (versionCode 4), debug-signed as before.
- Release notes must state the measured before/after SI-SDR and the listening set, plus history/storage changes.
- Build the release APK, install over 0.3.0 on the phone (same signing key), smoke-test Clean Video in the background, history reopen, and cleanup.
- Publish to GitHub only after the phone smoke test; attach the APK and `mrx_mask.onnx` (or the new model file) plus checksums.

## 11. Acceptance criteria for the whole release

1. Model: on the ground-truth set, each stem's SI-SDR beats 0.3.0 and is close to the official MRX range; listening confirms music is reduced without swallowing speech/SFX; no seam warbling or crackle beyond the source.
2. Loudness: clean mix -16 ±1 LUFS, true peak ≤ -1.5 dBTP, no clipping; near-silent guard still works.
3. History: survives force-stop/relaunch and app update; re-selecting the same video opens the existing result by default; deleting an entry frees its bytes.
4. Storage: in-app breakdown matches the filesystem; one-tap cleanup frees Clean Video stems and temp files only; projects and saved files survive.
5. No network permission, no ads, no accounts, MIT notices updated for any new model.
6. All tests green; release APK installs over 0.3.0 on SM-A576B.

## 12. Owner decisions

Confirmed:

1. A slower optional "Best quality" mode is allowed; Balanced stays the default.
2. This plan lives in the repo (this file).

Defaults, change on request:

3. History retention is manual; show a warning at ~2 GB.
4. "Free up space" deletes temporary files plus all Clean Video results after confirmation; projects and saved files are kept. Per-entry deletion stays available in History.

## 13. Risks

- The -27 LUFS contract fix may improve masks but not enough; the bake-off is the fallback, and BandIt may be too slow for real-time use.
- Longer chunks improve context but can OOM on 4 GB devices; enforce a memory guard and keep 6 s as the safe default until measured.
- History makes storage grow; fingerprinting and cleanup must stay fast and never delete saved files.
- Persisted `sourceUri` may expire, disabling video Save for old entries; degrade to audio-only with a clear message.
- The 117 MB model plus history can make the app look large; show "App & model" separately so cleanup expectations are honest.
- The 37-clip baseline run and its temporary harness are not part of the product; remove them from the build before release.
