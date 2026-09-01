# DriveApex Sample Recording Specification

DriveApex uses original or appropriately licensed recordings only. This document defines the capture contract so every sample can be rendered continuously without audible seams.

## Capture targets

- 48 kHz WAV preferred; 24-bit capture preferred.
- Record dry source audio with no mastering limiter.
- Preserve at least 6 dB peak headroom during capture.
- Record multiple takes at each operating point.
- Use consistent microphone placement for every take.

## Core operating grid

Capture at approximately:

- 900 / 1400 / 2000 / 2800 / 3600 / 4400 / 5200 / 6000 RPM-equivalent motor-speed zones.
- 0 / 0.25 / 0.50 / 0.75 / 1.00 normalized load.
- 0 / 30 / 60 / 100 / 150 / 200+ km/h speed bands where applicable.

## Dedicated event samples

Record clean isolated events for:

- launch
- hard acceleration hit
- lift-off
- light regen
- hard regen
- brake transition
- high-speed rush

Events should be short, phase-stable and captured with enough leading/trailing silence to permit precise trimming.

## Loop preparation

Each sustained sample should provide:

- loopStartMs
- loopEndMs
- zero-crossing-safe edit points
- pitchReferenceRpm
- normalized loudness metadata

The runtime must crossfade between adjacent samples rather than switching abruptly.

## Spatial capture

When possible, capture both cabin and exterior perspectives as separate banks. Do not mix these perspectives into one file; DriveApex uses independent interior/exterior buses.

## Rights

Every asset must have documented permission for inclusion in DriveApex. OEM-proprietary recordings must not be copied or redistributed.

## Bank manifest

Prepared recordings are described by a JSON manifest under
`app/src/main/assets/enginebanks/`. See the README there for the schema and an
example. Each entry names the file, the rpm it was captured at, whether it is
the closed- or open-throttle take, and its loop points.

Two rpm points at one throttle end is the minimum a bank can play with; the
grid above is what keeps any one recording from being stretched more than about
1.4x, beyond which it stops sounding like the engine it came from.

## Measured characters

Recordings too short or too unsteady to loop are still useful as a measurement.
`tools/build_engine_bank.py` reports the harmonic balance and the broad
spectral peaks of any recording; those numbers can be transcribed into an
`EngineCharacter` directly.

`measured_petrol` was built this way from a short street-car rev clip sourced
from Freesound. No audio from it ships in the app -- only the numbers measured
off it, which is what made a three-second clip enough.

The finding worth keeping: a real engine's orders fall away steeply beneath one
dominant partial, where a hand-tuned character tends to come out nearly flat.
A flat harmonic stack is most of what makes a synthesised engine sound like a
synthesiser.
