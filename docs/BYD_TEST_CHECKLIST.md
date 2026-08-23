# BYD Vehicle Test Checklist

## Before connecting the vehicle

- Install the latest successful DriveApex APK.
- Keep `STOP / SAFE` accessible.
- Start with SIM mode and verify audio still works.
- Do not enable any write/control channel.

## Telemetry validation

Confirm the bridge provides, at minimum:

- timestampMs
- rpm
- speedKph
- throttle
- brake
- regen

The phone must show LIVE data changing before audio evaluation begins.

## Test order

1. Vehicle stationary: verify LIVE connection.
2. Stationary throttle input only if the vehicle exposes a valid motor-speed signal without moving.
3. Very low-speed controlled movement in a safe/private area.
4. Gentle acceleration.
5. Lift-off/coast.
6. Controlled regeneration.
7. Repeat with stronger acceleration.

## Measurements to record

- Telemetry update rate.
- Telemetry-to-audio latency.
- Any stale-frame events.
- RPM stability.
- Scene transition timing.
- Audible crossfade quality.
- Any clipping or distortion.
- Whether the sound remains synchronized with vehicle behaviour.

## Stop conditions

Immediately stop the audio test if telemetry becomes unreliable, the displayed speed/RPM is clearly wrong, audio becomes unexpectedly continuous, or the phone/vehicle connection behaves unpredictably.

## Important

The first vehicle session is a telemetry and audio-latency validation only. Do not use the application to control, unlock, configure, or inject commands into vehicle systems.
