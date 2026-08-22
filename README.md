# DriveApex

DriveApex is an Android application for BYD-oriented dynamic EV drive sound.

## Current milestone

- Kotlin Android application using Gradle.
- Phone-first test lab that can later adapt to an in-vehicle display.
- Real-time layered EV sound renderer with procedural fallback.
- Premium electric-GT and original Apex sound profiles.
- Audio parameters driven by motor speed/RPM, accelerator load, vehicle speed, brake and regenerative-braking state.
- Adaptive sound scenes with continuous transitions and safe fallback on stale live telemetry.
- Sample-grid renderer architecture with smooth selection of nearby samples.
- Pitch tracking metadata and transient sample support for launch/regen events.
- Stereo spatial mix model with cabin/exterior routing reserved for final audio buses.
- Live telemetry gateway over UDP for phone/bridge testing.
- Adaptive Driver Sonic Signature that learns driving style locally and continuously shapes Sound DNA.
- Sonic Choreography layer that turns driving changes into short-lived acoustic accents rather than only changing continuous volume.
- Live acoustic event monitor in the phone test lab for tuning by ear and by telemetry.
- GitHub Actions pipeline for APK builds.

## Phone test lab

The phone UI exposes simulator controls for RPM, throttle and speed, plus quick scenes for IDLE, PULL, BOOST, COAST and REGEN. The app can switch to `LIVE UDP` mode and listen on port `38901`. The UI also displays the driver's evolving Sonic Signature and live event intensities.

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

## Adaptive Sonic Signature

DriveApex is designed around a driver-specific acoustic identity rather than one fixed preset. A lightweight local model tracks acceleration style, pedal smoothness, regenerative-braking preference, high-speed use and launch behaviour. Those signals continuously shape the Sound DNA while the vehicle is being driven.

The intent is that two drivers in the same car can produce different acoustic personalities without manually selecting a different preset. The system remains deterministic, bounded and local; it does not require a cloud service.

## Sonic Choreography

Telemetry changes are converted into transient event intensities for:

- Launch
- Acceleration hit
- Lift-off
- Regeneration hit
- Brake hit
- High-speed rush

Each transient has a real decay envelope so a sustained condition does not retrigger a full-strength event every frame. The current procedural renderer adds short accents over the continuous sound bed. The final sample renderer will replace these accents with original/appropriately licensed one-shot recordings and micro-stingers.

## Sound architecture

DriveApex uses a layered acoustic model rather than a single oscillator. Telemetry is converted into a driving scene and continuously weighted sound layers. The sample renderer is designed to select nearby recordings by RPM, load and speed and crossfade between them instead of switching abruptly.

The architecture supports motor core, inverter/electric whine, low-frequency body, air/speed presence, acceleration load, regenerative braking, launch transients, Sound DNA, Sonic Choreography and stereo spatial routing. Proprietary OEM recordings are not bundled or copied.

## Competitive direction

DriveApex is designed to go beyond fixed OEM sound presets. The combination of a dense sample map, transient event composition and a driver-specific Sonic Signature is intended to make the acoustic experience adaptive rather than static.

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
  lift_off.wav
  brake_hit.wav
  launch.wav
```

No copyrighted vehicle recordings are included in the repository.

## Roadmap

1. Add and validate original/appropriately licensed sample recordings.
2. Replace the procedural layer renderer with a PCM sample player while keeping the same telemetry model.
3. Add granular/micro-loop stitching and phase-aware micro-crossfades to eliminate obvious repetition.
4. Drive transient samples from the Acoustic Event Composer and tune them with the phone event monitor.
5. Persist and tune the Driver Sonic Signature across drives with a local profile.
6. Implement separate cabin/exterior buses with EQ and spatial processing.
7. Connect the live gateway to the actual BYD/DiLink data source after the data path is verified on the target vehicle.
8. Tune latency, audio focus and head-unit routing on actual hardware.
9. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
