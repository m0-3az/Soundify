# Evaluation harness

`SpikeAudioEvalTest.kt` is not part of the Gradle test source set and must not
ship. It runs the real `StreamingCleaner` over every `.mp4` in the app's
`files/spike-in` external directory and copies the four stems plus `stats.txt`
to `files/spike-out`.

To use it, copy it into `app/src/androidTest/java/com/soundify/app/` temporarily,
install the debug app and test APK, push the corpus into `spike-in`, then run:

```
adb shell am instrument -w \
  -e class com.soundify.app.SpikeAudioEvalTest \
  com.soundify.app.test/androidx.test.runner.AndroidJUnitRunner
```

Remove it from the source set again before running `connectedDebugAndroidTest`
or building a release; it depends on corpus state that is not present in CI.
