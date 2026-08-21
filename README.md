# DriveApex

DriveApex is an Android application for BYD-oriented dynamic EV drive sound.

## Current milestone

- Kotlin Android application using Gradle.
- Phone-first test lab that can later adapt to an in-vehicle display.
- Real-time layered EV sound renderer.
- Premium electric-GT and original Apex sound profiles.
- Audio parameters driven by motor speed/RPM, accelerator load, vehicle speed, brake and regenerative-braking state.
- Adaptive sound scenes with continuous transitions.
- Sample-grid renderer architecture with smooth selection of nearby samples.
- Pitch tracking metadata and transient sample support for launch/regen events.
- Stereo spatial mix model with cabin/exterior routing reserved for the final audio buses.
- Live telemetry gateway over UDP for phone/bridge testing.
- GitHub Actions pipeline for APK builds.

## Phone test lab

The phone UI exposes simulator controls for RPM, throttle and speed, plus quick scenes for IDLE, PULL, BOOST, COAST and REGEN. The app can switch to `LIVE UDP` mode and listen on port `38901`.

Example telemetry packet:

```json
{
  "rpm": 3200,
  "speedKph": 54,
  "throttle": 0.62,
  "brake": 0.0,
  "regen": 0.10
}
```

This transport is a bridge/testing contract, not a claim about an undocumented BYD API. A BYD/DiLink adapter can feed the same `VehicleData` model later without changing the audio renderer.

## Sound architecture

DriveApex uses a layered acoustic model rather than a single oscillator. Telemetry is converted into a driving scene and continuously weighted sound layers. The sample renderer is designed to select nearby recordings by RPM, load and speed and crossfade between them instead of switching abruptly.

The architecture supports motor core, inverter/electric whine, low-frequency body, air/speed presence, acceleration load, regenerative braking, launch transients, Sound DNA and stereo spatial routing. Proprietary OEM recordings are not bundled or copied.

## Competitive direction

DriveApex is designed to go beyond fixed OEM sound presets. Sound DNA can continuously shape aggression, futuristic character, mechanical body, inverter presence, low end, high-frequency energy and cabin focus. The sample-grid architecture allows a denser sound map and tuning specific to the actual BYD hardware.

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

No copyrighted vehicle recordings are included in the repository.

## Roadmap

1. Add and validate original/appropriately licensed sample recordings.
2. Replace the procedural layer renderer with a PCM sample player while keeping the same telemetry model.
3. Add granular/micro-loop stitching to eliminate obvious repetition.
4. Implement separate cabin/exterior buses with EQ and spatial processing.
5. Connect the live gateway to the actual BYD/DiLink data source after the data path is verified on the target vehicle.
6. Tune latency, audio focus and head-unit routing on actual hardware.
7. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
