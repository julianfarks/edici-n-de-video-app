# Changelog

## 0.2.1-alpha

Fixes the black preview ("Error de vista previa: -9223372036854775807").

### Cause
The number is Media3's `C.TIME_UNSET`. `CompositionPlayer` adds up each item's presentation
duration and requires it to be positive; it throws `IllegalStateException("-9223372036854775807")`
when an item has no source duration. The composition factory set a duration only for still images,
never for video clips, audio clips or the silent spacers, so **every video preview failed**. Export
does not need the value, which is why the export tests passed while the preview never worked.

### Changed
- `CompositionFactory`: every video, audio and silence item of the preview gets its full source
  duration (never smaller than the part used, never an unset value).
- `PreviewEngine`: "first frame drawn" signal, `retry()`, a newer seek replaces an older pending one,
  all positions normalised to the valid range, invalid player positions ignored, stale rebuilds dropped.
- Editor: "Preparando vista previa…" while the first frame is prepared; a readable error card with the
  technical detail and a **Reintentar** button instead of a permanent black screen.
- Contrast: text outside Material surfaces defaulted to black (the project name); the theme now sets
  the content colour, the project name is explicit, and the faint text colour was raised to ≥ 4.5:1.
- The timeline hint is closable, disappears by itself after 8 s and, once seen or closed, does not return
  (it can be re-enabled in Settings).

### Verified (and how)
- **Unit tests, all pass**: valid source durations for unset/zero/unbounded metadata, and WCAG
  contrast ratios of the palette on every background it is used on.
- **Android 13 emulator, 11 of 11 pass**, with a generated 146 s video (like the one in the report) and a
  vertical one, drawing into a surface whose pixels are read back:
  - first frame visible (mean brightness 128, not black), no error;
  - play (position advances, picture changes), pause (position holds, picture stays);
  - jumps to 30 s / 100 s / 140 s each draw a new, non-black frame;
  - frame step forward/back; negative and huge positions are clamped; 40 rapid seeks → the last wins;
  - switching to a vertical clip: picture changes, black bars at the sides (fitted, not stretched), playing across the cut;
  - save, close, reopen: the media is still there and plays;
  - a missing source reports an error (not an endless black screen) and recovers when fixed.
- The earlier real-SurfaceView run of the same code also rendered the first frame with no error.

### Not verified
- Not yet tried by hand on a physical phone (the report's device). The emulator uses software
  codecs; a vendor decoder on a specific phone could still fail in its own way, and the new error card
  would then show the real message.
- Preview of 4K/HEVC/10-bit camera footage specifically; the test videos are H.264 640x360.
- The contrast check covers the palette, not every screen by eye.

## 0.2.0-alpha

AI Studio, rhythm-synchronised light effects, automatic subtitles and a text-instruction assistant.
Full detail of what is real, what needs a connection and what is not built: [docs/AI.md](docs/AI.md).

### Added
- **AI Studio hub** with 12 tools and an honest status per tool (available / needs model / not implemented).
- **Beat Sync**: real DSP rhythm analysis (FFT, spectral flux, tempo, beat tracking, energy, pauses,
  sections), markers on the timeline, sensitivity control.
- **Light effects** (17 types) on an editable timeline, with a flicker-safety mode; one shader shared by
  preview and export. Auto-placement on pulses/hits/pauses; every event can be moved, trimmed, changed.
- **Automatic edit to the music** (soft / cinematic / dynamic / intense / very fast), "another version",
  restore original; the result is ordinary editable clips and one undo step.
- **Silence removal** with a review list and a ripple delete that keeps audio, text, effects and subtitles aligned.
- **Automatic subtitles**: offline Vosk (Spanish first, model downloaded with consent) or optional cloud
  service with your own key; editable cues, 8 styles, spoken-word highlight from engine timestamps,
  SRT export, burnt into the export.
- **Instruction assistant**: offline Spanish rules and an optional OpenAI-compatible service with your
  own key (consent dialog showing the exact text, monthly call limit). Proposal → preview → apply →
  cancel/undo; every operation is validated against a whitelist.
- **Local templates** (editable operation lists, no media).

### Verified (and how)
- **JVM unit tests in CI: all pass** (Beat DSP on synthetic signals, ripple/silence, auto-edit, flash
  safety, subtitle mapping and styles, assistant interpretation/validation/execution, cloud-reply parsing,
  templates, project codec).
- **Android 13 emulator in CI, 7 of 7 pass**, with real generated media:
  - *Flashes in the export*: two flashes placed at exact times, the second moved by hand; the exported
    file's frame luminance is ≈255 at both new instants (background ≈11) and back to dark at the old
    position of the moved flash.
  - *Real song*: a generated 120 BPM drum track was decoded from an audio file and analysed
    (**120.25 BPM**, 16 beats, 31 hits); flashes synchronised to it appear in the exported frames
    (luminance 141–157 at the hits vs 8.9 in the dark frame).
  - *Spanish speech*: synthetic speech (espeak-ng) with a 3 s pause → model downloaded → Vosk
    recognition → cues with measured word times (first at 0.3 s, second after the pause) → manual
    correction of a cue → export with and without subtitles: **1.26 %** of the bottom-third pixels change
    strongly at the cue against **0.0 %** in the silence.
  - Existing export tests still pass (video with speed change, stills with filter and text, clean failures).

### Not verified / honest limits
- **Recognition quality**: with robotic synthetic speech the small Spanish model got about half of the
  key words ("amigos" yes, "hola/bienvenidos" no). That says little about real voices, but it also
  says nothing good: the small model is general Spanish, not tuned to Colombian speech, and is
  sensitive to noise and music. **Always review subtitles.** Larger models or a cloud service are better.
- The interface has still not been used by hand on a physical device; Compose screens and gestures have no automated UI test.
- The cloud subtitle engine and the language-model assistant are implemented and their request/response
  handling is unit-tested, but they have **not been run against a real service** (no key in CI).
- Real music (not a synthetic drum loop) has not been analysed in CI; tempo accuracy on rubato or ambient music will be lower.
- Auto-edit and the assistant are verified by unit tests (clips modified, still plain editable clips, original restorable, one undo step), not yet by an exported-video check.
- The flash preview uses the same shader as the export, but preview vs export frame parity is not measured.

### Not implemented (listed in the app as such)
Best moments, subtitle translation, text to speech, object tracking, smart reframing, background removal, video enhancement. Architecture notes in [docs/AI.md](docs/AI.md).

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
