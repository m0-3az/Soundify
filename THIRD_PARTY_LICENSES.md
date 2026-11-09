# Third-party notices

Soundify itself is MIT-licensed (see `LICENSE`). It includes or depends on the
following components.

## MRX / Cocktail Fork Separation

- Project: https://github.com/merlresearch/cocktail-fork-separation
- License: MIT
- Copyright (c) 2023 Mitsubishi Electric Research Laboratories (MERL)
- Model: `mrx_mask.onnx`, a spectral-mask export of the MRX checkpoint.
- Paper: *The Cocktail Fork Problem: Three-Stem Audio Separation for Real-World
  Soundtracks* (Petermann et al., ICASSP 2022).
- Training data: Divide and Remaster (DnR), Zenodo record 6949108.

The model file is **not** committed to this repository because it is larger than
GitHub's 100 MB file limit. It is included in each release APK, and is attached
to the release as a separate asset for source builds.

## ONNX Runtime

- Project: https://github.com/microsoft/onnxruntime
- License: MIT
- Copyright (c) Microsoft Corporation

## FFmpegKit (maintained fork)

- Project: https://github.com/ffmpegkit-maintained/ffmpeg
- Artifact: `dev.ffmpegkit-maintained:ffmpeg-kit-audio:8.1.7`
- License: LGPL-3.0
- The app links FFmpegKit dynamically. The corresponding FFmpeg source and build
  scripts are available from the project link above.

FFmpeg is a trademark of Fabrice Bellard, originator of the FFmpeg project.

## AndroidX / Jetpack Compose / Kotlin

Licensed under the Apache License 2.0. See the respective project repositories.

## Fonts and icons

Material Icons are provided by AndroidX Compose Material and are licensed under
the Apache License 2.0.
