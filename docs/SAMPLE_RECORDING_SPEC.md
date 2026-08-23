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
