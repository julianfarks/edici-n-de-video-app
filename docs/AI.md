# AI Studio: what is real, what needs a connection, what is not built

Everything below is implemented in code unless it says otherwise. No tool pretends: a tool that
needs a model or a connection says so in the app (status pill in AI Studio), and a tool that is not
built is listed as "not implemented" and does nothing.

## Working on the device (no connection)

| Tool | How it works | Known limits |
|---|---|---|
| **Rhythm analysis** (Beat Sync) | Decodes the audio with `MediaCodec`, FFT, log-band spectral flux, adaptive peak picking, autocorrelation tempo + dynamic-programming beat tracking (Ellis). Also energy curve, pauses, sections and tempo changes. | Tuned on synthetic and a few real tracks. Free-tempo music (rubato, ambient) gives a weak pulse grid; hits are still found. |
| **Synchronised light effects** | 17 effect types on an editable timeline (add, move, trim, duplicate, delete, intensity, colour, duration). One GLSL shader (`LightEventsEffect`) is part of the same Media3 composition used for preview **and** export. | "Safe mode" caps flashes at 3 per second and intensity at 0.6; with it off, you are responsible for photosensitivity. |
| **Automatic edit to the music** | Cuts on pulses/hits chosen by mode (soft, cinematic, dynamic, intense, very fast), sensitivity control, zoom/transition/effect accents. The original is kept (`Restore original`), "another version" changes the result, and the result is ordinary clips. | It cuts and re-samples the footage you already have; it does not understand what is in the picture. |
| **Silence removal** | RMS in dB over 20 ms hops, adaptive threshold between the noise floor and speech level, minimum pause and margin controls, review list with per-pause toggles, ripple delete that keeps audio, text, overlays, effects and subtitles aligned. | Needs ≥12 dB between speech and background; with constant noise it says it cannot separate them and changes nothing. Music is not "silence". |
| **Subtitles, offline** | Vosk (Kaldi) small models, downloaded on request with your consent (Spanish ≈ 40 MB). Word timestamps from the engine drive highlight, karaoke, progressive and bounce styles; SRT export; burnt into the export with the same renderer as the preview. | The small Spanish model is general Spanish, not tuned to Colombian or other regional speech; accuracy drops with noise, music and fast speech. Always review the text. |
| **Instruction assistant (offline rules)** | A transparent rule set turns Spanish instructions into a whitelist of operations (cut, delete range, speed, volume, mute, text, effect, aspect, filter, subtitle style, open a tool). You see the proposal, apply it as one undo step, preview, cancel back to the original. | It understands the phrases in the examples and close variants, not free language. It lists what it did not understand. |

## Needs a connection or an external service (your own account)

| Tool | What is needed | Privacy |
|---|---|---|
| Cloud subtitles | Any OpenAI-compatible `/audio/transcriptions` service and **your** API key. | Audio leaves the device. The app asks every time, names the host, and stores the key encrypted (Android Keystore). The key is never in the APK or the repository. |
| Instruction assistant (language model) | Any OpenAI-compatible `/chat/completions` service and **your** API key; monthly call limit you set. | Only the instruction and a text summary (durations, counts, flags) are sent, never media or file names. The exact text is shown before sending. The reply is JSON that is validated against the same whitelist; anything else is dropped and reported. |
| Speech model download | One HTTPS download from the model host. | Asked first; nothing else is uploaded. |

## Not implemented (architecture notes)

These need models or algorithms that are not in the app. They appear as "not implemented" and do nothing.

| Tool | What it would take |
|---|---|
| Best moments / highlights | Scene-change + motion + audio-energy scoring is feasible on device; not built. |
| Translation of subtitles | Cloud translation (consent flow already exists) or an on-device model; not built. |
| Text to speech | Android `TextToSpeech` can synthesise to a file; not built. |
| Object tracking, smart reframing | A tracker (e.g. KCF) or detector on frames, plus keyframe generation into the existing transform keyframes; not built. |
| Background removal | A segmentation model (e.g. ML Kit / MediaPipe) per frame and a mask compositor; not built. |
| Video enhancement / denoise | A restoration model; not built. Nothing in the app claims to enhance. |
| Templates | The project has a `templateId` field; no template library is shipped yet. |

## Verification

See the "Verified" section of [CHANGELOG.md](../CHANGELOG.md) for exactly what has been run, on
what, and what has not.
