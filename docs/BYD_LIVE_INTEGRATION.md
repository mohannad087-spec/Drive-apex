# DriveApex — BYD Live Integration Contract

## Goal

Connect verified vehicle telemetry from a BYD/DiLink bridge to the existing `VehicleData` model without coupling the audio engine to undocumented OEM internals.

## Architecture

`BYD/DiLink source -> verified adapter/bridge -> VehicleData -> TelemetrySmoother -> EngineSoundController -> AudioScene + Sound Genome -> renderer`

The audio engine must never write to vehicle control systems. It is a read-only telemetry consumer.

## Transport for the first vehicle test

Use the existing UDP gateway on port `38901` as the first bridge contract. This allows the phone APK to be tested before implementing a vehicle-specific adapter.

Example packet:

```json
{
  "rpm": 3200,
  "speedKph": 54,
  "throttle": 0.62,
  "brake": 0.0,
  "regen": 0.10
}
```

Values are normalized at the adapter boundary:

- throttle: 0.0–1.0
- brake: 0.0–1.0
- regen: 0.0–1.0
- speedKph: non-negative
- rpm: motor-speed representation used by DriveApex

## Vehicle-side adapter requirements

The adapter must explicitly identify the source and timestamp every frame. It must reject malformed, stale, or out-of-range packets.

Minimum frame fields:

- `timestampMs`
- `rpm`
- `speedKph`
- `throttle`
- `brake`
- `regen`
- `source`

Recommended optional fields for the next iteration:

- motor torque/load
- accelerator pedal raw percentage
- wheel speeds
- drive mode
- gear/reduction state
- steering angle
- battery power
- inverter temperature

## Safety boundary

DriveApex is read-only. The integration must not expose vehicle-control commands, actuator writes, CAN injection, gateway unlocks, or safety-system overrides.

If live telemetry becomes stale, the application must enter its existing safe audio state rather than holding the last vehicle state indefinitely.

## First vehicle test

1. Keep the phone APK in SIM mode and verify audio.
2. Establish a bridge that can produce the contract packet.
3. Connect phone to the bridge over the same local network/hotspot.
4. Switch the APK to LIVE UDP.
5. Verify live speed/RPM/throttle changes before enabling any aggressive audio profile.
6. Test stationary first.
7. Test low-speed controlled movement in a safe location.
8. Compare telemetry timestamps against audible response and tune latency.

## Important

Do not assume a particular BYD/DiLink API, property name, port, ADB service, or CAN signal until it has been observed and verified on the target vehicle/software version.
