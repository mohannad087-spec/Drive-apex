# Grain sources

A granular voice is built from a **rev ramp**, not a loop: ten seconds or more
of one engine climbing from idle to the top of its range, and ideally a second
ten seconds of it coming back down off the throttle.

    python3 tools/build_grain_source.py pull.mp3  --id cadillac --name "Cadillac 1985"
    python3 tools/build_grain_source.py lift.mp3  --id cadillac_off --name "Cadillac 1985" --load off

The tool writes the audio plus a map of the firing frequency through it, and
prints how much of a rev range the recording covers. A gear spans about 3x; a
recording covering less than that has to be stretched at the top of every gear.

Nothing ships here at the moment. The first source was built from a 7.5 second
phone recording, and on the vehicle it did not hold up next to the synthesised
voices -- which is what a recording that short and that noisy sounds like once
it is asked to cover a whole rev range. The player and the tool are both
measured and ready; what they need is one good ramp.
