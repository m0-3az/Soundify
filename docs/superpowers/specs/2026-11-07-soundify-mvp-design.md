# Soundify Stem Mixer — Android MVP Design

**Date:** 2026-11-07
**Status:** Draft for review
**Product:** Soundify Stem Mixer, a free, offline, no-AI multi-track player and mixer for user-supplied stems
**Supersedes:** The cloud AI MVP design previously stored at this path

## 1. Context

The reference Moises APK is a server-side stem-separation product. Its client-side value after separation is a synchronized multi-track player: per-stem volume, pan, mute, solo, seeking and export.

Soundify Stem Mixer keeps that client-side value and removes the AI and the backend entirely:

- The user supplies already-separated stems.
- The app aligns, plays, mixes and exports them locally.
- There is no account, no server, no subscription, no advertising, no telemetry and no AI.

This is a different product from Moises: it does not turn a normal song into stems. It is a rehearsal, practice and mixing tool for people who already have stem files.

## 2. Goals and non-goals

### Goals

- Free to use and free to run: no backend, no API fees, no subscriptions, no ads, no accounts.
- Fully offline: the app does not require the `INTERNET` permission.
- Import 2–8 stem files and map them to mixer channels.
- Play all stems in sync with independent gain, pan, mute and solo.
- Support a loop region for practice.
- Export the current mix to WAV or M4A/AAC on device.
- Keep the app small and maintainable by using Android platform codecs instead of FFmpeg.
- Provide a clean, accessible Compose UI.

### Non-goals for v1

- Automatic stem separation (this is the AI feature being removed).
- Chord detection, BPM detection, lyrics, karaoke, denoise or mastering.
- Pitch shift, time-stretch, metronome or count-in.
- Accounts, cloud sync, collaboration or public sharing.
- URL import or bundled music catalog.
- iOS, web or desktop.
- MP3 export.
- Automatic offset detection between stems.

## 3. Target user and core flow

**Target user:** a musician, producer, teacher or student who already has stem files (from a DAW, a purchased stem pack, a rehearsal track or another tool) and wants to practice or build a mix on a phone.

**Core flow**

1. Create a project.
2. Select 2–8 stem files.
3. Confirm channel roles and names.
4. Play the project.
5. Adjust volume, pan, mute and solo.
6. Set a loop region if desired.
7. Export the mix.

## 4. MVP scope

### In scope

- Android only, Kotlin + Jetpack Compose.
- Import via the Storage Access Framework.
- 2–8 stems per project.
- Import formats: WAV, FLAC, MP3, M4A/AAC.
- Sample rates: 44.1 kHz and 48 kHz.
- Channels: mono (duplicated to stereo) and stereo.
- Maximum project duration: 30 minutes.
- Automatic role detection by filename, with manual override.
- Per-stem manual offset nudge.
- Synchronized playback with one transport clock.
- Per-stem gain, pan, mute and solo; master gain.
- Play, pause, seek and loop region.
- Background playback with a media notification and `MediaSession`.
- Local project library with saved mix state.
- Export to WAV (16-bit PCM) and M4A/AAC.
- No network permission, no accounts, no analytics, no ads.
- English copy in v1; all strings externalized for future translation.

### Out of scope

Everything in the non-goals list.

## 5. Success criteria

| Area | Criterion |
|---|---|
| Core loop | A user can import 4 stems, play them in sync, mix them, loop a section and export without help |
| Sync (WAV/FLAC) | Sample-aligned playback; automated click-track test shows zero drift |
| Sync (MP3/M4A) | Manual offset can correct decoder delay; documented limitation |
| Export | Rendered mix matches the live mix; null test shows no audible difference |
| Performance | 8 stereo stems play without dropouts on a mid-range Android device |
| Stability | Crash-free sessions ≥ 99.5% during beta |
| Size | APK under 25 MB |
| Privacy | No `INTERNET` permission; no data leaves the device |
| Accessibility | TalkBack labels, minimum touch targets, dark theme, scalable text |

## 6. Architecture

Soundify Stem Mixer is a single-module Android app with clear internal boundaries. There is no backend and no network layer.

```
Compose UI
  │
  ▼
ViewModels / UI state
  │
  ├── ProjectRepository (Room + app storage)
  ├── ImportUseCase (SAF → validate → copy → metadata)
  ├── MixerController (state: gain, pan, mute, solo, offset)
  └── ExportUseCase (offline render → WAV/M4A)
        │
        ▼
Audio engine
  ├── MediaExtractor + MediaCodec per stem
  ├── PCM ring buffers
  ├── Mix thread (float sum, gain, pan)
  ├── AudioTrack output
  └── MediaSession / foreground service
```

