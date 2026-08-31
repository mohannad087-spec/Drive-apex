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

## Compile-time BYD HAL stubs

`app/src/main/java/android/hardware/bydauto/` holds compile-time-only stub
classes (`BYDAutoFeatureIds`, `BYDAutoEventValue`, `AbsBYDAutoDevice`,
`engine/AbsBYDAutoEngineListener`, `engine/BYDAutoEngineDevice`) that mirror
the vendor `bmmcamera.jar` API surface actually used by this project. They
exist only so app code can compile and link against typed BYD HAL calls; on
the vehicle, the privileged telemetry daemon is launched with the real
vendor jar ahead of the APK on its classpath, so the real classes shadow
these stubs at runtime. Only feature IDs verified against a working
DiPlus/Overdrive collector path belong in `BYDAutoFeatureIds`.

`app/src/main/java/com/driveapex/vehicle/FrontMotorSpeedReader.java` is a
type-safe reader built on these stubs for
`BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED`. It is meant to run inside the
privileged daemon process alongside the existing reflection-based readers
(`BydDiPlusEngineTelemetryDaemonMain`, `DirectBydTelemetryReader`), not the
main app process, since the app itself has no direct BYD HAL access.

## Confirmed HAL listener dispatch (from a full DiPlus decompile)

A first pass analyzed a small hand-transcribed slice of `com.van.diplus`
(`classes2.dex` from a DiPlus APK). A follow-up full decompile of the actual
APK (androguard, DAD decompiler, all of `classes.dex`/`classes2.dex`)
confirmed that read and resolved the one piece it had left uncertain: the
exact feature ID and the exact branch front motor speed takes.

- `AbsBYDAutoEngineListener`'s real dispatch entry point for a registered
  feature is `onDataEventChanged(int type, BYDAutoEventValue value)`, where
  `type` is the feature ID that changed. Confirmed from decompiled source,
  not inferred.
- `BYDAutoEventValue` exposes plain public fields `intValue` (int) and
  `doubleValue` (double), read/written directly — not getter methods.
- DiPlus resolves each feature ID it cares about through a small helper
  (`z.d.a(primary, fallback)`) that tries a live `sget` against the real
  `android.hardware.bydauto.BYDAutoFeatureIds` field first, falling back to a
  literal `int` if that field can't be resolved (`NoClassDefFoundError`).
  Decompiling that helper's inputs for the front-motor-speed constant shows:
  primary = `android.hardware.bydauto.BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED`,
  fallback = **`1141899272`** — the exact same literal this codebase already
  uses everywhere (`BYDAutoFeatureIds.ENGINE_FRONT_MOTOR_SPEED`,
  `FALLBACK_FRONT_MOTOR_SPEED`, `FRONT_RPM_FALLBACK`, etc.). That value is
  now cross-verified against DiPlus's own compiled fallback, independent of
  this codebase's own history.
- In `onDataEventChanged`, the branch for that exact feature ID reads
  `value.intValue`, negates it, and stores it directly — **it never calls
  `onEngineSpeedChanged`**. `onEngineSpeedChanged` fires only from a
  different branch, for a separate, unrelated feature ID that DiPlus feeds
  from `BYDAutoEngineDevice.getEngineSpeed()` (DiPlus's own generic "engine
  speed", most likely combustion-engine RPM on a hybrid, not the front
  motor). Treating `onEngineSpeedChanged` as a front-motor-speed fallback, as
  an earlier version of this fix did, risks mixing in that unrelated signal
  instead of doing nothing.

Before this was found, `BydDiPlusEngineTelemetryDaemonMain`'s
`FrontMotorListener` only overrode `onEngineSpeedChanged`, which the HAL
never calls for this feature — the confirmed explanation for the front
motor speed feature repeatedly failing to update across earlier fixes. It
now overrides only `onDataEventChanged`, checked against the registered
feature ID. `FrontMotorSpeedReader` follows the same pattern. Neither
listener re-applies DiPlus's sign negation; both already take the absolute
value downstream, which makes the sign convention irrelevant. DiPlus also
applies no min/max clamp on this branch (unlike the 0..8000 clamp on the
unrelated `onEngineSpeedChanged` branch), consistent with EV front motor
RPM legitimately exceeding a combustion engine's redline -- this codebase's
existing 25,000 RPM sanity ceiling was left as is.

## The in-app direct reader was masking the daemon fix

Confirming the listener dispatch bug above is not enough on its own: on
real hardware, `MainActivity`'s live pipeline (`UdpTelemetryReceiver`)
never actually reaches the (now-fixed) privileged daemon for motor speed at
all, because of a second, independent bug.

`UdpTelemetryReceiver.liveLoop()` polls `DirectBydTelemetryReader` first —
an in-app-process reader that calls BYD HAL directly, without going through
the ADB-launched daemon. Its own `BydPermissionContext` overrides
`checkPermission`/`checkSelfPermission` etc. to always return `GRANTED`,
but that only fools client-side checks inside this process; it cannot
grant the real, OS-enforced `BYDAUTO_ENGINE_GET`-tier permission, which
only `VehicleAdbConnection.grantBydReadPermissions` (run through the ADB
shell UID) can actually obtain. So in this process, `getCurrentSpeed`/
`getAccelerateDeepness`/`getBrakeDeepness` (a less privileged tier) tend to
succeed, while the front-motor-speed read silently fails and `readOnce()`
defaults `motorSpeed` to `0f`.

The bug: `UdpTelemetryReceiver.isUsable()` only checked that `motorSpeed`
was finite and in range, and `0f` satisfies that. So every direct-reader
frame counted as fully "usable", which reset the failure counter that was
supposed to trigger falling back to the privileged daemon — the daemon
holding our `onDataEventChanged` fix was never even started for motor
speed, no matter how correct that fix was.

Fixed by tracking whether the direct reader actually obtained a motor
speed reading (`DirectBydTelemetryReader.Frame.motorSpeedAvailable`,
`true` only when its own front-motor-speed read succeeded) and, in
`UdpTelemetryReceiver`, starting the privileged daemon bridge specifically
to source the RPM field whenever `motorSpeedAvailable` is `false` —
speed/throttle/brake still come from the direct reader, only RPM is
spliced in from the daemon's latest frame. The direct reader is otherwise
left as-is on purpose, since re-verifying its speed/throttle/brake access
was out of scope here.

## Important

Do not assume a particular BYD/DiLink API, property name, port, ADB service, or CAN signal until it has been observed and verified on the target vehicle/software version.
