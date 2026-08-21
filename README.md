# DriveApex

DriveApex is an Android prototype for a BYD-oriented dynamic engine sound simulator.

## Current milestone

- Kotlin Android application using Gradle.
- Landscape-first UI suitable for an in-vehicle display.
- Optional Android Automotive hardware feature declaration.
- Real-time synthesized engine sound prototype driven by RPM.
- GitHub Actions pipeline that builds and uploads a debug APK.

## Next milestones

1. Replace the synthesized fallback with a layered sample bank.
2. Add throttle, vehicle speed, gear and load inputs.
3. Add a vehicle-data adapter for the available BYD/DiLink integration path.
4. Tune latency, audio focus and head-unit routing.
5. Add profiles, sound presets and persistent settings.
6. Produce signed release APKs after on-device validation.

## Build locally

```bash
gradle assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.
