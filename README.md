# DriveApex

DriveApex is an Android application for BYD-oriented dynamic EV drive sound.

## Current milestone

- Kotlin Android application using Gradle.
- Landscape-first UI suitable for an in-vehicle display.
- Real-time layered EV sound renderer.
- Premium electric-GT sound profile with multiple controllable layers.
- Audio parameters driven by motor speed/RPM, accelerator load and vehicle speed.
- Stereo output architecture ready for separate cabin/exterior routing later.
- Sound-layer model designed so procedural placeholders can be replaced by recorded samples without changing the vehicle-control layer.
- GitHub Actions pipeline for APK builds.

## Sound architecture

The sound system is intentionally based on multiple simultaneous acoustic sources rather than a single oscillator. The current prototype includes motor core, harmonics, inverter/electric whine, low-frequency body, load pulse and speed/air presence layers.

The design follows the engineering principles used in premium EV sound systems: drive-system telemetry controls the mix continuously, the sound grows in density and intensity with load and speed, and individual layers can be tuned independently. Audi describes its e-tron GT sound as a synthesis built from 32 individual sound sources controlled by electric-motor speed, accelerator position, vehicle speed and other parameters; DriveApex uses the same general design philosophy with original sound layers.

## Roadmap

1. Replace procedural placeholder layers with high-quality original/appropriately licensed sample recordings.
2. Add sample crossfades and pitch tracking across RPM/load regions.
3. Add separate interior and exterior buses with independent gain/EQ.
4. Add acceleration, lift-off, regenerative braking and coast sound states.
5. Add BYD/DiLink vehicle-data adapter and real telemetry input.
6. Tune latency, audio focus and head-unit routing on actual hardware.
7. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
