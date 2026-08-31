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

## Feature IDs are (CAN id << 20) | signal offset

A logcat capture from the target vehicle shows the HAL logging every
dispatched event:

```
D/BYDAutoSpeedDevice(25065):     postEvent device_type: 1013, event_type =12100008, value = 25.98
D/BYDAutoStatisticDevice(25065): postEvent device_type: 1014, event_type =44a00020, value = 6132
```

Decoding those hex event types against the constants recovered from the
DiPlus decompile shows the layout is `(CAN id << 20) | signal offset`:
`0x12100008` is CAN `0x121` (vehicle speed), `0x447xxxxx` is CAN `0x447`
(the battery-temperature family — DiPlus's own STATISTIC_*_BATTERY_TEMP
constants land at `0x44700020`/`0x44700038`, straddling the `0x44700028`
in the capture). `ENGINE_FRONT_MOTOR_SPEED` = `1141899272` = `0x44100008`,
i.e. CAN `0x441` — and DiPlus's neighbouring constant `1141899288` =
`0x44100018` is the same CAN frame at a different offset, consistent with
`0x441` being the front-motor message.

CAN `0x441` never appears anywhere in that capture, while `0x445`,
`0x446`, `0x447` and `0x44a` all do. `postEvent` is logged once per
*subscribed process*, so an event only shows up if something registered
for it — the absence of `0x441` is evidence that nothing was subscribed to
the engine device, not proof that the vehicle does not broadcast it.

## Register the engine listener unfiltered, like DiPlus does

That points at how the subscription itself is made. In DiPlus's
`DiplusBYDDataByApi.B()`, the engine listener is registered with a **null**
feature-ID array:

```java
this.h1 = (AbsBYDAutoEngineListener) C(v3_12, 0, v6_5, v7_7);   // p4 == null
```

and `C()` only takes the filtered path when that array is non-null:

```java
if (p4 != null) { if (SDK_INT >= 29) { v5_2.registerListener(v3_2, A(v5_2.getDevicetype(), p4)); return v3_2; } }
v5_2.registerListener(v3_2);    // single-arg: every event from this device
```

So DiPlus subscribes to *all* engine-device events and filters by `type`
inside `onDataEventChanged`. This codebase did the opposite: it looked only
for the two-argument overload and passed IDs through
`BYDAutoDeviceFeaturesMap.getFeatureIdsFromDevice`, which (per DiPlus's own
`A()`) returns the **intersection** with the device's declared feature set.
If `ENGINE_FRONT_MOTOR_SPEED` is not in that set on a given vehicle, the
array comes back empty, the listener is registered for nothing, and no
event ever arrives — indistinguishable from a wrong feature ID, and an
exact match for the observed "RPM stays 0 forever".

`BydDiPlusEngineTelemetryDaemonMain` now prefers the single-argument
`registerListener(listener)`, falls back to the two-argument form, and
never registers an empty feature array. Because the subscription is now
unfiltered, the listener also records every `(type, value)` it is handed —
logged to the daemon log every 5s and exposed on the scan port as
`HIT,ENGINE,EVENTS,count=…,types=0x…=v;…`. That list is what settles
whether the feature ID is wrong or the events were simply never delivered.

## Only the _COMMON permissions were ever granted

Running the unfiltered-listener build on the vehicle settled it. The
diagnostics probe reported:

```
ENGINE REGISTRATION  registerListener(listener):unfiltered   rpm=0
ENGINE EVENTS        count=0  types=NONE
BYD permissions declared: 10/10
BYD permissions reported granted: 4/10
  BYDAUTO_SPEED_COMMON  BYDAUTO_ENGINE_COMMON
  BYDAUTO_ENERGY_COMMON BYDAUTO_GEARBOX_COMMON
```

Registration succeeds, it is genuinely unfiltered, and **zero** events
arrive. That rules the feature ID out entirely: an unfiltered subscription
that receives nothing at all cannot be explained by asking for the wrong
id.

