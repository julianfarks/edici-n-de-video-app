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
- The app compiles and the APK is produced by GitHub Actions (45 JVM unit tests pass).
- Real export on an Android 13 emulator (CI, `instrumented.yml`): a still with a filter and a text
  layer, and a video split in two with the second half at 2x speed, both exported through the
  Transformer pipeline to MP4; the result's duration, resolution, audio track and a decodable
  frame were checked. Missing-source and empty-project exports fail with the right error.

### Not verified
- The interface itself (Compose screens, timeline gestures, pinch zoom, trim handles) has not been
  exercised by any automated UI test or by the author on a device.
- Live preview (CompositionPlayer) and preview/export visual parity.
- Individual effects/transitions/filters were exported only through the code path, not inspected
  frame by frame. Export of long, 4K or 60 fps projects and audio-lane mixing are untested.
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
Add UI tests (Compose) and frame-level export checks, profile the timeline on low-end devices, speed
curves, picture-in-picture video layer, captions.
