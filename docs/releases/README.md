# Release checklist

Soundify releases are distributed as signed APKs on GitHub Releases. Users
update with Obtainium. There is no F-Droid or Play Store channel.

## One-time signing setup

The release key must never be committed, regenerated, or lost. Losing it means
installed users can never update.

1. Create a 4096-bit RSA key valid for a long period:

   ```bash
   keytool -genkeypair \
     -keystore soundify-release.jks \
     -alias soundify \
     -keyalg RSA -keysize 4096 -validity 10000 \
     -storepass:file storepass.txt \
     -keypass:file storepass.txt \
     -dname "CN=Soundify, O=Soundify, C=US"
   ```

2. Store the keystore and the password file in a password manager or encrypted
   offline backup. Keep a second offline copy.

3. Export the signing variables for the build:

   ```bash
   export SOUNDIFY_KEYSTORE=/path/to/soundify-release.jks
   export SOUNDIFY_KEYSTORE_PASSWORD_FILE=/path/to/storepass.txt
   export SOUNDIFY_KEY_PASSWORD_FILE=/path/to/storepass.txt
   export SOUNDIFY_KEY_ALIAS=soundify
   ```

Without these variables `assembleRelease` produces an unsigned APK; it never
falls back to the debug key.

## Build a release

1. Confirm the branch is clean and tests pass:

   ```bash
   ./gradlew testDebugUnitTest
   ./gradlew connectedDebugAndroidTest
   ```

2. Bump `versionCode` and `versionName` in `app/build.gradle.kts` and write
   `docs/releases/v<version>.md`.

3. Build and verify:

   ```bash
   ./gradlew assembleRelease
   $ANDROID_HOME/build-tools/<version>/apksigner verify --print-certs \
     app/build/outputs/apk/release/app-release.apk
   sha256sum app/build/outputs/apk/release/app-release.apk
   ```

4. Check the merged release manifest has no `INTERNET` and no
   `RECORD_AUDIO`.

5. Install on the test phone and run: import audio → trim → split → export →
   reopen. Export must be 44.1 kHz stereo.

## Publish

1. Name the asset `Soundify-<version>.apk`.
2. Create a GitHub Release with tag `v<version>`, attach the APK, and paste the
   SHA-256 plus the release notes.
3. Verify Obtainium sees the release and offers the update.
4. Keep the previous APK available as a fallback.

## Source builds

`app/src/main/assets/mrx_mask.onnx` is not committed (117 MB). Download it from
the release assets and place it there before building.

## Never commit

- `*.jks`, `*.keystore`
- `storepass.txt`, `keypass.txt`
- `release-signing.properties`
- `local.properties`
