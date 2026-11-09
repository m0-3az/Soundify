#!/usr/bin/env bash
# Fetch the 117 MB separation model used by Clean Video.
# The model is too large for GitHub's normal file limit, so it ships as a
# release asset. Run this from the repository root before building from source.
set -euo pipefail
cd "$(dirname "$0")/.."
mkdir -p app/src/main/assets
curl -L --fail --retry 3 \
  -o app/src/main/assets/mrx_mask.onnx \
  "https://github.com/m0-3az/Soundify/releases/download/v0.2.0/mrx_mask.onnx"
echo "model written to app/src/main/assets/mrx_mask.onnx"
