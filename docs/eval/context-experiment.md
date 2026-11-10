# 0.5.0 chunk-context experiment

Date: 2026-11-10
Branch: `feat/clean-media-audio`
Pipeline: 0.4.0 inference contract (-27 LUFS model input, 6 dB stem
headroom, clean mix at -16 LUFS / -1.5 dBTP)

## Question

Does a longer model context reduce seam artifacts or improve separation
enough to justify the memory cost? The three agreed configurations were:

- A: 6 s chunk / 1 s overlap (0.4.0 baseline)
- B: 15 s chunk / 2 s overlap (requested candidate)
- C: 10 s chunk / 2 s overlap (added after B failed)

The "whole file when ≤15 s" branch does not apply to this set: the six reels
are 17.3–59.8 s long.

## Corpus

The six music-heavy Arabic reels at the top of
`docs/eval/clean-video-baseline.md`, which is the 0.4 listening set:

| # | Clip | Duration |
|---:|---|---:|
| 1 | Video by g_2f0 [Dcpd645KNRK] | 19.53 s |
| 2 | Video by aske_ladd_1 [DdHRJMMxLpM] | 43.50 s |
| 3 | Video by i3rriw [DecUfdmoOPy] | 22.69 s |
| 4 | Video by a.zur1 [DcguUakscyx] | 59.76 s |
| 5 | Video by i3rriw [Dd4Ll-RoPLh] | 17.30 s |
| 6 | Video by i3rriw [DeXJs8mItSV] | 49.16 s |

Total: 211.9 s. No other corpus clips were run.

## Method

- Emulator: `sdk_gphone64_x86_64`, API 35, 2.5 GB RAM,
  `dalvik.vm.heapgrowthlimit=192m`, `dalvik.vm.heapsize=576m`,
  app `largeHeap=true`.
- `CHUNK` / `OVERLAP` in `StreamingCleaner.kt` changed temporarily per run;
  nothing was committed. The harness is `tools/eval/SpikeAudioEvalTest.kt`,
  copied into `androidTest` for the run and removed afterwards.
- Baseline A is the existing `Spike/out` run from 0.4.0. Memory for A was
  re-measured in this session with the same six clips.
- Peak memory is PSS sampled every 2 s with `dumpsys meminfo`; the true peak
  may be slightly higher than the sample.
- Wall time is the harness's `elapsedMs`, per clip, including decode.

## Results

### 15 s / 2 s — rejected

All six clips failed immediately with:

```
java.lang.OutOfMemoryError: Failed to allocate a 10604752 byte allocation
with 10357088 free bytes and 10114KB until OOM,
target footprint 603979776, growth limit 603979776
```

The failure is the Java heap ceiling, not the 2.5 GB of system RAM. Raising
the chunk to 15 s is not shippable on this device class. No listening set was
produced for B.

### 6 s / 1 s vs 10 s / 2 s

| Clip | A elapsed | C elapsed | A clean RMS | C clean RMS | A/C difference RMS |
|---|---:|---:|---:|---:|---:|
| g_2f0 | 17.82 s | 15.55 s | -19.54 | -19.53 | -49.7 dB |
| aske_ladd_1 | 34.43 s | 34.40 s | -19.17 | -19.17 | -46.5 dB |
| DecUfdmoOPy | 16.77 s | 16.54 s | -17.08 | -17.32 | -34.2 dB |
| a.zur1 | 46.94 s | 46.47 s | -18.59 | -18.59 | -46.2 dB |
| Dd4Ll-RoPLh | 14.56 s | 12.94 s | -18.50 | -18.50 | -44.8 dB |
| DeXJs8mItSV | 36.69 s | 37.60 s | -18.69 | -18.68 | -39.9 dB |
| **Total** | **167.2 s** | **163.5 s** | | | |

- Wall time: 2.2% faster with 10 s / 2 s. Not a meaningful win.
- Clean level: within 0.24 dB on every clip; peaks stay at or below -1.5
  dBFS on five clips and -1.58 dBFS on the sixth. No clipping.
- Difference signal: 34–50 dB below full scale, i.e. the two configurations
  do produce different audio, most on `DecUfdmoOPy`.
- Peak PSS: **990 MB** for 6 s / 1 s, **1.24 GB** for 10 s / 2 s. The longer
  context costs about 250 MB more resident memory.

## Listening set

`Spike/context-previews/`:

- `01-g_2f0/` … `06-i3rriw_DeXJs8mItSV/`
- each folder: `A-6s1s-{clean,voice,music}.mp3` and
  `B-10s2s-{clean,voice,music}.mp3`
- stems are restored +6 dB on preview, matching Save
- start with clean, then voice and music; listen for warbling, bleed and
  swallowed detail at seams

## Recommendation

Keep 6 s / 1 s unless the owner hears a clear improvement in the 10 s set.

Even if 10 s sounds better, it adds ~250 MB PSS and only saves 2% wall time,
and it has not been validated on the SM-A576B with a 10-minute file. The
15 s configuration is rejected on memory grounds. A 0.4.1 context change is
only justified by a clear listening win plus a phone memory check; otherwise
the current defaults stay and 0.5.0 ships with audio input only.