The granted list is the answer, and it is exactly the four entries that
were hardcoded in `VehicleAdbConnection.BYD_RUNTIME_GRANT_PERMISSIONS`.
The manifest declares ten; the other six — every `_GET` permission,
including `BYDAUTO_ENGINE_GET` and `BYDAUTO_MOTOR_GET` — were never passed
to `pm grant` at all. Registering a listener only needs the `_COMMON`
tier, which is why registration reported success while no engine value was
ever delivered. All ten declared permissions are now granted.

Note the split this implies, and which matches every earlier observation:
the `_COMMON` tier is enough to open a device and register, the `_GET`
tier is what actually releases values. It is also why the in-process
`DirectBydTelemetryReader` could read speed/throttle/brake but never motor
speed.

## Never call the daemon bridge from the live loop

`UdpTelemetryReceiver.startBydDaemonForRpm()` originally called
`BydHalTelemetryBridge.isAvailable()` inline. That call goes out over ADB —
shell commands, an APK copy, then up to several seconds polling for the
daemon's port — and on failure it was retried on every 50 ms tick. The live
loop stalled, every frame aged past `staleAfterMs`, and the dashboard blanked
out completely: speed and pedals as well as RPM, on a vehicle where those had
been working. The start attempt now runs on a background thread, rate-limited,
and the loop keeps publishing from the direct reader while it happens.

## DiPlus has no privilege we lack — it just declares more

The assumption that DiPlus reads the motor because it is a system app or carries
a BYD platform signature is **wrong**. Inspecting the APK directly:

```
package      : com.van.diplus
sharedUserId : None                     <- ordinary app UID, not android.uid.system
certificate  : subject CN=vanjoge
               issuer  CN=vanjoge       <- self-signed developer key, not BYD's
```

DiPlus is a self-signed third-party APK with no privileged install, and it reads
the HAL straight from its own process: `getInstance(context)` then
`registerListener(listener)`. No ADB, no shell-UID daemon, no spoofed Context —
none of the machinery this project built.

The one measurable difference is the manifest: **DiPlus declares 120 BYD
permissions; this app declared 10.** If a self-signed app can read the motor by
declaration alone, these permissions are reachable without any of the workarounds,
and the entire daemon architecture may be solving a problem that does not exist.

Also correcting an earlier reading of the diagnostics: "granted 4/10" did **not**
mean six grants were refused. `grantBydReadPermissions` iterated only over the
four-entry `BYD_RUNTIME_GRANT_PERMISSIONS` array, so only four were ever
attempted, while the screen compares against a ten-entry `REQUIRED_PERMISSIONS`
list. The other six were never asked about, and may already have been held.

Acting on this: the manifest now declares the read-side BYD permissions for every
device this app touches — including `BYDAUTO_STATISTIC_*` and
`BYDAUTO_INSTRUMENT_*`, which the vehicle log showed carrying the fast-changing
drivetrain values and which were never declared at all. GET/COMMON only; a
read-only telemetry consumer must never hold a `_SET` permission.
`UdpTelemetryReceiver` now tries the in-process listener (`FrontMotorSpeedReader`,
written earlier and never wired up) before the ADB daemon.

### The stub-shadowing trap this exposes

`Class.forName("android.hardware.bydauto.…")` succeeding in the app process proves
nothing on its own: this APK ships its own compile-time stubs under those exact
names. Boot-classpath classes report a null class loader; APK classes report the
app's `PathClassLoader`. If the engine device resolves to the local stub, then
`getInstance()` returns null and no in-process read can ever work, regardless of
permissions — and `isBydVehicleRuntime()` would be reporting a false positive on
any device. The diagnostics screen now prints which of the two it actually got.

## Important

Do not assume a particular BYD/DiLink API, property name, port, ADB service, or CAN signal until it has been observed and verified on the target vehicle/software version.
