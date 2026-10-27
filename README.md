# Vanta Studio

A video editor for Android, written in Kotlin with Jetpack Compose and Media3.
Dark, minimal interface with its own identity (graphite and violet). Spanish and English.

> **Status: 0.1.0-alpha.** Everything described here is implemented in code. The project is
> compiled and unit-tested by GitHub Actions on every push, but it has **not yet been run on
> a physical device by the author**. Read [CHANGELOG.md](CHANGELOG.md) for the honest list of
> what is verified and what is not.

## Install the APK

Download `VantaStudio-<tag>.apk` from the [Releases page](../../releases), allow installing from
your browser or file manager, and open it. Requires Android 10 (API 29) or newer.
Release builds are signed with the debug key unless the maintainer configures a release key
(see *Signing* below), so Android may show an "unknown developer" warning.

## What it does

- **Projects**: create, rename, duplicate, delete (with confirmation); thumbnails, duration and
  last-modified date; aspect ratios 16:9, 9:16, 1:1, 4:5 and custom; 24/30/60 fps.
- **Timeline**: magnetic main track plus free text/sticker layers and up to four audio lanes;
  pinch zoom, scrolling with a fixed playhead, snapping, separate trim handles and move gestures,
  filmstrip thumbnails and audio waveforms.
- **Editing**: split, trim, delete, duplicate, freeze frame, rotate, flip, speed (0.1x–8x),
  volume/mute/fades, crop, transform (position, scale, rotation, opacity) with keyframes and easing,
  undo/redo.
- **Look**: 21 filters, 10 effects, colour adjustments, 6 transitions.
- **Layers**: text (presets, fonts, outline, shadow, background, enter/exit animations),
  stickers, shapes, frames and images.
- **Audio**: import audio, place it freely, trim, split, fades.
- **Export**: H.264/AAC MP4 at 480p–4K (as far as the device allows), 24/30/60 fps, quality
  presets, optional audio, custom file name, real progress, cancel, free-space check, verification
  of the written file, saved to `Movies/Vanta Studio`, share/open when done.
- **Safety**: atomic autosave with backup, recovery after a crash, missing-media and corrupt-project
  handling, project format migration.

## Not (yet) included

See the limitations list in [CHANGELOG.md](CHANGELOG.md). Highlights: no speed curves, no automatic
captions, no cloud assets, no chroma key, no masks, no multi-track video compositing (video is a
single magnetic track; overlays are images, text and shapes).

## Build

```
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:assembleDebug       # app/build/outputs/apk/debug/
./gradlew :app:assembleRelease     # release APK
```

Needs JDK 17 and the Android SDK (platform 35). CI does this on `ubuntu-latest`.

### Signing

No key, password or keystore is in the repository (`*.jks`, `*.keystore` and
`keystore.properties` are git-ignored). The release workflow reads, if present, the repository
secrets `VANTA_KEYSTORE_BASE64`, `VANTA_KEYSTORE_PASSWORD`, `VANTA_KEY_ALIAS` and
`VANTA_KEY_PASSWORD`; otherwise it signs with the debug key. Locally you can export
`VANTA_KEYSTORE_FILE`, `VANTA_KEYSTORE_PASSWORD`, `VANTA_KEY_ALIAS`, `VANTA_KEY_PASSWORD`.

### Publishing a release

Push a tag (`git tag v0.1.0-alpha && git push origin v0.1.0-alpha`) or run the **Release** workflow
manually; it builds the APK and attaches it to a GitHub Release together with a SHA-256 file.

## Documentation

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — modules, data flow, preview/export pipeline and decisions.