### Component responsibilities

| Component | Responsibility |
|---|---|
| Compose UI | Library, import wizard, player, mixer, export, settings |
| ViewModels | UI state, validation, user actions |
| ProjectRepository | Room entities, file storage, project lifecycle |
| ImportUseCase | SAF selection, format validation, metadata, file copy, role mapping |
| MixerController | Mix state and parameter changes |
| Audio engine | Decode, buffer, mix, play, seek, loop |
| ExportUseCase | Offline render with the same mix math |
| PlayerService | Foreground playback, audio focus, media notification |

## 7. Android stack and modules

### Stack

- Kotlin
- Jetpack Compose + Navigation Compose
- Hilt
- Coroutines and Flow
- Room
- DataStore for preferences
- Storage Access Framework for import
- MediaExtractor, MediaCodec, MediaMuxer, AudioTrack
- MediaSession + foreground service

### SDK levels

- `minSdk = 26` (Android 8.0)
- `targetSdk =` the API level required by Google Play at release time. For the 2026 cycle this is expected to be API 36; confirm before submission.

### Modules

```
app
core:audio
core:data
core:design
feature:library
feature:import
feature:player
feature:export
feature:settings
```

A single-module build is acceptable for M0–M1. Split into the modules above before the feature set grows.

## 8. Import and project model

### Import flow

1. User taps **New project**.
2. System file picker opens with multi-select.
3. App reads metadata with `MediaExtractor`: format, sample rate, channels, duration.
4. App validates:
   - supported format
   - 44.1 or 48 kHz
   - mono or stereo
   - duration ≤ 30 minutes
   - 2–8 files
5. App proposes channel roles by parsing filenames:
   - `vocals`, `vocal`, `vox` → Vocals
   - `drums`, `drum`, `perc` → Drums
   - `bass` → Bass
   - `guitar` → Guitar
   - `piano`, `keys` → Keys
   - `other`, `instrumental`, `music` → Other
   - otherwise → Stem 1..N
6. User can rename channels and change roles.
7. App copies files into app-private project storage and writes Room rows.
8. Project opens in the player.

### Project storage

```
filesDir/projects/{projectId}/
  project.json
  stems/{stemId}.{ext}
```

- Files are copied rather than referenced so a project survives the user moving or deleting the original file.
- `project.json` mirrors the database for portability and debugging.
- Deleting a project deletes its directory and database rows.
- `android:allowBackup="false"` to avoid backing up large audio files.
- A future version may use content-addressed storage to deduplicate stems shared across projects.

### Project duration

Project duration is the longest stem duration. Shorter stems are zero-padded. The UI shows the project duration and warns when stem lengths differ by more than 1 second.

## 9. Audio engine

### Format constraints

- All stems in a project must share the same sample rate: 44.1 kHz or 48 kHz.
- Mono stems are duplicated to stereo.
- Multichannel stems are rejected in v1 with a clear message.
- WAV and FLAC are sample-accurate.
- MP3 and M4A/AAC may carry encoder delay; the user can correct it with the per-stem offset nudge.

### Decode and mix

- One `MediaExtractor` + `MediaCodec` pair per stem.
- Decoded PCM is written into a ring buffer per stem.
- A dedicated mix thread pulls frames from each buffer, applies gain and constant-power pan, sums in float, applies master gain and a soft clip, then writes 16-bit PCM to a single `AudioTrack`.
- Output sample rate equals the project sample rate.
- Buffer target: 20–40 ms, tuned per device.
- Decoding runs ahead of playback to absorb jitter.

### Transport and sync

- A single 64-bit sample counter is the transport clock.
- Play starts all decoders from the same sample position after pre-roll.
- Seek flushes buffers, seeks every extractor to the same timestamp, pre-rolls, then resumes.
- Loop: when the sample counter reaches loop end, it jumps to loop start and all stems seek together.
- Per-stem offset is applied as a sample-domain shift; positive offset delays the stem, negative advances it.

### Background playback

- `PlayerService` is a foreground service with `MediaSession`.
- Handles audio focus and becoming-noisy.
- Notification shows project name, play/pause and seek.
- Android 14+ foreground service type `mediaPlayback`.

### Limitations documented in the app

- MP3/AAC stems may need a manual offset.
- Different sample rates are rejected in v1.
- Very large projects may take time to copy and open.

