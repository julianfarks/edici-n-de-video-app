# Architecture

## Layers

```
ui/        Jetpack Compose screens (home, editor, export, settings) + ViewModels
domain/    Pure Kotlin: Project model, Timeline operations, EditHistory, animation maths,
           export maths, effect parameters. No Android imports -> fast JVM unit tests.
engine/    Media3: PreviewEngine (CompositionPlayer), ExportEngine (Transformer),
           CompositionFactory, custom GL effects, audio processors, layer renderer.
data/      Project storage (atomic JSON), media import/probe, thumbnails, waveforms, settings.
```

## Project model
`Project` is an immutable tree: clips (main track), audio clips (lanes), text layers and overlays.
All edits are pure functions `Project -> Project` in `Timeline`, so undo/redo is a stack of
snapshots (`EditHistory`). Continuous gestures (a slider drag, a trim) update the state live and
commit one history step when released.

Projects live in `files/projects/<id>/` as `project.json` (+ `.bak`), `cover.jpg` and `media/`.
Saves write a temp file, then rename. `ProjectCodec` carries a `formatVersion`; older files are
migrated on load, newer ones are refused rather than silently truncated.

## One graph for preview and export
`CompositionFactory` turns a `Project` into a Media3 `Composition`. The same function feeds
`CompositionPlayer` (preview, reduced resolution) and `Transformer` (export, full resolution), which
keeps the two close. Parameter edits (colour, transform, keyframes, volume) are read live through
`LiveProject` by the effects, so the pipeline is **not** rebuilt; structural edits (trim, split, speed,
add/remove) rebuild it (`PreviewEngine.structuralKey`).

Custom OpenGL shaders: an uber-shader for colour grade, filters and effects, and a motion shader for
transform and transitions. Text and overlays are rasterised to bitmaps (`LayerRenderer`); preview shows
them with a Compose canvas, export burns them in with `OverlayEffect` using the same animation maths.

## Why Media3 `@UnstableApi`
Transformer, CompositionPlayer and the effects API are marked unstable. They are the only on-device
route to real hardware-accelerated export with custom effects without bundling FFmpeg, so the project
opts in deliberately and pins the Media3 version.

## Timeline UI
A fixed centre playhead; the content scrolls underneath and the scroll offset is the time. Pinch zoom
is an initial-pass pointer interceptor so it works over clips. Trim handles and move gestures are
separate hit targets. Thumbnails are drawn only for the visible range.

## Testing
- `app/src/test`: JVM unit tests for the domain layer.
- `app/src/androidTest`: instrumented export test on generated media.
- CI: `.github/workflows/build.yml` (tests + debug APK), `release.yml` (release APK + GitHub Release).
