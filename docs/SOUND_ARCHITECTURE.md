# DriveApex Sound Architecture

DriveApex is designed around an adaptive acoustic scene rather than a single looping engine sound.

## Target architecture

- 8+ procedural/sample layers in the first profile.
- Separate interior and exterior buses.
- Continuous crossfades by RPM, throttle, load and speed.
- Transient scenes: idle, coast, acceleration, hard acceleration, launch, regeneration and high speed.
- Real sample assets can replace individual layers without changing telemetry or scene logic.
- Future spatial routing can position motor/inverter/body layers differently across available speakers.

## Differentiation goal

The goal is not to copy Audi's sound. Audi publicly describes its e-tron GT system as a 32-source adaptive synthesis driven by motor speed, accelerator position, vehicle speed and other parameters. DriveApex should use the same class of engineering idea while developing an original acoustic identity.

Potential advantages for DriveApex:

1. Per-driver sound personalization.
2. Profiles that can be blended instead of simple presets.
3. Separate interior/exterior intensity curves.
4. Event-aware transients for launch, lift-off and regeneration.
5. Vehicle-specific calibration once real BYD telemetry is available.
6. A sample-bank pipeline supporting high-quality owned/licensed recordings.

## Sample asset policy

Do not bundle copyrighted manufacturer recordings unless we have permission or a suitable license. Reference vehicles are used for acoustic design analysis only.