## 10. Mixer and transport controls

### Per-stem controls

- Gain: 0–200%, default 100%.
- Pan: -100 (left) to +100 (right), default center.
- Mute: toggles the stem off.
- Solo: mutes all non-soloed stems.
- Offset: ±500 ms nudge, 1 ms steps.
- Name and role: editable.

### Master controls

- Master gain: 0–200%.
- Clip indicator: lights when the output would exceed full scale.
- Soft clip in the output stage to avoid hard distortion.

### Transport

- Play/pause.
- Seek bar with elapsed and remaining time.
- Loop toggle plus loop start/end handles.
- Screen stays awake while the player is visible, if the user enables it.

### Interaction details

- Gain and pan changes are ramped over 5–10 ms to avoid clicks.
- Parameter changes are persisted when the project is paused or closed.
- Undo is out of scope for v1.

## 11. Export

### Formats

- WAV: 16-bit PCM, project sample rate, stereo.
- M4A: AAC at 256 kbps, project sample rate, stereo.

MP3 export is deferred to v2.

### Rendering

- Export runs offline through the same mixer math as playback.
- It reads decoded PCM, applies gain, pan, mute, solo, offsets and master gain, and writes the output.
- It does not use FFmpeg.
- WAV is written directly with a PCM header.
- M4A is produced with `MediaCodec` (AAC encoder) + `MediaMuxer`.
- Export progress is shown as a percentage and can be cancelled.
- Output is written to the app cache first, then handed to the system share sheet or saved via SAF.
- A reference render is computed from the same decoded PCM and mix state; the exported file must match it within 0.1 dB RMS.

### Export failures

- Unsupported sample rate: blocked before export.
- Storage full: clear message and no partial file left behind.
- Encoder failure: fall back to WAV and tell the user.

## 12. UI screens

1. **Library** — project cards, create project, storage usage, empty state.
2. **Import** — file picker, validation results, role mapping, channel names.
3. **Player / Mixer** — transport, channel strips, loop controls.
4. **Channel details** — name, role, offset, gain, pan.
5. **Export** — format choice, progress, share/save.
6. **Settings** — theme, keep screen on, storage usage, licenses, about.

### Design principles

- One primary action per screen.
- Channel strips fit four stems on a phone; more stems scroll horizontally.
- Large touch targets for sliders and transport.
- Color is never the only indicator for mute/solo.
- Dark and light themes.
- All strings in resources.

## 13. Local data model

### Room entities

**projects**
- `id` (UUID, primary key)
- `name`
- `created_at`
- `updated_at`
- `duration_ms`
- `sample_rate`
- `last_position_ms`
- `loop_start_ms`
- `loop_end_ms`
- `loop_enabled`
- `master_gain`

**stems**
- `id` (UUID, primary key)
- `project_id` (FK, indexed)
- `role` (vocals, drums, bass, guitar, keys, other, custom)
- `display_name`
- `file_path`
- `format`
- `sample_rate`
- `channels`
- `duration_ms`
- `offset_ms`
- `gain`
- `pan`
- `muted`
- `soloed`
- `sort_order`

### Preferences

- Theme.
- Keep screen on.
- Last opened project.

No user accounts, no analytics database, no network state.

## 14. Privacy, security and legal

- No `INTERNET` permission.
- No analytics SDKs, no ads, no crash-reporting SDK that transmits data.
- Local diagnostic logs can be shared manually by the user.
- No account, email or personal data collected.
- Files stay in app-private storage until the user deletes the project.
- Deleting a project removes its files.
- Uninstalling the app removes all app-private data.
- The app does not bundle or provide music. Users must supply stems they have the right to use.
- The app must not reuse Moises code, assets, branding, API keys or backend endpoints.
- Third-party dependencies must use permissive licenses compatible with free distribution.

## 15. Accessibility

- TalkBack labels for every control.
- Minimum 48 dp touch targets.
- Content descriptions for channel strips and transport.
- Scalable text without layout breakage.
- Color contrast meeting WCAG AA.
- Mute/solo states exposed as accessibility states, not color alone.
- No time-limited interactions.

## 16. Testing strategy

### Unit tests

- Filename-to-role mapping.
- Project duration and padding math.
- Gain/pan/mute/solo math.
- Offset sample conversion.
- WAV header writer.
- Room DAO and repository behavior.

### Instrumentation tests

- Import 4 stems and open the project.
- Play, pause, seek and loop.
- Change gain/pan/mute/solo while playing.
- Export WAV and M4A and verify the files open.
- Delete a project and verify files are removed.

