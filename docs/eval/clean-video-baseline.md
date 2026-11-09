# Clean Video baseline — v0.3.0 native-level inference

Captured before the 0.4 inference-contract fix, on the `Spike/` corpus.

- Pipeline: v0.3.0 (`5925d14`) behaviour — separation at native level, 6 s chunks, 1 s overlap, stems at -6 dB headroom, clean mix matched to -16 LUFS.
- Corpus: 37 MP4s, 28.1 min of audio, all AAC stereo at 44.1/48 kHz.
- Run: real app pipeline through the instrumentation harness on an API 35 x86_64 emulator, one process, 1,214.7 s wall time.
- Outputs: `Spike/out/<clip>/{clean,voice,sfx,music}.wav` (git-ignored) with `stats.txt` per clip.

## Run result

- 32 of 37 clips completed.
- 5 clips failed with `java.lang.IllegalStateException: missing overlap tail`:
  `4_5857057432815017730`, `Video by fragh.1 [Dd4tEsFuiDh]`, `Video by mas1dar [Dbv4-Aftluz]`, `Video by shasmatar [De2hgw3xxnO]`, `Video by youssefbennasreldin [Deo_LM0BB_0]`.
- Root cause: the streaming loop advanced by the hop after a final chunk, scheduling a bogus chunk with no tail. Fixed in `d09f7dd`; the 5 clips are the regression set.

## Loudness

- Integrated loudness: -16.22 to -15.99 LUFS across 32 clips (target -16 ±1).
- True peak: -7.45 to -1.50 dBTP (target <= -1.5).
- No clipping; the near-silent guard did not trigger on this corpus.

## Clips ranked by music presence

`music-voice` is the music-stem RMS minus the voice-stem RMS; both are stored -6 dB, so the gap is level-invariant. A gap near 0 dB means music is as loud as speech and the clip is worth judging; a gap below -12 dB means the model found almost no music.

