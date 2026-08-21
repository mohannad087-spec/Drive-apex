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
- Sound-layer model designed so procedural placeholders can be replaced by recorded samples without changing the vehicle-control layer.
- GitHub Actions pipeline for APK builds.

## Sound architecture

The sound system is intentionally based on multiple simultaneous acoustic sources rather than a single oscillator. The current model supports motor core, harmonics, inverter/electric whine, low-frequency body, load pulse, speed/air presence, scene detection, adaptive weighting, Sound DNA and stereo spatial routing.

The design follows the engineering principles used in premium EV sound systems: drive-system telemetry controls the mix continuously, the sound grows in density and intensity with load and speed, and individual layers can be tuned independently. Audi describes its e-tron GT sound as a synthesis built from 32 individual sound sources controlled by electric-motor speed, accelerator position, vehicle speed and other parameters; DriveApex applies the general systems principle with original sound layers rather than copying proprietary recordings.

## Competitive direction

DriveApex is designed to go beyond fixed OEM sound presets by allowing the driver to continuously shape the acoustic character. Sound DNA can blend aggression, futuristic character, mechanical body, inverter presence, low end, high-frequency energy and cabin focus without changing the telemetry pipeline.

The next quality jump is sample-based rendering: multiple high-quality recordings per acoustic layer, pitch tracking, transient matching, micro-crossfades, separate interior/exterior buses, and hardware-tuned spatial output.

## Roadmap

1. Replace procedural placeholder layers with high-quality original/appropriately licensed sample recordings.
2. Add sample crossfades and pitch tracking across RPM/load regions.
3. Implement real stereo/interior/exterior PCM buses with independent gain/EQ.
4. Add acceleration, launch, lift-off, regenerative braking and coast transient layers.
5. Add BYD/DiLink vehicle-data adapter and real telemetry input.
6. Tune latency, audio focus and head-unit routing on actual hardware.
7. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
