# Changelog

## 0.1.0-alpha

First functional version.

### Implemented
Everything listed in the README. Specifically:
projects & home screen, editor with multilayer timeline, clip tools, keyframes, filters/effects/
transitions (custom GLSL), text/sticker/shape/frame/image layers, audio lanes, undo/redo,
autosave/recovery, export pipeline, settings, English and Spanish.

### Verified
- Pure logic (timeline operations, undo/redo, project codec and migration, keyframes/easing,
  export maths, file-name sanitising, snapping) by JVM unit tests in CI.
- The app compiles and the APK is produced by GitHub Actions (see the workflow runs).

### Not verified
- Behaviour on a real device or emulator: gestures, GPU effects, preview/export parity,
  audio sync, export of long or 4K projects. An instrumented export test exists
  (`app/src/androidTest`) but has not been run in CI yet.
- Performance on low-end devices.

### Known limitations
- Speed is constant per clip (no speed curves). Speed is video-only; stills have no speed.
- Single video track: no picture-in-picture video, no chroma key, no masks, no blend modes.
- Preview is an approximation (lower resolution, text/stickers drawn by a Compose overlay);
  the exported file uses the full-quality path, so tiny differences are possible.
- Media is copied into the app's storage on import (no duplicate-free linking).
- No automatic captions, no background removal, no cloud assets.
- Stickers are built-in vector/emoji-style shapes, not a downloadable library.
- Export is H.264/AAC only; 4K/60 depends on the device encoder.

### Next
Run the instrumented test on an emulator in CI, profile the timeline on low-end devices, speed
curves, picture-in-picture video layer, captions.