| # | Clip | Dur s | music-voice dB | music RMS | voice RMS | clean RMS | LUFS | dBTP |
|---:|---|---:|---:|---:|---:|---:|---:|---:|
| 1 | Video by g_2f0 [Dcpd645KNRK] | 19.5 | -1.8 | -28.1 | -26.3 | -19.0 | -16.11 | -1.50 |
| 2 | Video by aske_ladd_1 [DdHRJMMxLpM] | 43.5 | -3.8 | -29.6 | -25.8 | -18.6 | -16.07 | -1.50 |
| 3 | Video by i3rriw [DecUfdmoOPy] | 22.7 | -5.5 | -30.7 | -25.1 | -15.9 | -16.22 | -1.50 |
| 4 | Video by a.zur1 [DcguUakscyx] | 59.8 | -7.3 | -32.4 | -25.1 | -18.5 | -16.05 | -1.50 |
| 5 | Video by i3rriw [Dd4Ll-RoPLh] | 17.3 | -7.8 | -32.7 | -24.9 | -18.4 | -16.01 | -1.50 |
| 6 | Video by i3rriw [DeXJs8mItSV] | 49.2 | -8.1 | -31.6 | -23.5 | -18.7 | -16.01 | -1.60 |
| 7 | Video by i3rriw [Dd9XHfroo_J] | 68.1 | -8.5 | -32.2 | -23.8 | -18.7 | -16.05 | -1.50 |
| 8 | Video by sutoor_lines [DfRpkP1pMZW] | 44.1 | -8.8 | -33.2 | -24.4 | -20.0 | -16.00 | -2.03 |
| 9 | Video by sutoor_lines [DfSFEwQpb7Y] | 18.6 | -9.4 | -34.3 | -25.0 | -20.3 | -16.12 | -2.11 |
| 10 | Video by safa7iq [DfMv5PWNnTe] | 36.9 | -9.7 | -35.5 | -25.8 | -20.5 | -16.00 | -2.04 |
| 11 | Video by z3k_rr [DekF4h8oD7j] | 163.9 | -9.8 | -34.2 | -24.4 | -18.7 | -16.00 | -5.80 |
| 12 | Video by i3rriw [De9u2ETIMrB] | 29.9 | -10.5 | -34.1 | -23.6 | -18.8 | -16.00 | -1.54 |
| 13 | Video by l25k_ [DTfs-GUiluW] | 73.0 | -10.8 | -34.9 | -24.2 | -18.7 | -16.07 | -1.50 |
| 14 | Video by 3uiri [DYALJ71sDU4] | 42.1 | -11.4 | -34.5 | -23.0 | -18.3 | -16.00 | -1.62 |
| 15 | Video by sutoor_lines [DfScnN-JBW8] | 49.5 | -11.6 | -35.3 | -23.7 | -18.7 | -15.99 | -2.05 |
| 16 | Video by tilekmattegin [DeFuWgeiNm1] | 12.9 | -11.8 | -35.2 | -23.4 | -18.0 | -16.00 | -1.83 |
| 17 | Video by i7l.f [DXJ99JwjcwD] | 53.4 | -11.8 | -34.9 | -23.2 | -18.6 | -16.00 | -7.45 |
| 18 | Video by i3rriw [DfU3HOOIGu_] | 26.9 | -11.9 | -36.0 | -24.1 | -19.0 | -16.00 | -1.74 |
| 19 | Video by sutoor_lines [DfTLDnuJH8N] | 174.5 | -12.0 | -35.5 | -23.5 | -19.1 | -16.00 | -2.37 |
| 20 | Video by sutoor_lines [DfP-cMQJV_W] | 33.8 | -12.2 | -36.4 | -24.3 | -19.2 | -16.00 | -1.63 |
| 21 | Video by sutoor_lines [DfR4dzApRiX] | 49.3 | -12.3 | -36.2 | -23.9 | -19.1 | -16.00 | -2.02 |
| 22 | Video by sutoor_lines [DfRvR7IpygL] | 106.5 | -12.4 | -36.6 | -24.2 | -19.5 | -16.01 | -2.09 |
| 23 | Video by sutoor_lines [DfSpNa9ps3u] | 27.5 | -12.9 | -36.6 | -23.7 | -18.9 | -16.00 | -2.36 |
| 24 | Video by sutoor_lines [DfQkxmSJuO3] | 52.7 | -13.0 | -36.6 | -23.6 | -19.1 | -16.00 | -2.36 |
| 25 | Video by darayaet [DcjUYuatl56] | 24.8 | -13.0 | -37.1 | -24.2 | -19.3 | -16.00 | -2.47 |
| 26 | Video by stolen._meme [DRrru7GiF1t] | 12.2 | -14.1 | -41.0 | -26.9 | -18.7 | -16.01 | -2.28 |
| 27 | Video by shurfat_alshi3r [Ded9QxIInDv] | 22.9 | -14.1 | -38.9 | -24.7 | -20.0 | -16.00 | -2.62 |
| 28 | Video by 3uiri [De7Y1Jyh-jE] | 17.8 | -15.6 | -39.1 | -23.5 | -18.7 | -16.00 | -2.30 |
| 29 | Video by fragh.1 [DZODXTUuhUM] | 54.3 | -15.8 | -38.2 | -22.5 | -17.7 | -15.99 | -3.18 |
| 30 | Video by fragh.1 [DZut-CztXxV] | 14.0 | -16.6 | -39.7 | -23.1 | -18.0 | -16.00 | -1.96 |
| 31 | Video by 3tm.95 [Dcs6QpUs1bA] | 81.9 | -16.9 | -40.3 | -23.4 | -18.8 | -16.00 | -1.87 |
| 32 | Video by fragh.1 [DbtjcmMO4TV] | 21.3 | -19.5 | -41.8 | -22.3 | -17.6 | -15.99 | -2.32 |

## Notes for the 0.4 comparison

- This table is the A/B baseline. Phase 1 must keep every clip inside the loudness targets while moving separation quality; the music-heavy top of the table is the listening set.
- The 5 failed clips must complete after `d09f7dd`; a re-run of the corpus is the integration check.
- No SI-SDR is claimed here: the corpus has no ground-truth stems. Phase 0's ground-truth set supplies that number.
