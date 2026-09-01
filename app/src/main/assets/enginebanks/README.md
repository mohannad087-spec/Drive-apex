# Engine sample banks

Drop recordings here, plus one JSON manifest per bank. Anything found is decoded
at startup and appears as an extra button in the SOUND DNA row. With nothing
here the app synthesises as before.

    enginebanks/
      sport_petrol.json
      sport_petrol/
        off_1400.ogg  on_1400.ogg
        off_2400.ogg  on_2400.ogg
        off_3600.ogg  on_3600.ogg
        off_5000.ogg  on_5000.ogg

Manifest:

    {
      "id": "sport_petrol",
      "name": "Petrol Sport",
      "level": 0.9,
      "layers": [
        { "file": "sport_petrol/off_1400.ogg", "rpm": 1400, "load": 0,
          "loopStartMs": 120, "loopEndMs": 980 },
        { "file": "sport_petrol/on_1400.ogg",  "rpm": 1400, "load": 1 }
      ]
    }

- `rpm` is the engine speed the file was recorded at. Playback rate is the rpm
  being sounded divided by this, so getting it wrong detunes the whole bank.
- `load` is 0 for a closed throttle and 1 for full load. The pedal crossfades
  between the two, which is what makes coasting and pulling sound different at
  the same rpm.
- `loopStartMs`/`loopEndMs` are optional; without them the whole file loops.
- Any format the device decodes works: ogg, m4a, mp3, wav. Files are folded to
  mono and resampled to 44.1kHz on load.

A bank needs at least two rpm points at one load to play. Capture guidance,
including the rpm grid and how to prepare a seamless loop, is in
docs/SAMPLE_RECORDING_SPEC.md.