### Audio tests

- Generate four WAV click tracks with known offsets; assert sample alignment.
- Generate FLAC stems and repeat.
- Null test: render a mix offline and compare against the expected sum; assert RMS difference is below 0.1 dB.
- 8-stem, 30-minute stress test for memory and CPU.
- Seek and loop stress test for drift.

### Device matrix

- Android 8, 12, 15 and 16.
- Low-end, mid-range and high-end devices.
- At least one device with a 48 kHz output path and one with 44.1 kHz.

### UI tests

- Compose tests for library, import, mixer and export.
- Accessibility scanner.
- Dark/light theme screenshots.

## 17. Milestones

| Milestone | Deliverable | Acceptance criteria |
|---|---|---|
| M0 | Project skeleton, import wizard, 4-stem playback | Four WAV stems play in sync |
| M1 | Mixer, transport, background service | Gain/pan/mute/solo work without clicks; background playback works |
| M2 | Library, Room persistence, project lifecycle | Projects survive restart; delete removes files |
| M3 | Export WAV and M4A | Export matches live mix; null test passes |
| M4 | Loop, offset nudge, accessibility, polish | Loop and offset tests pass; accessibility scan clean |
| M5 | Release build | APK under 25 MB; no network permission; signed release |

## 18. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Codec/device variability | High | Test on a device matrix; use platform codecs; clear error messages |
| Sync drift | High | One sample clock; pre-roll; automated click-track tests |
| MP3/AAC encoder delay | Medium | Manual offset nudge; document limitation; auto-offset in v2 |
| Large files and storage | Medium | Copy with progress; show storage usage; delete cleans up |
| SAF permission loss | Medium | Copy imported files into app storage |
| 8-stem performance | Medium | Ring buffers, decode-ahead, stress test, lower buffer on weak devices |
| Scope creep toward AI | High | This document defines v1; separation and detection are explicitly out of scope |
| User content rights | Medium | In-app notice that users must own the rights to their stems |
| F-Droid licensing | Low | Choose an OSI-approved license if F-Droid distribution is used |

## 19. Distribution and cost

- Primary: direct APK and F-Droid (free).
- Optional: Google Play, which requires a one-time $25 developer registration.
- No server, database, storage or API costs.
- No ads, purchases or subscriptions.
- Development time and test devices are the only real costs.
- If F-Droid distribution is chosen, the app source must be open source under an OSI-approved license; MIT is the default recommendation.
- If the app is distributed as proprietary freeware, it can still be free of charge through direct APK distribution.

## 20. Decisions recorded

| Decision | Choice | Rationale |
|---|---|---|
| AI | None | Product explicitly excludes separation and detection |
| Network | No `INTERNET` permission | Fully offline and private |
| Backend | None | Zero runtime cost |
| Accounts | None | No personal data, no auth complexity |
| Stems source | User-supplied files | No AI and no content licensing |
| Platform | Android only | Fastest path; audio stack is platform-specific |
| UI | Jetpack Compose | Modern, accessible, avoids reference layouts |
| Audio decode | `MediaCodec` + `MediaExtractor` | Platform APIs, no FFmpeg, small APK |
| Audio output | Single `AudioTrack` + float mix | Sample sync and per-stem control |
| Stem formats | WAV, FLAC, MP3, M4A/AAC | Common user formats |
| Sample rates | 44.1 and 48 kHz | Covers typical music files |
| Export | WAV + M4A/AAC | Platform encoders, no extra libraries |
| Offset | Manual nudge in v1 | Compressed formats may need correction |
| Auto-offset | v2 | Requires envelope cross-correlation work |
| Pitch/speed | v2 | Requires time-stretch; out of v1 scope |
| Metronome | v2 | Not required for the core mixer loop |
| Loop | v1 | High practice value, low complexity |
| Distribution | APK/F-Droid; Play optional | Keeps the product free |
| Quota | None | Local app, no server cost |

## 21. Clean-room note

The decompiled Moises APK is used only as a reference for the post-separation mixing experience. Soundify Stem Mixer will not reuse its source code, smali, layouts, drawables, audio, branding, signing key, API key, Firebase project or backend. All code, copy and visual design are original.

## 22. What this product is not

- It is not a song separator.
- It is not a Moises replacement for users who only have a finished song.
- It is not a cloud service.
- It is not a subscription product.

The product promise is simple: **bring your stems, mix them on your phone, for free, offline.**
