# DriveApex

DriveApex is an Android application for BYD-oriented dynamic EV drive sound.

## Current milestone

- Kotlin Android application using Gradle.
- Landscape-first UI suitable for an in-vehicle display.
- Real-time layered EV sound renderer.
- Premium electric-GT sound profile with multiple controllable layers.
- Audio parameters driven by motor speed/RPM, accelerator load and vehicle speed.
- Adaptive sound scenes with continuous transitions.
- Sound DNA presets for GT, balanced and hyper personalities.
- Adaptive stereo spatial mix model with cabin/exterior bus controls.
- Expanded telemetry model with brake and regenerative-braking state.
- Sample-grid renderer architecture with smooth selection of up to three nearby samples.
- Pitch tracking metadata and transient sample support for launch/regen events.
- Sample-bank manifest prepared for original/appropriately licensed recordings.
- Procedural fallback remains available until real sample assets are added.
- GitHub Actions pipeline for APK builds.

## Sound architecture

DriveApex uses a layered acoustic model rather than a single oscillator. Telemetry is converted into a driving scene and continuously weighted sound layers. The sample renderer selects nearby recordings by RPM, load and speed, then crossfades between them instead of switching abruptly.

The current architecture supports motor core, inverter/electric whine, low-frequency body, air/speed presence, acceleration load, regenerative braking, launch transients, Sound DNA and stereo spatial routing. Separate interior and exterior gain paths are reserved for the final PCM buses.

The design takes inspiration from the systems approach used by premium EV sound systems: multiple sources, telemetry-driven mixing and continuous transitions. Proprietary OEM recordings are not bundled or copied.

## Competitive direction

DriveApex is designed to go beyond fixed OEM sound presets. Sound DNA can continuously shape aggression, futuristic character, mechanical body, inverter presence, low end, high-frequency energy and cabin focus. The sample-grid architecture adds another advantage: we can build a much denser sound map than a single factory preset and tune it to the actual BYD hardware.

## Sample bank layout

Future original/appropriately licensed assets are expected under:

```text
app/src/main/res/raw/audio/gt/
  motor_low.wav
  motor_mid.wav
  motor_high.wav
  inverter_low.wav
  inverter_high.wav
  air_speed.wav
  regen.wav
  launch.wav
```

The manifest is metadata-only for now; no copyrighted vehicle recordings are included in the repository.

## Roadmap

1. Add and validate original/appropriately licensed sample recordings.
2. Replace the procedural layer renderer with a PCM sample player while keeping the same telemetry model.
3. Add granular/micro-loop stitching to eliminate obvious repetition.
4. Implement separate cabin/exterior buses with EQ and spatial processing.
5. Add BYD/DiLink vehicle-data adapter and real telemetry input.
6. Tune latency, audio focus and head-unit routing on actual hardware.
7. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
